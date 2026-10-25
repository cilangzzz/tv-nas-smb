package com.tv.neighbor;

import android.webkit.MimeTypeMap;

import java.util.ArrayList;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;

/** jcifs-ng 封装：上下文构建、共享/目录枚举、URL 编解码、MIME 推断。 */
public final class Smb {

    /** 共享或目录条目。 */
    public static class Entry {
        public final String name;   // 显示名（无尾斜杠）
        public final String url;    // 完整 smb:// URL（目录带尾斜杠）
        public final boolean dir;
        public final long size;
        public final long mtime;

        Entry(String name, String url, boolean dir, long size, long mtime) {
            this.name = name;
            this.url = url;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
        }
    }

    private static volatile CIFSContext base2; // 默认：SMB2.02+
    private static volatile CIFSContext base1; // 老设备：允许协商 SMB1

    private Smb() {}

    private static Properties props(boolean smb1) {
        Properties p = new Properties();
        if (smb1) {
            p.setProperty("jcifs.smb.client.minVersion", "SMB1");
            p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        } else {
            // 现代共享（Win10/11、NAS）基本都关了 SMB1；最小从 SMB2.02 起协商
            p.setProperty("jcifs.smb.client.minVersion", "SMB202");
            p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        }
        p.setProperty("jcifs.smb.client.responseTimeout", "20000");
        p.setProperty("jcifs.smb.client.connectionTimeout", "15000");
        p.setProperty("jcifs.smb.client.soTimeout", "60000");
        p.setProperty("jcifs.smb.client.sessionTimeout", "60000");
        p.setProperty("jcifs.resolveOrder", "BCAST,DNS");
        p.setProperty("jcifs.smb.client.port", "445");
        return p;
    }

    private static CIFSContext base(boolean smb1) {
        CIFSContext b = smb1 ? base1 : base2;
        if (b == null) {
            synchronized (Smb.class) {
                if (smb1 && base1 == null) {
                    try {
                        base1 = new BaseContext(new PropertyConfiguration(props(true)));
                    } catch (CIFSException e) {
                        throw new IllegalStateException(e);
                    }
                } else if (!smb1 && base2 == null) {
                    try {
                        base2 = new BaseContext(new PropertyConfiguration(props(false)));
                    } catch (CIFSException e) {
                        throw new IllegalStateException(e);
                    }
                }
                b = smb1 ? base1 : base2;
            }
        }
        return b;
    }

    /** 按凭据取上下文。 */
    public static CIFSContext ctx(Cred c) {
        boolean smb1 = c != null && c.allowSmb1;
        if (c == null || c.anonymous) {
            return base(smb1).withAnonymousCredentials();
        }
        return base(smb1).withCredentials(new NtlmPasswordAuthenticator(c.domain, c.user, c.pass));
    }

    public static SmbFile file(Cred c, String url) throws Exception {
        return new SmbFile(url, ctx(c));
    }

    /**
     * URL 段转义。jcifs-ng 对 smb: URL **不做任何百分号解码**（Handler.parseURL/canonicalizePath
     * 均无 unescape），percent-encode 过的名字会被原样发给服务器，导致
     * STATUS_BAD_NETWORK_NAME "The network name cannot be found"（中文共享名必踩）。
     * 因此这里必须原样返回，让 jcifs 把 Java String 直接转 UTF-16LE 上线。
     * '#' 会被 Handler.parseURL 拼回 path，安全；'?' 会被 java.net.URL 当 query 截断，
     * 但 Windows 文件名禁止 '?'，实际不出现。
     */
    public static String enc(String seg) {
        return seg == null ? "" : seg;
    }

    /** 列出主机的共享（可能抛 SmbAuthException）。过滤掉无浏览意义的 IPC$。 */
    public static List<Entry> listShares(Cred cred, String host) throws Exception {
        SmbFile root = new SmbFile("smb://" + host + "/", ctx(cred));
        List<Entry> out = mapEntries(root.listFiles(), "smb://" + host + "/");
        for (java.util.Iterator<Entry> it = out.iterator(); it.hasNext();) {
            if ("IPC$".equalsIgnoreCase(it.next().name)) it.remove();
        }
        return out;
    }

    /** 列出目录。dirUrl 必须以 / 结尾。 */
    public static List<Entry> listDir(Cred cred, String dirUrl) throws Exception {
        String base = dirUrl.endsWith("/") ? dirUrl : dirUrl + "/";
        SmbFile d = new SmbFile(base, ctx(cred));
        return mapEntries(d.listFiles(), base);
    }

    private static List<Entry> mapEntries(SmbFile[] arr, final String parentUrl) throws Exception {
        List<Entry> out = new ArrayList<>();
        if (arr == null) return out;
        for (SmbFile f : arr) {
            String raw = f.getName();
            boolean dir = raw.endsWith("/");
            String name = dir ? raw.substring(0, raw.length() - 1) : raw;
            if (name.length() == 0) continue;
            out.add(new Entry(name, parentUrl + enc(name) + (dir ? "/" : ""), dir, f.length(), f.lastModified()));
        }
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return col.compare(a.name, b.name);
            }
        });
        return out;
    }

    /** 是否认证错误（用于触发登录框）。 */
    public static boolean isAuthError(Throwable t) {
        return t instanceof SmbAuthException;
    }

    /** 供播放器/浏览器用的 MIME。 */
    public static String mime(String name) {
        String ext = "";
        int i = name.lastIndexOf('.');
        if (i >= 0) ext = name.substring(i + 1).toLowerCase(Locale.US);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m != null) return m;
        switch (ext) {
            case "mkv": return "video/x-matroska";
            case "ts": case "m2ts": return "video/mp2t";
            case "avi": return "video/x-msvideo";
            case "rmvb": case "rm": return "application/vnd.rn-realmedia-vbr";
            case "flv": return "video/x-flv";
            case "wmv": return "video/x-ms-wmv";
            case "mov": return "video/quicktime";
            case "3gp": return "video/3gpp";
            case "m4v": return "video/x-m4v";
            case "flac": return "audio/flac";
            case "ape": return "audio/x-ape";
            case "m4a": return "audio/mp4";
            case "wav": return "audio/x-wav";
            case "ogg": return "audio/ogg";
            case "iso": return "application/x-iso9660-image";
            case "ass": case "ssa": case "srt": return "text/plain";
            default: return "application/octet-stream";
        }
    }
}
