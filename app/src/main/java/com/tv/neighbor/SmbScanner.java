package com.tv.neighbor;

import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** TCP 445 端口并发扫段 + NBNS 反解主机名。 */
public final class SmbScanner {
    public interface Listener {
        void onFound(String ip, String name, String smbVer);

        void onProgress(int done, int total);

        void onDone(int found);

        void onError(String msg);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool;
    private volatile boolean cancelled;

    /** 取本机所在 IPv4 /24 网段前缀（如 192.168.31），失败返回 null。 */
    public static String localPrefix() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                List<InterfaceAddress> ias = ni.getInterfaceAddresses();
                for (InterfaceAddress ia : ias) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        byte[] b = a.getAddress();
                        return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public synchronized boolean running() {
        return pool != null && !pool.isShutdown() && !cancelled;
    }

    public synchronized void start(String prefix, final Listener l) {
        cancel();
        cancelled = false;
        final String pfx = prefix;
        pool = Executors.newFixedThreadPool(48);
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger found = new AtomicInteger();
        final ExecutorService sweep = pool;

        for (int i = 1; i <= 254; i++) {
            final String ip = pfx + "." + i;
            sweep.execute(new Runnable() {
                @Override
                public void run() {
                    boolean open = false;
                    if (!cancelled) {
                        Socket s = new Socket();
                        try {
                            s.connect(new InetSocketAddress(ip, 445), 600);
                            open = true;
                        } catch (Exception ignored) {
                        } finally {
                            try { s.close(); } catch (Exception ignored) {}
                        }
                    }
                    if (open) {
                        // 445 开着还不够——必须是真正的 SMB 服务（顺带拿到方言）
                        final String ver = SmbProbe.probe(ip);
                        if (ver != null) {
                            found.incrementAndGet();
                            final String name = Nbns.query(ip);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!cancelled && l != null) l.onFound(ip, name, ver);
                                }
                            });
                        }
                    }
                    int d = done.incrementAndGet();
                    final int fd = d;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!cancelled && l != null) l.onProgress(fd, 254);
                        }
                    });
                    if (fd >= 254) {
                        final int f = found.get();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!cancelled && l != null) l.onDone(f);
                            }
                        });
                        shutdownPool();
                    }
                }
            });
        }
    }

    private synchronized void shutdownPool() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown();
        }
    }

    public synchronized void cancel() {
        cancelled = true;
        if (pool != null) pool.shutdownNow();
        pool = null;
    }
}
