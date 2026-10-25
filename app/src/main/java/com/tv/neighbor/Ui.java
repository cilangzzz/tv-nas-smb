package com.tv.neighbor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

/** TV 遥控器友好的 UI 小工具：焦点缩放、对话框、格式化。 */
public final class Ui {
    public interface Callback<T> {
        void run(T value);
    }

    private Ui() {}

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }

    /** 列表项获得焦点时轻微放大，TV 上更有“选中感”。 */
    public static void focusScale(View v, float scale) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                float s = has ? scale : 1f;
                view.animate().scaleX(s).scaleY(s).setDuration(110).start();
            }
        });
    }

    /** 给 ListView 每个可见子项挂焦点缩放（在 onItemSelected 里调用）。 */
    public static void scaleFocused(ListView lv, float scale) {
        lv.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (view != null) focusScale(view, scale);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    /**
     * 选项菜单（TV 适配）。不用 Dialog：这台 MTK 固件上 Dialog 窗口虽有输入焦点，
     * 但内部 ListView 拿不到视图焦点，D-pad 完全失灵（实测）。
     * 改为 activity 内容层上的覆盖面板——与浏览列表同一窗口同一焦点机制，必然可用。
     * 返回值是关闭面板的 Runnable（供外部在页面离开时清理）。
     */
    public static Runnable menu(Activity a, String title, String[] items, Callback<Integer> cb) {
        final android.widget.FrameLayout content =
                (android.widget.FrameLayout) a.findViewById(android.R.id.content);
        final android.widget.FrameLayout overlay = new android.widget.FrameLayout(a);
        overlay.setBackgroundColor(0xB3000000); // 半透明遮罩

        android.widget.LinearLayout panel = new android.widget.LinearLayout(a);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setBackgroundColor(a.getResources().getColor(R.color.surface));
        float den = a.getResources().getDisplayMetrics().density;
        android.widget.FrameLayout.LayoutParams plp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins((int) (70 * den), (int) (110 * den), (int) (70 * den), 0);
        overlay.addView(panel, plp);

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(a.getResources().getColor(R.color.text_dim));
        t.setTextSize(18);
        t.setSingleLine(true);
        t.setPadding((int) (28 * den), (int) (22 * den), (int) (28 * den), (int) (6 * den));
        panel.addView(t);

        ListView lv = new ListView(a);
        lv.setDivider(null);
        lv.setSelector(R.drawable.item_selector);
        lv.setAdapter(new android.widget.ArrayAdapter<String>(a, R.layout.item_menu, items));
        lv.setOnItemClickListener((p, v, pos, id) -> {
            content.removeView(overlay);
            cb.run(pos);
        });
        lv.setOnKeyListener((v, k, ev) -> {
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && k == android.view.KeyEvent.KEYCODE_BACK) {
                content.removeView(overlay);
                return true;
            }
            return false;
        });
        panel.addView(lv);

        content.addView(overlay, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        android.util.Log.d("NN", "menu overlay added: " + title);
        lv.post(() -> {
            boolean f = lv.requestFocus();
            lv.setSelection(0);
            android.util.Log.d("NN", "menu lv requestFocus=" + f + " hasFocus=" + lv.hasFocus());
        });
        return () -> content.removeView(overlay);
    }

    /** 添加/编辑主机凭据对话框。editing=false 时允许改 host。 */
    public static void hostDialog(Activity a, Cred init, boolean editing, Callback<Cred> onSave) {
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_host, null);
        EditText host = v.findViewById(R.id.f_host);
        EditText domain = v.findViewById(R.id.f_domain);
        EditText user = v.findViewById(R.id.f_user);
        EditText pass = v.findViewById(R.id.f_pass);
        CheckBox anon = v.findViewById(R.id.f_anon);
        CheckBox smb1 = v.findViewById(R.id.f_smb1);
        if (init != null) {
            host.setText(init.host);
            domain.setText(init.domain);
            user.setText(init.user);
            pass.setText(init.pass);
            anon.setChecked(init.anonymous);
            smb1.setChecked(init.allowSmb1);
            if (editing) host.setEnabled(false);
        }
        user.setEnabled(!anon.isChecked());
        pass.setEnabled(!anon.isChecked());
        anon.setOnCheckedChangeListener((b, checked) -> {
            user.setEnabled(!checked);
            pass.setEnabled(!checked);
        });
        new AlertDialog.Builder(a)
                .setTitle(editing ? R.string.edit_cred : R.string.title_add_host)
                .setView(v)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    Cred c = init == null ? new Cred() : init;
                    c.host = String.valueOf(host.getText()).trim().toLowerCase();
                    c.domain = String.valueOf(domain.getText()).trim();
                    c.user = String.valueOf(user.getText()).trim();
                    c.pass = String.valueOf(pass.getText()).toString();
                    c.anonymous = anon.isChecked();
                    c.allowSmb1 = smb1.isChecked();
                    if (c.name == null || c.name.isEmpty() || c.name.equals(c.host)) c.name = c.host;
                    onSave.run(c);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static String fmtSize(long n) {
        if (n < 0) return "";
        if (n < 1024) return n + " B";
        double v = n;
        for (String u : new String[]{"KB", "MB", "GB", "TB"}) {
            v /= 1024;
            if (v < 1024) return String.format(java.util.Locale.US, "%.1f %s", v, u);
        }
        return String.format(java.util.Locale.US, "%.1f PB", v / 1024);
    }

    public static String fmtDate(long t) {
        try {
            return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(t));
        } catch (Exception e) {
            return "";
        }
    }

    public static String fileIcon(Smb.Entry e) {
        if (e.dir) return "📁";
        String n = e.name.toLowerCase();
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        switch (ext) {
            case "mp4": case "mkv": case "avi": case "ts": case "m2ts": case "rmvb":
            case "flv": case "wmv": case "mov": case "3gp": case "m4v": case "iso":
                return "🎬";
            case "mp3": case "flac": case "ape": case "wav": case "m4a": case "ogg":
                return "🎵";
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "webp":
                return "🖼";
            case "zip": case "rar": case "7z": case "gz": case "tar":
                return "📦";
            case "srt": case "ass": case "ssa": case "txt":
                return "📄";
            default:
                return "📄";
        }
    }

    public static void setListEmpty(ListView list, View empty, boolean showEmpty) {
        empty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        list.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
    }
}
