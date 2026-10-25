package com.tv.neighbor;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 主页：收藏 + 局域网扫描结果 + 操作入口。
 * 扫描方式：/24 段 TCP 445 并发连接探测（不依赖 SMB1 的“网上邻居”浏览），
 * 命中的 IP 再用 NBNS Node Status 反查机器名。
 */
public class MainActivity extends Activity {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DEVICE = 1;
    private static final int TYPE_ACTION = 2;

    private ListView list;
    private TextView subtitle;
    private TextView empty;
    private ProgressBar bar;
    private final SmbScanner scanner = new SmbScanner();
    private final List<Object> rows = new ArrayList<>();
    private final List<Cred> favs = new ArrayList<>();
    private final List<Device> foundList = new ArrayList<>();
    private final List<Device> known = new ArrayList<>(); // 已展示的扫描结果
    private final Adapter adapter = new Adapter();
    private boolean scanDone;
    private String prefix;
    private int lastProgress;

    private static class Device {
        String ip;
        String name;
        String smbVer;
    }

    private static class Header {
        final String label;

        Header(String l) {
            label = l;
        }
    }

    private static class Action {
        final String label;

        Action(String l) {
            label = l;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        list = findViewById(android.R.id.list);
        subtitle = findViewById(R.id.subtitle);
        empty = findViewById(R.id.empty);
        bar = findViewById(R.id.scan_progress);
        list.setAdapter(adapter);
        Ui.scaleFocused(list, 1.03f);
        list.setOnItemClickListener((p, v, pos, id) -> onRow(rows.get(pos)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
        startScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面离开就停扫，避免后台耗电
        scanner.cancel();
    }

    private void rebuild() {
        rows.clear();
        favs.clear();
        favs.addAll(HostStore.favorites(this));
        if (!favs.isEmpty()) {
            rows.add(new Header(getString(R.string.section_fav)));
            rows.addAll(favs);
        }
        if (!known.isEmpty()) {
            rows.add(new Header(getString(R.string.section_found)));
            rows.addAll(known);
        }
        rows.add(new Header(getString(R.string.section_actions)));
        rows.add(new Action(getString(R.string.action_add)));
        rows.add(new Action(getString(R.string.action_rescan)));
        adapter.notifyDataSetChanged();
        if (rows.size() <= 3) {
            subtitle.setText(R.string.subtitle_idle);
        }
        boolean showEmpty = scanDone && favs.isEmpty() && known.isEmpty();
        Ui.setListEmpty(list, empty, showEmpty);
    }

    private void startScan() {
        if (scanner.running()) return;
        scanDone = false;
        lastProgress = 0;
        known.clear();
        foundList.clear();
        prefix = SmbScanner.localPrefix();
        if (prefix == null) {
            subtitle.setText(R.string.subtitle_no_network);
            bar.setVisibility(View.INVISIBLE);
            rebuild();
            return;
        }
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        subtitle.setText(getString(R.string.subtitle_scanning, prefix + ".0/24", 0, 254));
        scanner.start(prefix, new SmbScanner.Listener() {
            @Override
            public void onFound(String ip, String name, String smbVer) {
                Device d = new Device();
                d.ip = ip;
                d.name = name != null ? name : ip;
                d.smbVer = smbVer;
                foundList.add(d);
                known.add(d);
                Collections.sort(known, (a, b) -> a.ip.compareTo(b.ip));
                rebuild();
            }

            @Override
            public void onProgress(int done, int total) {
                if (done - lastProgress >= 8 || done == total) {
                    lastProgress = done;
                    subtitle.setText(getString(R.string.subtitle_scanning, prefix + ".0/24", done, total));
                    bar.setProgress(done * 100 / total);
                }
            }

            @Override
            public void onDone(int found) {
                scanDone = true;
                bar.setVisibility(View.INVISIBLE);
                subtitle.setText(getString(R.string.subtitle_scan_done, found));
            }

            @Override
            public void onError(String msg) {
                bar.setVisibility(View.INVISIBLE);
                subtitle.setText(getString(R.string.scan_fail, msg));
            }
        });
    }

    private void onRow(Object row) {
        if (row instanceof Device) {
            Device d = (Device) row;
            Cred cred = HostStore.get(this, d.ip);
            if (cred.name.equals(cred.host)) cred.name = d.name; // 顺手把显示名补上
            Ui.menu(this, d.name, new String[]{getString(R.string.open),
                            cred.favorite ? getString(R.string.edit_cred) : getString(R.string.fav_add),
                            cred.favorite ? getString(R.string.forget) : "—"},
                    which -> {
                        if (which == 0) {
                            HostStore.put(this, cred);
                            startActivity(ShareListActivity.intent(this, d.ip));
                        } else if (which == 1) {
                            if (cred.favorite) {
                                Ui.hostDialog(this, cred, true, c -> {
                                    HostStore.put(this, c);
                                    rebuild();
                                });
                            } else {
                                cred.favorite = true;
                                HostStore.put(this, cred);
                                rebuild();
                            }
                        } else if (which == 2 && cred.favorite) {
                            cred.favorite = false;
                            HostStore.put(this, cred);
                            rebuild();
                        }
                    });
        } else if (row instanceof Action) {
            Action a = (Action) row;
            if (a.label.equals(getString(R.string.action_add))) {
                Ui.hostDialog(this, null, false, c -> {
                    if (c.host.isEmpty()) return;
                    c.favorite = true;
                    HostStore.put(this, c);
                    rebuild();
                });
            } else {
                scanner.cancel();
                startScan();
            }
        } else if (row instanceof Cred) {
            Cred c = (Cred) row;
            Ui.menu(this, c.name, new String[]{getString(R.string.open), getString(R.string.edit_cred),
                            getString(R.string.forget)},
                    which -> {
                        if (which == 0) {
                            startActivity(ShareListActivity.intent(this, c.host));
                        } else if (which == 1) {
                            Ui.hostDialog(this, c, true, x -> {
                                HostStore.put(this, x);
                                rebuild();
                            });
                        } else if (which == 2) {
                            c.favorite = false;
                            HostStore.put(this, c);
                            rebuild();
                        }
                    });
        }
    }

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            Object r = rows.get(position);
            if (r instanceof Header) return TYPE_HEADER;
            if (r instanceof Action) return TYPE_ACTION;
            return TYPE_DEVICE;
        }

        @Override
        public boolean isEnabled(int position) {
            return !(rows.get(position) instanceof Header);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object r = rows.get(position);
            int type = getItemViewType(position);
            View v = convertView;
            if (type == TYPE_HEADER) {
                if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_header, parent, false);
                ((TextView) v).setText(((Header) r).label);
                return v;
            }
            if (type == TYPE_ACTION) {
                if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_action, parent, false);
                ((TextView) v).setText(((Action) r).label);
                Ui.focusScale(v, 1.02f);
                return v;
            }
            if (r instanceof Cred) { // 收藏区行：对象本来就是 Cred，直接渲染
                Cred c = (Cred) r;
                if (v == null || v.findViewById(R.id.state) == null || !(v.getTag() instanceof Boolean)) {
                    v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_device, parent, false);
                    v.setTag(Boolean.TRUE);
                }
                ((TextView) v.findViewById(R.id.name)).setText("★ " + c.name);
                ((TextView) v.findViewById(R.id.sub)).setText(c.host);
                ((TextView) v.findViewById(R.id.state)).setText("");
                Ui.focusScale(v, 1.02f);
                return v;
            }
            Device d = (Device) r;
            if (v == null || v.findViewById(R.id.state) == null || !(v.getTag() instanceof Boolean)) {
                v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_device, parent, false);
                v.setTag(Boolean.TRUE);
            }
            boolean isFav = false;
            for (Cred c : favs) {
                if (c.host.equals(d.ip) || c.host.equals(d.name)) {
                    isFav = true;
                    break;
                }
            }
            ((TextView) v.findViewById(R.id.name)).setText(isFav ? favName(d) : d.name);
            ((TextView) v.findViewById(R.id.sub)).setText(d.ip
                    + (d.smbVer != null ? " · " + d.smbVer : " · " + getString(R.string.smb_host)));
            ((TextView) v.findViewById(R.id.state)).setText("");
            Ui.focusScale(v, 1.02f);
            return v;
        }

        private String favName(Device d) {
            for (Cred c : favs) {
                if (c.host.equals(d.ip) || c.host.equals(d.name)) return "★ " + c.name;
            }
            return d.name;
        }
    }
}
