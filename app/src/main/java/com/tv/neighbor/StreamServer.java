package com.tv.neighbor;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * 本机回环 HTTP 流服务器：把 smb:// 条目以 http://127.0.0.1:port/<token>/<name>
 * 提供给系统播放器，支持 HTTP Range（随机 seek），这是电视播放器能拖进度条的关键。
 */
public final class StreamServer {
    /** 一个可播放的条目。 */
    public static class Item {
        public final Cred cred;
        public final String url;
        public final String mime;

        Item(Cred cred, String url, String mime) {
            this.cred = cred;
            this.url = url;
            this.mime = mime;
        }
    }

    private volatile ServerSocket server;
    private ExecutorService pool;
    // 注册表带上限（LinkedHashMap 保注册顺序）：悬停预览每次都会 register，
    // 只进不出的 map 会长驻越积越多——超限按注册顺序淘汰最旧的 token。
    private static final int MAX_ITEMS = 32;
    private final java.util.LinkedHashMap<String, Item> items = new java.util.LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile long lastUsed = System.currentTimeMillis();

    /** 绑定 127.0.0.1 随机端口并开始 accept。返回 false 表示启动失败。 */
    public synchronized boolean start() {
        if (server != null && !server.isClosed()) return true;
        try {
            server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            return false;
        }
        pool = Executors.newFixedThreadPool(6);
        final ServerSocket ss = server;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!ss.isClosed()) {
                    try {
                        final Socket s = ss.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(s);
                            }
                        });
                    } catch (IOException e) {
                        if (ss.isClosed()) return;
                    }
                }
            }
        }, "stream-accept");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
        if (pool != null) pool.shutdownNow();
        pool = null;
        items.clear();
    }

    public int port() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    public boolean running() {
        ServerSocket s = server;
        return s != null && !s.isClosed();
    }

    public boolean idle(long thresholdMs) {
        return System.currentTimeMillis() - lastUsed > thresholdMs;
    }

    /** 注册条目，返回带 token 的完整 URL。（超上限按注册顺序淘汰最旧的） */
    public synchronized String register(Cred cred, String url, String mime) {
        String token = Long.toHexString(seq.incrementAndGet()) + Long.toHexString(System.nanoTime());
        items.put(token, new Item(cred, url, mime));
        while (items.size() > MAX_ITEMS) {
            items.remove(items.keySet().iterator().next());
        }
        lastUsed = System.currentTimeMillis();
        return "http://127.0.0.1:" + port() + "/" + token + "/stream";
    }

    /** 连接处理：解析请求行与 Range，把 SMB 流泵出去。 */
    private void handle(Socket sock) {
        SmbRandomAccessFile raf = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            sock.setSoTimeout(30000);
            byte[] rb = new byte[8192];
            StringBuilder req = new StringBuilder();
            java.io.InputStream is = sock.getInputStream();
            int nl;
            while ((nl = is.read(rb)) > 0) {
                req.append(new String(rb, 0, nl, "ASCII"));
                if (req.toString().contains("\r\n\r\n")) break;
            }
            String head = req.toString();
            if (head.isEmpty()) return;
            String[] lines = head.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0];
            String path = first[1];
            if (!method.equals("GET") && !method.equals("HEAD")) {
                writeSimple(out = sock.getOutputStream(), "405 Method Not Allowed");
                return;
            }
            out = new BufferedOutputStream(sock.getOutputStream(), 256 * 1024);
            // path = /<token>/...
            String[] seg = path.split("/");
            String token = seg.length > 1 ? seg[1] : "";
            Item it;
            synchronized (this) { it = items.get(token); } // LinkedHashMap 非并发安全
            if (it == null) {
                writeSimple(out, "404 Not Found");
                return;
            }
            lastUsed = System.currentTimeMillis();
            // Range 头
            long rStart = -1, rEnd = -1;
            for (String ln : lines) {
                if (ln.toLowerCase().startsWith("range:")) {
                    String v = ln.substring(6).trim().toLowerCase();
                    if (v.startsWith("bytes=")) {
                        String[] parts = v.substring(6).split("-");
                        try {
                            if (parts[0].length() > 0) rStart = Long.parseLong(parts[0].trim());
                            if (parts.length > 1 && parts[1].length() > 0) rEnd = Long.parseLong(parts[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    }
                }
            }
            SmbFile f = new SmbFile(it.url, Smb.ctx(it.cred));
            long len = f.length();
            String dispName = "network-neighbor-stream";
            String mime = it.mime == null ? "application/octet-stream" : it.mime;
            boolean headOnly = method.equals("HEAD");
            if (rStart >= 0 && rStart < len) {
                long end = rEnd < 0 ? len - 1 : Math.min(rEnd, len - 1);
                long n = end - rStart + 1;
                out.write(("HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Type: " + mime + "\r\n"
                        + "Content-Range: bytes " + rStart + "-" + end + "/" + len + "\r\n"
                        + "Content-Length: " + n + "\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "Connection: close\r\n\r\n").getBytes("ASCII"));
                out.flush();
                if (!headOnly) {
                    raf = new SmbRandomAccessFile(f, "r");
                    raf.seek(rStart);
                    pump(raf, out, n, it);
                }
            } else if (rStart >= len) {
                writeSimple(out, "416 Requested Range Not Satisfiable");
            } else {
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: " + mime + "\r\n"
                        + "Content-Length: " + len + "\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "Connection: close\r\n\r\n").getBytes("ASCII"));
                out.flush();
                if (!headOnly) {
                    in = f.openInputStream();
                    pump(in, out, -1, it);
                }
            }
            out.flush();
            try { sock.shutdownOutput(); } catch (Exception ignored) {}
        } catch (SocketException e) {
            // 播放器主动断开/seek 中断——正常
        } catch (Exception e) {
            // 网络/协议错误：静默关闭
        } finally {
            try { if (raf != null) raf.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private void pump(InputStream src, OutputStream dst, long remain, Item it) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (remain != 0) {
            int want = remain < 0 ? buf.length : (int) Math.min(buf.length, remain);
            int r = src.read(buf, 0, want);
            if (r < 0) break;
            dst.write(buf, 0, r);
            if (remain > 0) remain -= r;
            lastUsed = System.currentTimeMillis(); // 长请求持续续命：系统播放器一次大 Range 要泵几十分钟，
            // 空闲判定若只看"上次收到请求"，播到 5 分钟整会被 idleCheck 掐断
        }
    }

    /** SmbRandomAccessFile 不是 InputStream（jcifs-ng），单独一个重载。 */
    private void pump(SmbRandomAccessFile src, OutputStream dst, long remain, Item it) throws IOException {
        byte[] buf = new byte[64 * 1024];
        while (remain != 0) {
            int want = remain < 0 ? buf.length : (int) Math.min(buf.length, remain);
            int r = src.read(buf, 0, want);
            if (r < 0) break;
            dst.write(buf, 0, r);
            if (remain > 0) remain -= r;
            lastUsed = System.currentTimeMillis(); // 长请求持续续命：系统播放器一次大 Range 要泵几十分钟，
            // 空闲判定若只看"上次收到请求"，播到 5 分钟整会被 idleCheck 掐断
        }
    }

    private void writeSimple(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ASCII"));
        out.flush();
    }
}
