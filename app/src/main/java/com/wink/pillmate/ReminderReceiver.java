package com.wink.pillmate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.Calendar;

/** 到点提醒：按周期/日期过滤 + 轻柔通知 + 自动排下一天 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String CH = "pillmate_remind2";

    @Override
    public void onReceive(Context c, Intent intent) {
        final int id = intent.getIntExtra("id", -1);
        final int idx = intent.getIntExtra("idx", 0);
        final Context ctx = c.getApplicationContext();
        final MedStore.Plan p = MedStore.byId(ctx, id);
        if (p == null || p.times.isEmpty()) return;

        // goAsync 拉住接收器，确保后台也能把提示音播完
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (p.activeToday(Calendar.getInstance())) {
                        notifyIt(ctx, p, idx);
                        playBeep(ctx, p);
                    }
                    // 无论如何继续排后续闹钟
                    Scheduler.scheduleAll(ctx, p);
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }

    private void notifyIt(Context c, MedStore.Plan p, int idx) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "用药提醒",
                    "vibrate".equals(p.sound) ? NotificationManager.IMPORTANCE_DEFAULT
                            : NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("到点轻柔提醒");
            if ("vibrate".equals(p.sound)) {
                ch.setSound(null, null);
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 300, 250, 300});
            } else if ("silent".equals(p.sound)) {
                ch.setSound(null, null);
                ch.enableVibration(false);
            } else {
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 300, 250, 300});
                // 下课铃（内置 raw 资源）
                Uri sound = Uri.parse("android.resource://" + c.getPackageName() + "/raw/school_bell");
                ch.setSound(sound, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            }
            nm.createNotificationChannel(ch);
        }

        String dose = (p.dose == null || p.dose.isEmpty()) ? "" : p.dose;
        String title = "💊 该吃药啦";
        String text = p.name + (dose.isEmpty() ? "" : " · " + dose)
                + (p.relationText().isEmpty() ? "" : "（" + p.relationText() + "）");

        PendingIntent contentPi = PendingIntent.getActivity(c, p.id,
                new Intent(c, ToolboxActivity.class),
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
                        | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CH)
                : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(contentPi);
        if (Build.VERSION.SDK_INT < 26) {
            b.setDefaults("silent".equals(p.sound) ? 0
                    : "vibrate".equals(p.sound) ? Notification.DEFAULT_VIBRATE
                    : Notification.DEFAULT_ALL);
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        nm.notify(p.id * 10 + (idx % 10), b.build());
    }

    /** 自带提示音：下课铃循环 ~12s，不依赖系统铃声设置 */
    private void playBeep(final Context c, final MedStore.Plan p) {
        new Thread(new Runnable() {
            public void run() {
                if ("vibrate".equals(p.sound)) {
                    vibrate(c, new long[]{0, 300, 250, 300});
                    return;
                }
                if ("silent".equals(p.sound)) return;
                android.media.MediaPlayer mp = null;
                try {
                    mp = new android.media.MediaPlayer();
                    mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
                    mp.setDataSource(c, Uri.parse("android.resource://" + c.getPackageName() + "/raw/school_bell"));
                    mp.prepare();
                    mp.setLooping(true);
                    mp.start();
                    Thread.sleep(12000);
                    mp.stop();
                    vibrate(c, new long[]{0, 300, 250, 300, 250, 300});
                } catch (Exception ignored) {
                } finally {
                    if (mp != null) try { mp.release(); } catch (Exception ignored2) {}
                }
            }
        }).start();
    }

    private void vibrate(Context c, long[] pattern) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {}
    }
}
