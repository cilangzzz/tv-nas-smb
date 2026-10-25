package com.tv.neighbor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 目录浏览：OK 进目录 / 弹文件菜单；BACK 返回上级。 */
public class BrowseActivity extends Activity {
    private TextView title, subtitle, empty;
    private ProgressBar loading;
    private ListView list;
    private final List<Smb.Entry> entries = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private Cred cred;
    private String host;
    private String dirUrl;
    private String label; // 当前目录显示名
    private Smb.Entry pendingDownload;

    // 悬停预览：焦点移到文件行自动加载（防抖 + 帧缓存），本机固件不能用 Dialog 但 activity 内视图没这问题
    private android.view.View pvCard;
    private android.view.ViewGroup pvBody;
    private TextView pvName, pvMeta;
    private final android.os.Handler pvHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pvLoad;
    private int pvGen;
    private final android.util.LruCache<String, android.graphics.Bitmap> pvCache =
            new android.util.LruCache<>(8);

    public static Intent intent(Context c, String host, String dirUrl, String label) {
        Intent i = new Intent(c, BrowseActivity.class);
        i.putExtra("host", host);
        i.putExtra("url", dirUrl);
        i.putExtra("label", label);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        host = getIntent().getStringExtra("host");
        dirUrl = getIntent().getStringExtra("url");
        label = getIntent().getStringExtra("label");
        cred = HostStore.get(this, host);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        empty = findViewById(R.id.empty);
        loading = findViewById(R.id.loading);
        list = findViewById(android.R.id.list);
        list.setAdapter(adapter);
        pvCard = findViewById(R.id.pv_card);
        pvName = findViewById(R.id.pv_name);
        pvMeta = findViewById(R.id.pv_meta);
        pvBody = (android.view.ViewGroup) findViewById(R.id.pv_body);
        // 焦点缩放 + 悬停预览二合一（Ui.scaleFocused 只做缩放，这里还要挂预览）
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View view, int pos, long id) {
                if (view != null) Ui.focusScale(view, 1.03f);
                hoverPreview(pos >= 0 && pos < entries.size() ? entries.get(pos) : null);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        list.setOnItemClickListener((p, v, pos, id) -> onEntry(entries.get(pos)));
        title.setText(label);
        subtitle.setText(dirUrl);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        Ui.setListEmpty(list, empty, false);
        pvGen++; // 目录重载：作废在途的悬停加载
        pvCard.setVisibility(View.GONE);
        new Thread(() -> {
            List<Smb.Entry> got = null;
            boolean authErr = false;
            String err = null;
            try {
                got = Smb.listDir(cred, dirUrl);
            } catch (Throwable t) {
                authErr = t instanceof jcifs.smb.SmbAuthException;
                err = t.toString();
            }
            final List<Smb.Entry> fGot = got;
            final boolean fAuth = authErr;
            final String fErr = err;
            runOnUiThread(() -> {
                loading.setVisibility(View.INVISIBLE);
                if (fGot != null) {
                    entries.clear();
                    entries.addAll(fGot);
                    adapter.notifyDataSetChanged();
                    Ui.setListEmpty(list, empty, entries.isEmpty());
                    empty.setText(R.string.empty_dir);
                } else if (fAuth) {
                    askLogin();
                } else {
                    entries.clear();
                    adapter.notifyDataSetChanged();
                    Ui.setListEmpty(list, empty, true);
                    empty.setText(getString(R.string.net_error, fErr == null ? "?" : fErr) + "\n\n⟳ 按 OK 重试");
                    empty.setOnClickListener(v -> load());
                    empty.setFocusable(true);
                    empty.requestFocus();
                }
            });
        }, "smb-dir").start();
    }

    private void askLogin() {
        Ui.hostDialog(this, cred, true, c -> {
            cred = c;
            HostStore.put(this, c);
            load();
        });
    }

