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

    // ---- 预览条 + 预取：目录内可预览文件的横向轮播，高亮跟随焦点，预取中心附近 ±2 的缩略图 ----
    private static final int PREFETCH_RADIUS = 2;
    private android.widget.HorizontalScrollView stripScroll;
    private android.widget.LinearLayout stripRow;
    private final java.util.List<Integer> stripIdx = new ArrayList<>(); // entries 里可预览文件的下标
    private int listPos;           // 列表当前焦点行
    private int stripPos = -1;     // stripIdx 中的高亮位置
    private boolean stripMode;     // ▸ 已进入预览条（按键接管）
    private int thumbEpoch;        // 预取任务代次：换焦点/换目录即作废旧任务
    private final java.util.concurrent.ExecutorService thumbExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "nn-thumb");
                t.setDaemon(true);
                return t;
            });
    private final android.util.LruCache<String, android.graphics.Bitmap> thumbCache =
            new android.util.LruCache<>(24);
    private final android.util.LruCache<String, Long> durCache = new android.util.LruCache<>(64);
    private String sortMode = "name_asc"; // name_asc/name_desc/time_asc/time_desc（time=SMB 创建时间）

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
        sortMode = getSharedPreferences("nn_ui", MODE_PRIVATE).getString("sort_mode", "name_asc");
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
        stripScroll = findViewById(R.id.pv_strip_scroll);
        stripRow = findViewById(R.id.pv_strip);
        // 焦点缩放 + 悬停预览二合一（Ui.scaleFocused 只做缩放，这里还要挂预览）
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View view, int pos, long id) {
                if (view != null) Ui.focusScale(view, 1.03f);
                onRowSelected(pos);
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
                    Smb.sort(entries, sortMode); // 按用户上次的排序方式
                    adapter.notifyDataSetChanged();
                    rebuildStrip();
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

    /** 焦点行变化的统一入口（ListView 回调与排序重定位共用）。 */
    private void onRowSelected(int pos) {
        listPos = pos;
        Smb.Entry e = pos >= 0 && pos < entries.size() ? entries.get(pos) : null;
        if (!stripMode && previewable(e)) {
            int j = stripIdx.indexOf(pos);
            if (j >= 0) {
                stripPos = j;
                stripApply(true);
                return;
            }
        }
        hoverPreview(e);
    }

    // ---- 排序（菜单键）：文件名 / 创建时间，升·降序，记忆在 nn_ui 里 ----

    private static final String[][] SORT_MODES = {
            {"name_asc", "名称 A→Z"},
            {"name_desc", "名称 Z→A"},
            {"time_asc", "创建时间 旧→新"},
            {"time_desc", "创建时间 新→旧"},
    };

    /** 时间排序时界面日期跟随排序键（创建时间），避免"按创建时间排但显示的是修改时间"的困惑。 */
    private long sortDate(Smb.Entry e) {
        return sortMode.startsWith("time") ? e.ctime : e.mtime;
    }

    private void openSortMenu() {
        String[] items = new String[SORT_MODES.length];
        for (int i = 0; i < SORT_MODES.length; i++) {
            items[i] = (SORT_MODES[i][0].equals(sortMode) ? "▶ " : "") + SORT_MODES[i][1];
        }
        Ui.menu(this, "排序方式（BACK 关闭）", items, which -> applySort(SORT_MODES[which][0]));
    }

    /** 重排当前目录：保持焦点在原条目上；若正处在预览条里则留在预览条。 */
    private void applySort(String mode) {
        if (entries.isEmpty()) return;
        sortMode = mode;
        getSharedPreferences("nn_ui", MODE_PRIVATE).edit().putString("sort_mode", mode).apply();
        Smb.Entry cur = listPos >= 0 && listPos < entries.size() ? entries.get(listPos) : null;
        boolean wasStrip = stripMode;
        Smb.sort(entries, mode);
        adapter.notifyDataSetChanged();
        rebuildStrip();
        int np = 0;
        if (cur != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).url.equals(cur.url)) {
                    np = i;
                    break;
                }
            }
        }
        listPos = np;
        list.setSelection(np);
        onRowSelected(np); // setSelection 位置没变时 ListView 可能不回调，主动刷一次
        if (wasStrip) {
            int j = stripIdx.indexOf(np);
            if (j >= 0) {
                stripMode = true;
                stripPos = j;
                stripApply(true);
            }
        }
        for (String[] m : SORT_MODES) {
            if (m[0].equals(mode)) {
                Ui.toast(this, "排序：" + m[1]);
                break;
            }
        }
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
        playInternal(e, 0);
    }

    /** startMs>0：从该位置起播（粗览片段「从此处播放」）。 */
    private void playInternal(Smb.Entry e, long startMs) {
        android.util.Log.d("NN", "playInternal: " + e.name + " url=" + e.url + " startMs=" + startMs);
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
                    this, http, entry.url, mime, entry.name, cred, fUrls, fNames, fIdx, startMs)));
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

    /** 焦点移到行上触发：不可预览行显示"按 ▸ 浏览"提示（预览条可用时），文件防抖 260ms 后后台加载。 */
    private void hoverPreview(Smb.Entry e) {
        pvHandler.removeCallbacks(pvLoad);
        pvGen++;
        if (e == null || e.dir || !previewable(e)) {
            if (stripIdx.isEmpty()) {
                pvCard.setVisibility(View.GONE);
                return;
            }
            // 行本身不可预览，但目录里有可预览文件 → 保留卡片供 ▸ 进入
            pvCard.setVisibility(View.VISIBLE);
            pvName.setText(e == null ? "" : e.name);
            pvMeta.setText("此行不可预览 · 按 ▸ 浏览可预览文件");
            pvBody.removeAllViews();
            TextView hint = new TextView(this);
            hint.setText("按 ▸ 键浏览预览");
            hint.setTextColor(getResources().getColor(R.color.text_dim));
            hint.setTextSize(14);
            pvBody.addView(hint, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        String mime = Smb.mime(e.name);
        boolean media = mime.startsWith("video/") || mime.startsWith("audio/");
        boolean img = isImgName(e.name);
        boolean txt = isTxtName(e.name);
        pvCard.setVisibility(View.VISIBLE);
        pvName.setText(e.name);
        pvMeta.setText(Ui.fmtSize(e.size) + " · " + Ui.fmtDate(sortDate(e)));
        pvBody.removeAllViews();
        TextView hint = new TextView(this);
        hint.setText("正在加载预览…");
        hint.setTextColor(getResources().getColor(R.color.text_dim));
        hint.setTextSize(14);
        pvBody.addView(hint, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        // 预取过的缩略图先垫上，秒开
        android.graphics.Bitmap tb = thumbCache.get(e.url);
        if (tb != null) {
            hint.setText("高清加载中…");
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(tb);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            pvBody.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }

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

    // ---- 预览条：目录内可预览文件的横向轮播 ----

    private static boolean previewable(Smb.Entry e) {
        if (e == null || e.dir) return false;
        String m = Smb.mime(e.name);
        return m.startsWith("video/") || m.startsWith("audio/")
                || isImgName(e.name) || isTxtName(e.name);
    }

    private static class CardHolder {
        android.widget.ImageView iv;
        android.widget.TextView ph;
        android.widget.TextView name;
    }

    /** 目录加载完成后重建预览条（可预览文件集合变了）。 */
    private void rebuildStrip() {
        thumbEpoch++;
        stripIdx.clear();
        for (int i = 0; i < entries.size(); i++) {
            if (previewable(entries.get(i))) stripIdx.add(i);
        }
        stripRow.removeAllViews();
        stripMode = false;
        stripPos = -1;
        for (int j = 0; j < stripIdx.size(); j++) stripRow.addView(makeCard(j));
        if (stripIdx.isEmpty()) pvCard.setVisibility(View.GONE);
    }

    private View makeCard(int j) {
        Smb.Entry e = entries.get(stripIdx.get(j));
        float den = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pd = (int) (5 * den);
        card.setPadding(pd, pd, pd, pd);
        CardHolder h = new CardHolder();
        android.widget.FrameLayout thumbBox = new android.widget.FrameLayout(this);
        thumbBox.setBackgroundColor(0xff10141c);
        h.iv = new android.widget.ImageView(this);
        h.iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        thumbBox.addView(h.iv, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        h.ph = new android.widget.TextView(this);
        h.ph.setText(Ui.fileIcon(e));
        h.ph.setTextSize(30);
        h.ph.setGravity(android.view.Gravity.CENTER);
        thumbBox.addView(h.ph, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(
                (int) (150 * den), (int) (84 * den));
        card.addView(thumbBox, blp);
        h.name = new android.widget.TextView(this);
        h.name.setText(e.name);
        h.name.setTextColor(getResources().getColor(R.color.text_dim));
        h.name.setTextSize(11);
        h.name.setSingleLine(true);
        h.name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        card.addView(h.name, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setTag(h);
        return card;
    }

    /** 高亮/文案/滚动/预取 一起刷。 */
    private void stripApply(boolean scroll) {
        for (int j = 0; j < stripRow.getChildCount(); j++) {
            CardHolder h = (CardHolder) stripRow.getChildAt(j).getTag();
            boolean cur = j == stripPos;
            stripRow.getChildAt(j).setBackgroundColor(cur ? 0x30e5b567 : 0x00000000);
            h.name.setTextColor(cur ? 0xffffffff : getResources().getColor(R.color.text_dim));
        }
        if (stripPos < 0 || stripPos >= stripIdx.size()) return;
        final Smb.Entry e = entries.get(stripIdx.get(stripPos));
        pvCard.setVisibility(View.VISIBLE);
        hoverPreview(e); // 先触发防抖加载（内部会把 meta 重写成 size·date，所以要后写 meta）
        pvName.setText(e.name);
        StringBuilder meta = new StringBuilder(Ui.fmtSize(e.size)).append(" · ").append(Ui.fmtDate(sortDate(e)));
        Long d = durCache.get(e.url);
        if (Smb.mime(e.name).startsWith("video/") && d != null && d > 0) {
            meta.append(" · 时长 ").append(fmtDur(d));
        }
        if (stripMode) meta.append(" · OK 粗览");
        pvMeta.setText(meta);
        if (scroll) scrollStripTo(stripPos);
        prefetchAround(stripPos);
    }

    private void scrollStripTo(final int j) {
        stripScroll.post(() -> {
            View c = stripRow.getChildAt(j);
            if (c == null || stripScroll.getWidth() == 0) return;
            int target = c.getLeft() - (stripScroll.getWidth() - c.getWidth()) / 2;
            stripScroll.smoothScrollTo(Math.max(0, target), 0);
        });
    }

    /** 列表按 ▸ 进入预览条：高亮落到离当前行最近的预览项。 */
    private void enterStrip() {
        if (stripIdx.isEmpty()) return;
        int best = 0, bd = Integer.MAX_VALUE;
        for (int j = 0; j < stripIdx.size(); j++) {
            int dd = Math.abs(stripIdx.get(j) - listPos);
            if (dd < bd) {
                bd = dd;
                best = j;
            }
        }
        stripMode = true;
        stripPos = best;
        stripApply(true);
    }

    private void exitStrip() {
        stripMode = false;
        int j = stripIdx.indexOf(listPos); // 高亮弹回列表焦点对应的卡片
        if (j >= 0) stripPos = j;
        stripApply(true);
    }

    private void stripMove(int d) {
        int n = stripPos + d;
        if (n < 0) {
            exitStrip(); // 最左再 ◂ = 回列表
            return;
        }
        if (n >= stripIdx.size()) return;
        stripPos = n;
        stripApply(true);
    }

    // ---- 缩略图预取：单线程队列，焦点附近 ±2 最近优先 ----

    private void prefetchAround(int c) {
        final int epoch = ++thumbEpoch;
        int[] order = {c, c + 1, c - 1, c + PREFETCH_RADIUS, c - PREFETCH_RADIUS};
        for (final int j : order) {
            if (j < 0 || j >= stripIdx.size()) continue;
            final Smb.Entry e = entries.get(stripIdx.get(j));
            if (thumbCache.get(e.url) != null) continue;
            thumbExec.execute(() -> {
                if (epoch != thumbEpoch || isFinishing()) return;
                final android.graphics.Bitmap b = makeThumb(e);
                if (b == null) return;
                runOnUiThread(() -> {
                    if (epoch != thumbEpoch || isFinishing()) return;
                    View card = stripRow.getChildAt(j);
                    if (card == null) return;
                    CardHolder h = (CardHolder) card.getTag();
                    h.iv.setImageBitmap(b);
                    h.ph.setVisibility(View.GONE);
                });
            });
        }
    }

    /** 低分辨率缩略图：图片降采样；视频 MMR 抓单帧；文本/音频给占位符。 */
    private android.graphics.Bitmap makeThumb(Smb.Entry e) {
        android.graphics.Bitmap b = thumbCache.get(e.url);
        if (b != null) return b;
        try {
            if (isImgName(e.name)) {
                b = decodeSampled(e.url, 480);
            } else if (Smb.mime(e.name).startsWith("video/")) {
                String http = StreamService.registerAndStream(getApplicationContext(), cred, e.url, Smb.mime(e.name));
                if (http == null) return null;
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                try {
                    mmr.setDataSource(http, new java.util.HashMap<String, String>());
                    long d = durOf(mmr, e.url);
                    long atMs = d > 0 ? Math.min(10_000, d / 2) : 0;
                    b = frameAt(mmr, atMs, 480);
                } finally {
                    try {
                        mmr.release();
                    } catch (Exception ignored) {
                    }
                }
            } else {
                return null;
            }
            if (b != null) thumbCache.put(e.url, b);
            return b;
        } catch (Throwable t) {
            android.util.Log.d("NN", "thumb fail " + e.name + ": " + t);
            return null;
        }
    }

    private long durOf(android.media.MediaMetadataRetriever mmr, String url) {
        Long c = durCache.get(url);
        if (c != null) return c;
        long d = 0;
        try {
            d = Long.parseLong(mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception ignored) {
        }
        durCache.put(url, d);
        return d;
    }

    /** 按目标宽抓帧；API<27 无 getScaledFrameAtTime，退回全尺寸再缩。 */
    private android.graphics.Bitmap frameAt(android.media.MediaMetadataRetriever mmr,
                                            long atMs, int targetW) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            android.graphics.Bitmap b = mmr.getScaledFrameAtTime(atMs * 1000,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW, targetW * 9 / 16);
            if (b != null) return b;
        }
        android.graphics.Bitmap full = mmr.getFrameAtTime(atMs * 1000,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (full == null || full.getWidth() <= targetW || full.getWidth() == 0) return full;
        int h = Math.max(1, full.getHeight() * targetW / full.getWidth());
        android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(full, targetW, h, true);
        if (s != full) full.recycle();
        return s;
    }

    // ---- 视频粗览：随机低分辨率抓几个片段，◂▸ 浏览，OK 从该处播放 ----

    private static final int ROUGH_FRAMES = 5;

    private void roughVideoPreview(final Smb.Entry e) {
        android.util.Log.d("NN", "rough preview: " + e.name);
        final android.view.ViewGroup content =
                (android.view.ViewGroup) findViewById(android.R.id.content);
        final android.widget.FrameLayout overlay = new android.widget.FrameLayout(this);
        overlay.setBackgroundColor(0xE6000000);
        overlay.setFocusable(true);

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
        final TextView meta = new TextView(this);
        meta.setText(Ui.fmtSize(e.size) + " · 正在探测时长…");
        meta.setTextColor(getResources().getColor(R.color.text_dim));
        meta.setTextSize(14);
        meta.setPadding(0, (int) (4 * den), 0, (int) (12 * den));
        panel.addView(meta);

        final android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        panel.addView(body, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView loading = new TextView(this);
        loading.setText("正在抓取片段 1/" + ROUGH_FRAMES + " …");
        loading.setTextColor(getResources().getColor(R.color.text_dim));
        loading.setTextSize(18);
        loading.setGravity(android.view.Gravity.CENTER);
        body.addView(loading, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        final android.widget.ImageView big = new android.widget.ImageView(this);
        big.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        big.setVisibility(View.GONE);
        body.addView(big, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView badge = new TextView(this);
        badge.setBackgroundColor(0x99000000);
        badge.setTextColor(0xffffffff);
        badge.setTextSize(13);
        badge.setPadding((int) (10 * den), (int) (2 * den), (int) (10 * den), (int) (2 * den));
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.END);
        blp.setMargins(0, 0, (int) (10 * den), (int) (10 * den));
        badge.setVisibility(View.GONE);
        body.addView(badge, blp);

        // 片段轨道
        android.widget.HorizontalScrollView railScroll = new android.widget.HorizontalScrollView(this);
        railScroll.setHorizontalScrollBarEnabled(false);
        railScroll.setFillViewport(true);
        android.widget.LinearLayout railRow = new android.widget.LinearLayout(this);
        railRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        railScroll.addView(railRow, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        panel.addView(railScroll, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) (104 * den)));

        final android.graphics.Bitmap[] frames = new android.graphics.Bitmap[ROUGH_FRAMES];
        final long[] posMs = new long[ROUGH_FRAMES];
        final android.widget.ImageView[] railIv = new android.widget.ImageView[ROUGH_FRAMES];
        final android.widget.TextView[] railTs = new android.widget.TextView[ROUGH_FRAMES];
        final int[] cur = {0};

        content.addView(overlay, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));

        final Runnable showCur = new Runnable() {
            @Override
            public void run() {
                int i = cur[0];
                for (int j = 0; j < ROUGH_FRAMES && j < railRow.getChildCount(); j++) {
                    railRow.getChildAt(j).setBackgroundColor(j == i ? 0x30e5b567 : 0x00000000);
                }
                railScroll.post(() -> {
                    if (i < railRow.getChildCount()) {
                        View c = railRow.getChildAt(i);
                        railScroll.smoothScrollTo(
                                Math.max(0, c.getLeft() - (railScroll.getWidth() - c.getWidth()) / 2), 0);
                    }
                });
                if (frames[i] != null) {
                    big.setImageBitmap(frames[i]);
                    big.setVisibility(View.VISIBLE);
                    loading.setVisibility(View.GONE);
                    badge.setText(fmtDur(posMs[i]));
                    badge.setVisibility(View.VISIBLE);
                } else {
                    loading.setText("正在抓取片段 " + (i + 1) + "/" + ROUGH_FRAMES + " …");
                    loading.setVisibility(View.VISIBLE);
                    badge.setVisibility(View.GONE);
                }
            }
        };

        for (int i = 0; i < ROUGH_FRAMES; i++) {
            android.widget.LinearLayout card = new android.widget.LinearLayout(this);
            card.setOrientation(android.widget.LinearLayout.VERTICAL);
            int cp = (int) (4 * den);
            card.setPadding(cp, cp, cp, cp);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xff10141c);
            railIv[i] = iv;
            card.addView(iv, new android.widget.LinearLayout.LayoutParams(
                    (int) (150 * den), (int) (74 * den)));
            TextView ts = new TextView(this);
            ts.setText("…");
            ts.setTextColor(getResources().getColor(R.color.text_dim));
            ts.setTextSize(11);
            ts.setGravity(android.view.Gravity.CENTER);
            card.addView(ts, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            railTs[i] = ts;
            final int idx = i;
            card.setOnClickListener(v -> { // 触屏/模拟器点击片段也能跳
                cur[0] = idx;
                showCur.run();
            });
            railRow.addView(card);
        }

        overlay.setOnKeyListener((v, k, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
            switch (k) {
                case android.view.KeyEvent.KEYCODE_BACK:
                    content.removeView(overlay);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                    if (cur[0] > 0) {
                        cur[0]--;
                        showCur.run();
                    }
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    if (cur[0] < ROUGH_FRAMES - 1) {
                        cur[0]++;
                        showCur.run();
                    }
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    if (frames[cur[0]] != null) {
                        content.removeView(overlay);
                        playInternal(e, posMs[cur[0]]); // 从该片段的时间点起播
                    }
                    return true;
                default:
                    return true;
            }
        });
        overlay.requestFocus();

        // 后台：一次 MMR 会话顺序抓 N 帧，渐进上屏
        final int gen = pvGen;
        new Thread(() -> {
            final java.util.HashMap<String, String> noHdr = new java.util.HashMap<>();
            try {
                String http = StreamService.registerAndStream(getApplicationContext(), cred, e.url, Smb.mime(e.name));
                if (http == null) throw new IllegalStateException("流服务未启动");
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                try {
                    mmr.setDataSource(http, noHdr);
                    final long dur = durOf(mmr, e.url);
                    if (dur <= 0) {
                        frames[0] = frameAt(mmr, 0, 900);
                        posMs[0] = 0;
                        runOnUiThread(() -> {
                            if (overlay.getWindowToken() == null) return;
                            meta.setText(Ui.fmtSize(e.size) + " · 未读到时长");
                            railIv[0].setImageBitmap(frames[0]);
                            railIv[0].setVisibility(frames[0] == null ? View.INVISIBLE : View.VISIBLE);
                            railTs[0].setText(frames[0] == null ? "–" : "0:00");
                            showCur.run();
                        });
                        return;
                    }
                    // 分层随机：每段内随机取一点 → 每次打开看到的片段都不同
                    java.util.Random rnd = new java.util.Random();
                    for (int i = 0; i < ROUGH_FRAMES; i++) {
                        posMs[i] = (long) (dur * (0.04 + 0.92 * (i + rnd.nextDouble()) / ROUGH_FRAMES));
                    }
                    runOnUiThread(() -> {
                        if (overlay.getWindowToken() == null) return;
                        meta.setText(Ui.fmtSize(e.size) + " · 时长 " + fmtDur(dur)
                                + " · ◂▸ 换片段 · OK 从此播放 · BACK 返回");
                    });
                    for (int i = 0; i < ROUGH_FRAMES; i++) {
                        if (overlay.getWindowToken() == null || gen != pvGen || isFinishing()) return;
                        try {
                            frames[i] = frameAt(mmr, posMs[i], 900);
                        } catch (Throwable tex) {
                            android.util.Log.d("NN", "rough frame " + i + " fail: " + tex);
                            frames[i] = null;
                        }
                        final int fi = i;
                        runOnUiThread(() -> {
                            if (overlay.getWindowToken() == null) return;
                            railIv[fi].setImageBitmap(frames[fi]);
                            if (frames[fi] == null) railIv[fi].setVisibility(View.INVISIBLE);
                            railTs[fi].setText(frames[fi] == null ? "–" : fmtDur(posMs[fi]));
                            if (cur[0] == fi) showCur.run();
                        });
                    }
                    int got = 0;
                    for (android.graphics.Bitmap b : frames) if (b != null) got++;
                    if (got == 0) {
                        runOnUiThread(() -> {
                            if (overlay.getWindowToken() == null) return;
                            loading.setText("🎵 抓不到画面（可能是纯音频或封装异常）");
                            loading.setVisibility(View.VISIBLE);
                            big.setVisibility(View.GONE);
                        });
                    }
                } finally {
                    try {
                        mmr.release();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable ex) {
                android.util.Log.d("NN", "rough preview fail: " + ex);
                runOnUiThread(() -> {
                    if (overlay.getWindowToken() == null) return;
                    loading.setText("预览失败：" + ex.getMessage());
                    loading.setVisibility(View.VISIBLE);
                });
            }
        }, "nn-rough").start();
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

    /** 菜单「预览」：视频走粗览（随机片段浏览）；图片看图、音频抓帧+时长、字幕/文本看内容。 */
    private void preview(Smb.Entry e) {
        // 视频：粗览比单帧有用——◂▸ 翻随机片段、OK 从该处起播
        if (Smb.mime(e.name).startsWith("video/")) {
            roughVideoPreview(e);
            return;
        }
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

    /**
     * 预览条按键接管（dispatchKeyEvent 先于 ListView 消费）：
     * - 列表态按 ◂/▸：目录里有可预览文件就进入预览条（高亮落到离焦点最近的项）；
     * - 预览条态：◂▸ 翻卡片、OK 粗览/看图、BACK 回列表、↑↓ 回列表并放行给 ListView 移焦点。
     * 菜单/预览覆盖层打开时（content 多了子 View）完全不插手。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        android.view.ViewGroup content = (android.view.ViewGroup) findViewById(android.R.id.content);
        android.util.Log.d("NN", "dispatch code=" + event.getKeyCode()
                + " act=" + event.getAction() + " kids="
                + (content == null ? -1 : content.getChildCount())
                + " strip=" + stripMode + " n=" + stripIdx.size());
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        // 菜单/预览覆盖层打开时（content 多了子 View）完全不插手，事件交给聚焦的覆盖层自己处理
        if (content == null || content.getChildCount() > 1) return super.dispatchKeyEvent(event);
        // 菜单键：排序方式（列表态/预览条态都可用，覆盖层打开时走上面守卫交给覆盖层）
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            openSortMenu();
            return true;
        }
        if (!stripMode) {
            if (stripIdx.isEmpty()) return super.dispatchKeyEvent(event);
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT
                    || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                enterStrip();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                stripMove(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                stripMove(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: {
                if (stripPos < 0 || stripPos >= stripIdx.size()) return true;
                Smb.Entry e = entries.get(stripIdx.get(stripPos));
                String mime = Smb.mime(e.name);
                if (mime.startsWith("video/")) roughVideoPreview(e);
                else preview(e); // 图片/音频/文本沿用各自预览
                return true;
            }
            case KeyEvent.KEYCODE_BACK:
                exitStrip();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                exitStrip();
                return false; // 放行给 ListView 正常移焦点
            default:
                return true; // 预览条态吃掉其余按键，防列表乱跳
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
