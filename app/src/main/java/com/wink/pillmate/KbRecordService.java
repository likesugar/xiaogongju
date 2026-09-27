package com.wink.pillmate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** KB 后台录制前台服务：保活+通知栏停止按钮 */
public class KbRecordService extends Service {

    private static final String CHANNEL = "kbrecord";
    public static final String ACTION_STOP = "com.wink.pillmate.KB_STOP";

    private android.os.PowerManager.WakeLock wl;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pillmate:kbrec");
            wl.acquire();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SnifferActivity.stopKb();
            LiveProxy.stopRefresher();
            stopSelf();
            return START_NOT_STICKY;
        }
        LiveProxy.start();
        if (LiveProxy.mediaUrl == null) {
            LiveProxy.mediaUrl = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("mediaUrl", null);
        }
        LiveProxy.startRefresher();
        startForeground(2001, buildNotification("初始化…"));
        new Thread(new Runnable() { public void run() {
            while (true) {
                try {
                    Thread.sleep(2000);
                    long age = LiveProxy.latestAt == 0 ? -1 : (System.currentTimeMillis() - LiveProxy.latestAt) / 1000;
                    String t = "轮询 " + LiveProxy.pollCount + " 次｜失败 " + LiveProxy.failCount
                        + "｜最新列表 " + (age < 0 ? "无" : age + " 秒前");
                    updateNotification(t);
                    if (SnifferActivity.kbState[0] != 1) break;
                } catch (Throwable e) { break; }
            }
        } }).start();
        return START_STICKY;
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(2001, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "直播录制", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        // 点通知本体 = 进入解析页查看
        android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(this, 2,
            new Intent(this, PlayerActivity.class)
                .putExtra("autoUrl", "http://127.0.0.1:" + LiveProxy.PORT + "/playlist.m3u8")
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("小工具 · 录制中")
            .setContentText(text + "｜点此进入查看")
            .setContentIntent(openPi)
            .setOngoing(true);
        android.app.PendingIntent pi = android.app.PendingIntent.getService(this, 0,
            new Intent(this, KbRecordService.class).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, "停止", pi).build());
        return b.build();
    }
}
