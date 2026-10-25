package com.tv.neighbor;

import android.app.Activity;
import android.content.Context;
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
import java.util.List;

/** 一台主机的共享列表（复用 activity_list 布局）。 */
public class ShareListActivity extends Activity {
    private TextView title, subtitle, empty;
    private ProgressBar loading;
    private ListView list;
    private final List<Smb.Entry> entries = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private Cred cred;
    private String host;

    public static Intent intent(Context c, String host) {
        Intent i = new Intent(c, ShareListActivity.class);
        i.putExtra("host", host);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        host = getIntent().getStringExtra("host");
        cred = HostStore.get(this, host);
        title = findViewById(R.id.title);
        subtitle = findViewById(R.id.subtitle);
        empty = findViewById(R.id.empty);
        loading = findViewById(R.id.loading);
        list = findViewById(android.R.id.list);
        list.setAdapter(adapter);
        Ui.scaleFocused(list, 1.03f);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Smb.Entry e = entries.get(pos);
            if (e.dir) startActivity(BrowseActivity.intent(this, host, e.url, e.name));
        });
        title.setText(host);
        subtitle.setText(getString(R.string.share));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        loading.setVisibility(View.VISIBLE);
        Ui.setListEmpty(list, empty, false);
        new Thread(() -> {
            List<Smb.Entry> got = null;
            String err = null;
            boolean authErr = false;
            try {
                got = Smb.listShares(cred, host);
            } catch (Throwable t) {
                err = t.toString();
                authErr = t instanceof jcifs.smb.SmbAuthException;
            }
            final List<Smb.Entry> fGot = got;
            final String fErr = err;
            final boolean fAuth = authErr;
            runOnUiThread(() -> {
                loading.setVisibility(View.INVISIBLE);
                if (fGot != null) {
                    entries.clear();
                    entries.addAll(fGot);
                    adapter.notifyDataSetChanged();
                    Ui.setListEmpty(list, empty, entries.isEmpty());
                    empty.setText(R.string.empty_shares);
                } else {
                    entries.clear();
                    adapter.notifyDataSetChanged();
                    Ui.setListEmpty(list, empty, true);
                    if (fAuth) {
                        askLogin();
                    } else {
                        empty.setText(getString(R.string.net_error, fErr == null ? "?" : shortErr(fErr)) + "\n\n⟳ 按 OK 重试");
                        empty.setOnClickListener(v -> load());
                        empty.setFocusable(true);
                        empty.requestFocus();
                    }
                }
            });
        }, "smb-shares").start();
    }

    private static String shortErr(String s) {
        // jcifs 异常 toString 很长，取最后的异常类名+消息
        int i = s.lastIndexOf(": ");
        return i > 0 ? s.substring(i + 2) : s;
    }

    private void askLogin() {
        Ui.hostDialog(this, cred, true, c -> {
            cred = c;
            HostStore.put(this, c);
            load();
        });
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
                v = LayoutInflater.from(ShareListActivity.this).inflate(R.layout.item_entry, parent, false);
            }
            ((TextView) v.findViewById(R.id.icon)).setText("📁");
            ((TextView) v.findViewById(R.id.name)).setText(e.name);
            ((TextView) v.findViewById(R.id.meta)).setText(getString(R.string.share));
            Ui.focusScale(v, 1.02f);
            return v;
        }
    }
}
