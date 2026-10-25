package com.tv.neighbor;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

/** 前台服务持有 StreamServer：播放期间进程不被杀，5 分钟无访问自动停。 */
public class StreamService extends Service {
    private static final long IDLE_MS = 5 * 60 * 1000;
    private static volatile StreamServer server;
    private static volatile boolean playerActive; // 播放页在前台：暂停/缓冲太久也别停服
    private final Handler h = new Handler();

    /** 播放页 onCreate/onDestroy 通知服务：页面存在期间（含后台暂停）空闲判定失效。 */
    public static void setPlayerActive(boolean b) {
        playerActive = b;
    }

    /** 播放页回前台时判断流服务是否还活着（5 分钟空闲自杀后为 false）。 */
    public static boolean serverRunning() {
        StreamServer s = server;
        return s != null && s.running();
    }

    /** 注册要播放的条目；必要时拉起服务。返回 http://127.0.0.1:... URL。 */
    public static String registerAndStream(android.content.Context c, Cred cred, String smbUrl, String mime) {
        if (server == null || !server.running()) {
            c.startService(new Intent(c, StreamService.class));
            // 慢 TV 固件上 startService 派发可能 >3s，最长等 10s（此等待必须在后台线程调）
            for (int i = 0; i < 500 && (server == null || !server.running()); i++) {
                try { Thread.sleep(20); } catch (InterruptedException e) { break; }
            }
        }
        StreamServer s = server;
        if (s == null || !s.running()) return null;
        String http = s.register(cred, smbUrl, mime);
        android.util.Log.i("StreamService", "serving " + http + " <- " + smbUrl);
        return http;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        android.util.Log.d("NN", "StreamService.onCreate");
        server = new StreamServer();
        if (server.start()) {
            android.util.Log.d("NN", "StreamService server up");
            startForeground(1, buildNotif());
            h.postDelayed(idleCheck, 60_000);
        } else {
            android.util.Log.d("NN", "StreamService server FAILED");
            stopSelf();
        }
    }

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            StreamServer s = server;
            if (s == null || (s.idle(IDLE_MS) && !playerActive)) {
                stopSelf();
            } else {
                h.postDelayed(this, 60_000);
            }
        }
    };

    private Notification buildNotif() {
        // API 26+ startForeground 要求带 channel 的通知，否则 Bad notification for startForeground
        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    "stream", getString(R.string.notif_title), android.app.NotificationManager.IMPORTANCE_LOW);
            getSystemService(android.app.NotificationManager.class).createNotificationChannel(ch);
            b = new Notification.Builder(this, "stream");
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentIntent(pi);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        StreamServer s = server;
        server = null;
        if (s != null) s.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
