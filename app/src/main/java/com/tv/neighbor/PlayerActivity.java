package com.tv.neighbor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.io.File;

/**
 * 内置播放器（ExoPlayer/Media3）：遥控器友好的自研播放页。
 * - OK=播放/暂停；LEFT/RIGHT 短按 ±10s，**按住不放步长加速** 10→30→60→120→300s（长按快进）；
 * - 预读缓冲 45s 起、最多 120s（慢 SMB 不断流）+ 512MB LRU 磁盘缓存（重看秒开）；
 * - 解码失败（rmvb 等老格式）提示后按 OK 自动降级系统播放器。
 * 注意：本机固件 Dialog 内列表拿不到焦点，所以一切交互都用 Activity 自身按键处理，不用 Dialog。
 */
@UnstableApi
public class PlayerActivity extends Activity {
    /** 长按快进/快退的步长阶梯（秒）。 */
    private static final int[] STEPS = {10, 30, 60, 120, 300};
    private static final long HOLD_FIRST_MS = 550;  // 首次加速延迟（短按不触发）
    private static final long HOLD_TICK_MS = 450;   // 加速间隔

    private ExoPlayer player;
    private PlayerView view;
    private TextView hud, err;
    private String url, smbUrl, mime, name;
    private Cred cred;
    private boolean errored;
    private boolean launchAsAudio;
    // 同目录切换列表（BrowseActivity 传入）：按 ↑/菜单键弹出
    private String[] listUrls = new String[0];
    private String[] listNames = new String[0];
    private int curIdx = -1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable holdTick, hideHud;
    // 长按快进/快退：不依赖 UP 时机判定松开（这台遥控的重复模式不可靠），
    // 用"同方向按键事件间隔 < RESUME_GAP 视为仍按住、事件停了 RELEASE_GRACE 才算松开"。
    // 兼容三种固件：标准 autorepeat（UP 只在松手时来）/ 每次重复发 DOWN+UP 对 / 完全无 autorepeat。
    private int holdDir;          // 0 无，±1 方向
    private int holdIdx;
    private long lastDirEventAt;
    private static final long RESUME_GAP_MS = 500;    // 事件间隔小于此 = 还按着
    private static final long RELEASE_GRACE_MS = 350; // 最后一个事件后等这么久 = 松开
    private final Runnable endHoldR = new Runnable() {
        @Override
        public void run() {
            endHold();
        }
    };

    public static Intent intent(android.content.Context c, String httpUrl, String smbUrl,
                                String mime, String title, Cred cred,
                                String[] urls, String[] names, int idx) {
        Intent i = new Intent(c, PlayerActivity.class);
        i.putExtra("url", httpUrl);
        i.putExtra("smb", smbUrl);
        i.putExtra("mime", mime);
        i.putExtra("title", title);
        i.putExtra("cred", cred);
        i.putExtra("urls", urls);
        i.putExtra("names", names);
        i.putExtra("idx", idx);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 播放页活着（含后台暂停）就保住流服务，别让 5 分钟空闲判定把它杀了；
        // onDestroy 才释放。放 onCreate/onDestroy 而不是 onStart/onStop，
        // 否则 HOME 超 5 分钟回来服务已自杀、旧 URL 全作废。
        StreamService.setPlayerActive(true);
        url = getIntent().getStringExtra("url");
        smbUrl = getIntent().getStringExtra("smb");
        mime = getIntent().getStringExtra("mime");
        name = getIntent().getStringExtra("title");
        cred = (Cred) getIntent().getSerializableExtra("cred");
        listUrls = getIntent().getStringArrayExtra("urls");
        listNames = getIntent().getStringArrayExtra("names");
        curIdx = getIntent().getIntExtra("idx", -1);
        if (listUrls == null) listUrls = new String[0];
        if (listNames == null) listNames = new String[0];
        launchAsAudio = mime != null && mime.startsWith("audio/");
        setContentView(R.layout.activity_player);
        view = findViewById(R.id.player_view);
        hud = findViewById(R.id.p_hud);
        err = findViewById(R.id.p_err);
        ((TextView) findViewById(R.id.p_title)).setText(name);
        view.setKeepScreenOn(true);
        android.util.Log.d("NN", "player open: " + name + " url=" + url);
        startPlayer();
    }