    private void onEntry(Smb.Entry e) {
        android.util.Log.d("NN", "onEntry: " + e.name + " dir=" + e.dir);
        if (e.dir) {
            startActivity(BrowseActivity.intent(this, host, e.url, e.name));
            overridePendingTransition(0, 0);
            finish();
        } else {
            // 音视频用内置 ExoPlayer（长按快进/大缓冲）；其他类型只有系统播放器一条路
            String mime = Smb.mime(e.name);
            final boolean media = mime.startsWith("video/") || mime.startsWith("audio/");
            String[] opts = media
                    ? new String[]{getString(R.string.play_builtin), getString(R.string.play),
                        getString(R.string.preview), getString(R.string.copy_local)}
                    : new String[]{getString(R.string.preview), getString(R.string.play),
                        getString(R.string.copy_local)};
            final int iSys = 1, iPrev = media ? 2 : 0, iCopy = media ? 3 : 2;
            Ui.menu(this, e.name, opts, which -> {
                if (media && which == 0) playInternal(e);
                else if (which == iSys) playSystem(e);
                else if (which == iPrev) preview(e);
                else if (which == iCopy) {
                    if (DownloadTask.hasStoragePerm(this)) {
                        DownloadTask.run(this, cred, e);
                    } else if (android.os.Build.VERSION.SDK_INT >= 23) {
                        pendingDownload = e;
                        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                    } else {
                        Ui.toast(this, "没有存储权限");
                    }
                }
            });
        }
    }

    /** 内置 ExoPlayer：长按快进、预读缓冲、磁盘缓存、↑键同目录切换。 */
    private void playInternal(Smb.Entry e) {
        android.util.Log.d("NN", "playInternal: " + e.name + " url=" + e.url);
        // 在 UI 线程先把同目录媒体列表抄出来（entries 会被 reload 并发改）
        java.util.List<String> urls = new ArrayList<>(), names = new ArrayList<>();
        int idx = -1;
        for (Smb.Entry x : entries) {
            String m = Smb.mime(x.name);
            if (!x.dir && (m.startsWith("video/") || m.startsWith("audio/"))) {
                if (x.url.equals(e.url)) idx = urls.size();
                urls.add(x.url);
                names.add(x.name);
            }
        }
        final int fIdx = idx;
        final String[] fUrls = urls.toArray(new String[0]), fNames = names.toArray(new String[0]);
        Ui.toast(this, "正在启动流服务…");
        final Smb.Entry entry = e;
        new Thread(() -> {
            String mime = Smb.mime(entry.name);
            String http = StreamService.registerAndStream(getApplicationContext(), cred, entry.url, mime);
            android.util.Log.d("NN", "playInternal: stream=" + http);
            if (http == null) {
                runOnUiThread(() -> Ui.toast(this, "启动流服务失败"));
                return;
            }
            runOnUiThread(() -> startActivity(PlayerActivity.intent(
                    this, http, entry.url, mime, entry.name, cred, fUrls, fNames, fIdx)));
        }, "nn-play").start();
    }

