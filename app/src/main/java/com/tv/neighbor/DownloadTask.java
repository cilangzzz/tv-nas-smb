package com.tv.neighbor;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 把 SMB 文件复制到 Download/网络邻居/，带进度对话框。 */
public final class DownloadTask {
    private DownloadTask() {}

    /** 无写权限时返回 false（调用方应先申请运行时权限；API<23 安装时已授予）。 */
    public static boolean hasStoragePerm(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    public static void run(Activity a, Cred cred, Smb.Entry e) {
        if (!hasStoragePerm(a)) return;
        new Task(a, cred, e).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static class Task extends AsyncTask<Void, Long, String> {
        private final Activity act;
        private final Cred cred;
        private final Smb.Entry e;
        private ProgressDialog pd;

        Task(Activity a, Cred cred, Smb.Entry e) {
            this.act = a;
            this.cred = cred;
            this.e = e;
        }

        @Override
        protected void onPreExecute() {
            pd = new ProgressDialog(act);
            pd.setTitle(e.name);
            pd.setMessage(act.getString(R.string.loading));
            pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            pd.setMax(100);
            pd.setCancelable(true);
            pd.show();
        }

        @Override
        protected String doInBackground(Void... voids) {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File out = new File(new File(dir, "网络邻居"), e.name);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream in = null;
            OutputStream os = null;
            long total = e.size;
            try {
                in = Smb.file(cred, e.url).openInputStream();
                os = new FileOutputStream(out);
                byte[] buf = new byte[128 * 1024];
                long done = 0;
                int lastPct = -1;
                int r;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                    done += r;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            publishProgress((long) pct);
                        }
                    }
                }
                return out.getAbsolutePath();
            } catch (Exception ex) {
                try { out.delete(); } catch (Exception ignored) {}
                return "!" + ex.toString();
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (os != null) os.close(); } catch (Exception ignored) {}
            }
        }

        @Override
        protected void onProgressUpdate(Long... values) {
            if (pd != null) pd.setProgress(values[0].intValue());
        }

        @Override
        protected void onPostExecute(String result) {
            try { pd.dismiss(); } catch (Exception ignored) {}
            if (result.startsWith("!")) {
                Ui.toast(act, act.getString(R.string.download_fail, result.substring(1)));
            } else {
                Ui.toast(act, act.getString(R.string.download_to, result));
            }
        }
    }
}