    private void startPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("NetworkNeighbor/1.0")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true);

        DataSource.Factory dsf = http;
        SimpleCache cache = cache();
        if (cache != null) {
            final String key = smbUrl != null ? smbUrl : url; // 稳定缓存键：token 每次都变，smb url 不变
            dsf = new CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(http)
                    .setCacheKeyFactory((CacheKeyFactory) dataSpec -> key)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        }

        // 预读缓冲：最少 45s、最多 120s，起步 2.5s 即播；回退缓冲 60s（往回拨不重新下载）
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(45_000, 120_000, 2_500, 6_000)
                .setBackBuffer(60_000, true)
                .build();

        player = new ExoPlayer.Builder(this)
                .setLoadControl(load)
                // 关键：把带磁盘缓存/稳定缓存键/30s 读超时的 DataSource 工厂接进去。
                // 不接的话 Builder 默认用 DefaultDataSource.Factory（8s 读超时、无缓存），
                // 上面构建的 dsf 全是死代码。
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dsf))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), /* handleAudioFocus= */ true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                android.util.Log.d("NN", "player error: " + e);
                errored = true;
                endHold();
                err.setText(getString(R.string.player_error,
                        e == null || e.getMessage() == null ? "" : e.getMessage()));
                err.setVisibility(View.VISIBLE);
                view.setVisibility(View.INVISIBLE);
            }
        });
        view.setPlayer(player);
        view.setUseController(false);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        if (launchAsAudio) showHud("🎵 " + name, true);
    }

    // ---- 磁盘缓存（进程级单例；同一目录开两个 SimpleCache 会抛异常） ----

    private static volatile SimpleCache cache;

    private static SimpleCache cache() {
        SimpleCache s = cache;
        if (s == null) {
            synchronized (PlayerActivity.class) {
                if (cache == null) {
                    try {
                        android.content.Context c = App.instance();
                        DatabaseProvider db = new StandaloneDatabaseProvider(c);
                        cache = new SimpleCache(new File(c.getCacheDir(), "nn_media"),
                                new LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024), db);
                    } catch (Throwable t) {
                        android.util.Log.d("NN", "media cache init failed: " + t);
                        return null; // 缓存不可用就直连上游
                    }
                }
                s = cache;
            }
        }
        return s;
    }

    // ---- 遥控器按键 ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        android.util.Log.d("NN", "key code=" + keyCode + " act=" + event.getAction()
                + " rep=" + event.getRepeatCount());
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_MENU) {
            // 出错页也允许开列表——这是用户逃出解码失败最快的路
            openSwitchMenu();
            return true;
        }
        if (errored) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                fallbackToSystemPlayer();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        if (player == null) return super.onKeyDown(keyCode, event);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                onDirKey(1, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                onDirKey(-1, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                // 用 playWhenReady 判断而不是 isPlaying()：BUFFERING 期间 isPlaying()==false，
                // 会导致慢 SMB 缓冲中按 OK 暂停失效且 HUD 误报"播放"
                if (player.getPlaybackState() == Player.STATE_ENDED) {
                    player.seekTo(0);
                    player.play();
                    showHud("▶ 重播", true);
                } else if (player.getPlayWhenReady()) {
                    player.pause();
                    showHud("⏸ 暂停", true);
                } else {
                    player.play();
                    showHud("▶ 播放", true);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.play();
                showHud("▶ 播放", true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.pause();
                showHud("⏸ 暂停", true);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                onDirKey(1, true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                onDirKey(-1, true);
                return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /**
     * 方向键统一入口（DOWN/UP 都走这里）。
     * - DOWN：新的一次按（换方向或距上次事件超 RESUME_GAP）→ 撤旧连跳、立即走第一步、
     *   550ms 后开始阶梯加速连跳（连跳节奏独立于按键事件，靠 endHoldR 撤）；
     *   仍按住 → 只撤销"松开宽限"，不打断连跳节奏。
     * - UP：不立即断（有的固件每次重复都发 DOWN+UP 对），等 RELEASE_GRACE 无新事件才算松开。
     */
    private void onDirKey(int dir, boolean up) {
        if (player == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (up) {
            ui.removeCallbacks(endHoldR);
            ui.postDelayed(endHoldR, RELEASE_GRACE_MS);
            lastDirEventAt = now;
            return;
        }
        if (dir == holdDir && now - lastDirEventAt < RESUME_GAP_MS) {
            ui.removeCallbacks(endHoldR); // 仍按着：连跳节奏已在跑
        } else {
            endHold();
            holdDir = dir;
            holdIdx = 0;
            seekBy(dir * STEPS[0]);
            holdTick = new Runnable() {
                @Override
                public void run() {
                    holdIdx = Math.min(holdIdx + 1, STEPS.length - 1);
                    seekBy(holdDir * STEPS[holdIdx]);
                    ui.postDelayed(this, HOLD_TICK_MS);
                }
            };
            ui.postDelayed(holdTick, HOLD_FIRST_MS);
        }
        lastDirEventAt = now;
    }

    private void endHold() {
        if (holdTick != null) {
            ui.removeCallbacks(holdTick);
            holdTick = null;
        }
        ui.removeCallbacks(endHoldR);
        holdDir = 0;
        holdIdx = 0;
    }

    private void seekBy(long deltaSec) {
        if (player == null) return;
        long dur = player.getDuration();
        long target = player.getCurrentPosition() + deltaSec * 1000L;
        if (dur > 0) target = Math.min(target, dur - 500);
        target = Math.max(0, target);
        player.seekTo(target);
        showHud((deltaSec > 0 ? "⏩ 快进 " : "⏪ 快退 ") + Math.abs(deltaSec)
                + " 秒 · " + fmt(target) + (dur > 0 ? " / " + fmt(dur) : ""), true);
    }

    /** 内置解码失败 → 重走系统播放器链路（同一流服务，只换 token）。 */
    private void fallbackToSystemPlayer() {
        try {
            err.setText("正在切换系统播放器…");
            final Activity a = this;
            new Thread(() -> {
                String http = StreamService.registerAndStream(getApplicationContext(), cred, smbUrl, mime);
                if (http == null) {
                    runOnUiThread(() -> Ui.toast(a, "启动流服务失败"));
                    return;
                }
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.parse(http), mime)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        finish();
                    } catch (Exception ex) {
                        Ui.toast(a, "系统播放器启动失败：" + ex.getMessage());
                    }
                });
            }, "nn-fallback").start();
        } catch (Exception ex) {
            Ui.toast(this, "系统播放器启动失败：" + ex.getMessage());
        }
    }

    /** singleTop 复用：等待流服务期间再选了另一个文件时，intent 会走这里而不是 onCreate。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        endHold();
        if (hideHud != null) { ui.removeCallbacks(hideHud); hideHud = null; }
        if (player != null) { player.release(); player = null; view.setPlayer(null); }
        errored = false;
        err.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
        url = intent.getStringExtra("url");
        smbUrl = intent.getStringExtra("smb");
        mime = intent.getStringExtra("mime");
        name = intent.getStringExtra("title");
        cred = (Cred) intent.getSerializableExtra("cred");
        listUrls = intent.getStringArrayExtra("urls");
        listNames = intent.getStringArrayExtra("names");
        curIdx = intent.getIntExtra("idx", -1);
        if (listUrls == null) listUrls = new String[0];
        if (listNames == null) listNames = new String[0];
        launchAsAudio = mime != null && mime.startsWith("audio/");
        ((TextView) findViewById(R.id.p_title)).setText(name);
        android.util.Log.d("NN", "player onNewIntent: " + name + " url=" + url);
        startPlayer();
    }

    // ---- 同目录切换 ----

    /** ↑/菜单键：弹出当前目录的视频列表，当前播放项标 ▶。 */
    private void openSwitchMenu() {
        if (listNames.length == 0) {
            Ui.toast(this, "当前目录没有可选视频");
            return;
        }
        String[] items = new String[listNames.length];
        for (int i = 0; i < listNames.length; i++) items[i] = (i == curIdx ? "▶ " : "") + listNames[i];
        Ui.menu(this, "同目录视频（OK 切换 · BACK 关闭）", items, which -> {
            if (which != curIdx) switchTo(which);
        });
    }

    /** 切到同目录另一个文件：重新注册流、换 MediaItem 续播。 */
    private void switchTo(final int idx) {
        final String smb0 = listUrls[idx], name0 = listNames[idx], mime0 = Smb.mime(name0);
        showHud("⏳ " + name0, false);
        new Thread(() -> {
            String http = StreamService.registerAndStream(getApplicationContext(), cred, smb0, mime0);
            if (http == null) {
                runOnUiThread(() -> Ui.toast(this, "启动流服务失败"));
                return;
            }
            ui.post(() -> {
                if (player == null) return;
                curIdx = idx;
                url = http;
                smbUrl = smb0;
                mime = mime0;
                name = name0;
                errored = false;
                err.setVisibility(View.GONE);
                view.setVisibility(View.VISIBLE);
                ((TextView) findViewById(R.id.p_title)).setText(name);
                player.setMediaItem(MediaItem.fromUri(http));
                player.prepare();
                player.play();
                showHud("▶ " + name, true);
                android.util.Log.d("NN", "player switch: " + name + " url=" + http);
            });
        }, "nn-switch").start();
    }

    // ---- HUD / 生命周期 ----

    private void showHud(String text, boolean autoHide) {
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        if (hideHud != null) ui.removeCallbacks(hideHud);
        if (autoHide) {
            hideHud = () -> hud.setVisibility(View.GONE);
            ui.postDelayed(hideHud, 1200);
        }
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        if (s < 3600) return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // HOME/待机时 ACTION_UP 会派给别的窗口，onKeyUp 收不到——焦点一丢就撤连跳，
        // 否则视频会在后台自己一直快进
        if (!hasFocus) endHold();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 流服务 5 分钟空闲自杀（或进程被杀重建）后回来：旧 URL 的端口/token 已作废，
        // 重新注册并换新 URL 续播。服务还在时什么都不做（token 有效，不无谓重缓冲）。
        if (smbUrl == null || player == null || StreamService.serverRunning()) return;
        final long pos = player.getCurrentPosition();
        final boolean wasPlaying = player.isPlaying();
        new Thread(() -> {
            final String fresh = StreamService.registerAndStream(getApplicationContext(), cred, smbUrl, mime);
            if (fresh == null) return; // 起不来就保持现状：出错页按 OK 走系统播放器兜底
            ui.post(() -> {
                if (player == null) return;
                url = fresh;
                errored = false;
                err.setVisibility(View.GONE);
                view.setVisibility(View.VISIBLE);
                player.setMediaItem(MediaItem.fromUri(fresh));
                player.seekTo(pos); // prepare 前设好续播位置
                player.prepare();
                if (wasPlaying) player.play();
                android.util.Log.d("NN", "player revived: " + fresh);
            });
        }, "nn-revive").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        endHold();
        if (player != null && !errored) player.pause(); // 退后台静音；回前台按 OK 继续
        // 注意：这里不能 setPlayerActive(false)——见 onCreate 注释
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        StreamService.setPlayerActive(false);
        endHold();
        if (hideHud != null) ui.removeCallbacks(hideHud);
        if (player != null) {
            player.release();
            player = null;
            view.setPlayer(null);
        }
    }
}
