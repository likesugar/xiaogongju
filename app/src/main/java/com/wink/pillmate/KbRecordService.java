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
            PlayerActivity.updateRecNote();
            return START_NOT_STICKY;
        }
        LiveProxy.start();
        if (LiveProxy.mediaUrl == null) {
            LiveProxy.mediaUrl = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("mediaUrl", null);
        }
        LiveProxy.startRefresher();
        // 与录制总通知同一款（同 id 9100），不再单独显示
        startForeground(9100, PlayerActivity.buildRecNote(this));
        PlayerActivity.updateRecNote();
        new Thread(new Runnable() { public void run() {
            while (true) {
                try { Thread.sleep(2000); if (SnifferActivity.kbState[0] != 1) break; }
                catch (Throwable e) { break; }
            }
        } }).start();
        return START_STICKY;
    }


}