    /** 系统播放器兜底（rmvb 等内置解不动的老格式走这里）。 */
    private void playSystem(Smb.Entry e) {
        android.util.Log.d("NN", "playSystem: " + e.name + " url=" + e.url);
        // 等流服务起在后台线程做（慢 TV 上 >3s，UI 线程等会 ANR）
        Ui.toast(this, "正在启动流服务…");
        final Smb.Entry entry = e;
        new Thread(() -> {
            String mime = Smb.mime(entry.name);
            String http = StreamService.registerAndStream(getApplicationContext(), cred, entry.url, mime);
            android.util.Log.d("NN", "playSystem: stream=" + http);
            if (http == null) {
                runOnUiThread(() -> Ui.toast(this, "启动流服务失败"));
                return;
            }
            final Exception[] err = {null};
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.parse(http), mime)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    android.util.Log.d("NN", "playSystem: intent fired " + mime);
                } catch (Exception ex) {
                    err[0] = ex;
                    android.util.Log.d("NN", "playSystem: no player " + ex);
                    Ui.toast(this, "没有找到可用的播放器：" + ex.getMessage());
                }
            });
        }, "nn-play").start();
    }

    // ---- 悬停预览 ----

    /** 焦点移到行上触发：目录/不支持的类型隐藏卡片，文件防抖 260ms 后后台加载。 */
    private void hoverPreview(Smb.Entry e) {
        pvHandler.removeCallbacks(pvLoad);
        pvGen++;
        if (e == null || e.dir) {
            pvCard.setVisibility(View.GONE);
            return;
        }
        String mime = Smb.mime(e.name);
        boolean media = mime.startsWith("video/") || mime.startsWith("audio/");
        boolean img = isImgName(e.name);
        boolean txt = isTxtName(e.name);
        if (!media && !img && !txt) {
            pvCard.setVisibility(View.GONE);
            return;
        }
        pvCard.setVisibility(View.VISIBLE);
        pvName.setText(e.name);
        pvMeta.setText(Ui.fmtSize(e.size) + " · " + Ui.fmtDate(e.mtime));
        pvBody.removeAllViews();
        TextView hint = new TextView(this);
        hint.setText("正在加载预览…");
        hint.setTextColor(getResources().getColor(R.color.text_dim));
        hint.setTextSize(14);
        pvBody.addView(hint, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        final int gen = pvGen;
        final Smb.Entry fe = e;
        pvLoad = () -> loadHover(gen, fe, img, txt, media);
        pvHandler.postDelayed(pvLoad, 260);
    }

    private void loadHover(int gen, Smb.Entry e, boolean img, boolean txt, boolean media) {
        new Thread(() -> {
            try {
                final android.view.View v;
                if (img) {
                    android.graphics.Bitmap b = pvCache.get(e.url);
                    if (b == null) {
                        b = decodeSampled(e.url, 900);
                        if (b != null) pvCache.put(e.url, b);
                    }
                    if (b == null) throw new IllegalStateException("图片解码失败");
                    android.widget.ImageView iv = new android.widget.ImageView(BrowseActivity.this);
                    iv.setImageBitmap(b);
                    iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    v = iv;
                } else if (txt) {
                    v = textPreviewView(e.url);
                } else {
                    v = mediaHoverView(e);
                }
                applyHover(gen, v, null);
            } catch (Throwable ex) {
                android.util.Log.d("NN", "hover preview fail: " + ex);
                applyHover(gen, null, "预览失败：" + ex.getMessage());
            }
        }, "nn-hover").start();
    }

    private void applyHover(int gen, android.view.View v, String error) {
        runOnUiThread(() -> {
            if (gen != pvGen || pvBody.getWindowToken() == null) return; // 已换行/换目录/关预览
            pvBody.removeAllViews();
            if (error != null) {
                TextView tv = new TextView(BrowseActivity.this);
                tv.setText(error);
                tv.setTextColor(0xffe5b567);
                tv.setTextSize(14);
                pvBody.addView(tv, new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                return;
            }
            pvBody.addView(v, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        });
    }

    /** 视频/音频悬停：回环流 + MMR 抓帧（带缓存，滚动回来不重抓）。 */
    private android.view.View mediaHoverView(Smb.Entry e) throws Exception {
        android.graphics.Bitmap fr = pvCache.get(e.url);
        String dur = null;
        if (fr == null) {
            String http = StreamService.registerAndStream(getApplicationContext(), cred, e.url, Smb.mime(e.name));
            if (http == null) throw new IllegalStateException("流服务未启动");
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            try {
                mmr.setDataSource(http, new java.util.HashMap<String, String>());
                dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                long atMs = 10_000;
                try {
                    long d = Long.parseLong(dur == null ? "0" : dur);
                    if (d > 0) atMs = Math.min(10_000, d / 2);
                } catch (Exception ignored) {
                }
                fr = mmr.getFrameAtTime(atMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } finally {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
            }
            if (fr != null) pvCache.put(e.url, fr);
        }
        final android.graphics.Bitmap fFr = fr;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        if (fFr != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(fFr);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            box.addView(iv, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            TextView note = new TextView(this);
            note.setText("🎵");
            note.setTextSize(46);
            note.setGravity(android.view.Gravity.CENTER);
            box.addView(note, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        if (dur != null) {
            TextView cap = new TextView(this);
            try {
                cap.setText("时长 " + fmtDur(Long.parseLong(dur)));
            } catch (Exception ignored) {
            }
            cap.setTextColor(getResources().getColor(R.color.text_dim));
            cap.setTextSize(12);
            cap.setGravity(android.view.Gravity.CENTER);
            box.addView(cap, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return box;
    }

    // ---- 文件预览（本机固件不能用 Dialog，全用 activity 覆盖层） ----

    private static String extOf(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(java.util.Locale.US);
    }

    private static boolean isImgName(String n) {
        String x = extOf(n);
        return x.equals("jpg") || x.equals("jpeg") || x.equals("png") || x.equals("gif")
                || x.equals("bmp") || x.equals("webp");
    }

    private static boolean isTxtName(String n) {
        String x = extOf(n);
        return x.equals("srt") || x.equals("ass") || x.equals("ssa") || x.equals("txt")
                || x.equals("log") || x.equals("nfo") || x.equals("ini");
    }

    /** 菜单「预览」：图片看图、音视频抓帧+时长、字幕/文本看内容。 */
    private void preview(Smb.Entry e) {
        android.util.Log.d("NN", "preview: " + e.name);
        final android.widget.FrameLayout content =
                (android.widget.FrameLayout) findViewById(android.R.id.content);
        final android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setBackgroundColor(0xE6000000);

        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setBackgroundColor(getResources().getColor(R.color.surface));
        float den = getResources().getDisplayMetrics().density;
        int m = (int) (90 * den);
        android.widget.FrameLayout.LayoutParams plp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        plp.setMargins(m, (int) (70 * den), m, (int) (70 * den));
        overlay.addView(panel, plp);
        int pd = (int) (30 * den);
        panel.setPadding(pd, (int) (24 * den), pd, (int) (24 * den));

        TextView t = new TextView(this);
        t.setText(e.name);
        t.setTextColor(getResources().getColor(R.color.text));
        t.setTextSize(20);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        panel.addView(t);
        TextView meta = new TextView(this);
        meta.setText(Ui.fmtSize(e.size) + " · " + Ui.fmtDate(e.mtime) + " · 按 BACK 返回");
        meta.setTextColor(getResources().getColor(R.color.text_dim));
        meta.setTextSize(14);
        meta.setPadding(0, (int) (4 * den), 0, (int) (12 * den));
        panel.addView(meta);

        final android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        panel.addView(body, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView loading = new TextView(this);
        loading.setText("正在加载预览…");
        loading.setTextColor(getResources().getColor(R.color.text_dim));
        loading.setTextSize(18);
        loading.setGravity(android.view.Gravity.CENTER);
        body.addView(loading, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(overlay, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setFocusable(true);
        overlay.setOnKeyListener((v, k, ev) -> {
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && k == android.view.KeyEvent.KEYCODE_BACK) {
                content.removeView(overlay);
                return true;
            }
            return false;
        });
        overlay.requestFocus();

        final boolean img = isImgName(e.name);
        final boolean txt = isTxtName(e.name);
        new Thread(() -> {
            try {
                final android.view.View v;
                if (img) v = imagePreviewView(e.url);
                else if (txt) v = textPreviewView(e.url);
                else v = mediaPreviewView(e);
                runOnUiThread(() -> {
                    if (body.getWindowToken() == null) return; // 用户已关掉预览
                    body.removeAllViews();
                    body.addView(v, new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                });
            } catch (Throwable ex) {
                android.util.Log.d("NN", "preview fail: " + ex);
                runOnUiThread(() -> {
                    if (body.getWindowToken() == null) return;
                    body.removeAllViews();
                    TextView err2 = new TextView(BrowseActivity.this);
                    err2.setText("预览失败：" + ex.getMessage());
                    err2.setTextColor(0xffe5b567);
                    err2.setTextSize(16);
                    err2.setGravity(android.view.Gravity.CENTER);
                    body.addView(err2, new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                });
            }
        }, "nn-preview").start();
    }

    private android.view.View imagePreviewView(String url) throws Exception {
        android.graphics.Bitmap bmp = decodeSampled(url, 1600);
        if (bmp == null) throw new IllegalStateException("图片解码失败");
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** 按目标宽度算 inSampleSize，两趟解码（先 bounds 后位图），避免大图 OOM。 */
    private android.graphics.Bitmap decodeSampled(String url, int targetW) throws Exception {
        jcifs.smb.SmbFile f = new jcifs.smb.SmbFile(url, Smb.ctx(cred));
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        java.io.InputStream in = f.openInputStream();
        try {
            android.graphics.BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
        o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = sample;
        in = f.openInputStream();
        try {
            return android.graphics.BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
    }

    private android.view.View textPreviewView(String url) throws Exception {
        jcifs.smb.SmbFile f = new jcifs.smb.SmbFile(url, Smb.ctx(cred));
        java.io.InputStream in = f.openInputStream();
        byte[] buf = new byte[24 * 1024];
        int n = 0, r;
        try {
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
        } finally {
            in.close();
        }
        String utf8 = new String(buf, 0, n, "UTF-8");
        String s = utf8;
        try { // 字幕常见 GBK：谁替换符少用谁
            String gbk = new String(buf, 0, n, "GBK");
            if (countRepl(gbk) < countRepl(utf8)) s = gbk;
        } catch (Exception ignored) {
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(s + (n >= buf.length ? "\n\n…（只加载前 24KB）" : ""));
        tv.setTextColor(getResources().getColor(R.color.text));
        tv.setTextSize(15);
        tv.setLineSpacing(0, 1.15f);
        sv.addView(tv, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private static int countRepl(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '�') c++;
        return c;
    }

    /** 音视频：注册回环流 → MediaMetadataRetriever 抓帧 + 时长。 */
    private android.view.View mediaPreviewView(Smb.Entry e) throws Exception {
        String http = StreamService.registerAndStream(getApplicationContext(), cred, e.url, Smb.mime(e.name));
        if (http == null) throw new IllegalStateException("流服务未启动");
        android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
        try {
            mmr.setDataSource(http, new java.util.HashMap<String, String>());
            String dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            long atMs = 10_000;
            try {
                long d = Long.parseLong(dur == null ? "0" : dur);
                if (d > 0) atMs = Math.min(10_000, d / 2);
            } catch (Exception ignored) {
            }
            final android.graphics.Bitmap fr = mmr.getFrameAtTime(atMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            float den = getResources().getDisplayMetrics().density;
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setGravity(android.view.Gravity.CENTER);
            if (fr != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageBitmap(fr);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                box.addView(iv, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            } else {
                TextView note = new TextView(this);
                note.setText("🎵 音频文件（无画面）");
                note.setTextColor(getResources().getColor(R.color.text_dim));
                note.setTextSize(40);
                note.setGravity(android.view.Gravity.CENTER);
                box.addView(note, new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            TextView cap = new TextView(this);
            String durTxt = "";
            try {
                durTxt = "时长 " + fmtDur(Long.parseLong(dur == null ? "0" : dur));
            } catch (Exception ignored) {
            }
            cap.setText(durTxt + (fr == null ? "" : " · 截自 " + (atMs / 1000) + " 秒处"));
            cap.setTextColor(getResources().getColor(R.color.text_dim));
            cap.setTextSize(14);
            cap.setGravity(android.view.Gravity.CENTER);
            cap.setPadding(0, (int) (10 * den), 0, 0);
            box.addView(cap, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            return box;
        } finally {
            try {
                mmr.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static String fmtDur(long ms) {
        long s = ms / 1000;
        if (s < 3600) return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && pendingDownload != null) {
            DownloadTask.run(this, cred, pendingDownload);
            pendingDownload = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            String parent = parentUrl(dirUrl);
            if (parent == null) {
                finish();
            } else {
                dirUrl = parent;
                label = labelOf(parent);
                title.setText(label);
                subtitle.setText(dirUrl);
                load();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** smb://host/share/a/b/ → smb://host/share/a/；到共享根（smb://host/share/）返回 null → 退回共享列表。 */
    private static String parentUrl(String url) {
        if (url == null) return null;
        String u = url.endsWith("/") ? url : url + "/";
        // 去掉尾斜杠后找上一个 '/'
        int end = u.length() - 1;
        int slash = u.lastIndexOf('/', end - 1);
        if (slash < 0) return null;
        String parent = u.substring(0, slash + 1);
        // smb://host/share/ 的 parent 是 smb://host/ —— 交给 ShareListActivity 处理
        if (parent.equals("smb://" + hostOf(u) + "/")) return null;
        return parent;
    }

    private static String hostOf(String url) {
        // smb://host/...
        String rest = url.substring("smb://".length());
        int i = rest.indexOf('/');
        return i < 0 ? rest : rest.substring(0, i);
    }

    private static String labelOf(String url) {
        // URL 是原样名字（jcifs 不做百分号解码），直接取最后一段显示
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int i = u.lastIndexOf('/');
        return i >= 0 ? u.substring(i + 1) : u;
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Smb.Entry e = entries.get(position);
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(BrowseActivity.this).inflate(R.layout.item_entry, parent, false);
            }
            ((TextView) v.findViewById(R.id.icon)).setText(Ui.fileIcon(e));
            ((TextView) v.findViewById(R.id.name)).setText(e.name);
            ((TextView) v.findViewById(R.id.meta)).setText(e.dir ? "" : Ui.fmtSize(e.size));
            Ui.focusScale(v, 1.02f);
            return v;
        }
    }
}
