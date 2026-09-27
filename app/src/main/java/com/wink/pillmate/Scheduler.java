package com.wink.pillmate;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

/** 精确闹钟调度：每个计划每个时间点一个 PendingIntent */
public class Scheduler {

    public static final int MAX_TIMES = 10;

    private static PendingIntent pi(Context c, MedStore.Plan p, int idx) {
        Intent it = new Intent(c, ReminderReceiver.class);
        it.putExtra("id", p.id);
        it.putExtra("idx", idx);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, p.id * 100 + idx, it, flags);
    }

    public static void scheduleAll(Context c, MedStore.Plan p) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        boolean exact = !(Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms());

        for (int i = 0; i < p.times.size() && i < MAX_TIMES; i++) {
            int[] t = p.times.get(i);
            // 从今天起 8 天内找第一个「计划生效且该时间点在未来」的日子
            for (int off = 0; off <= 8; off++) {
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, off);
                if (!p.activeToday(cal)) continue;
                if (off == 0) {
                    Calendar at = (Calendar) cal.clone();
                    at.set(Calendar.HOUR_OF_DAY, t[0]);
                    at.set(Calendar.MINUTE, t[1]);
                    at.set(Calendar.SECOND, 0);
                    at.set(Calendar.MILLISECOND, 0);
                    if (at.getTimeInMillis() <= System.currentTimeMillis()) continue;
                    set(am, exact, at, pi(c, p, i));
                    break;
                } else {
                    cal.set(Calendar.HOUR_OF_DAY, t[0]);
                    cal.set(Calendar.MINUTE, t[1]);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    set(am, exact, cal, pi(c, p, i));
                    break;
                }
            }
        }
    }

    private static void set(AlarmManager am, boolean exact, Calendar cal, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= 23 && exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
        }
    }

    /** 重新调度全部计划（保存 / 启动 / 开机后调用） */
    public static void rescheduleAll(Context c) {
        List<MedStore.Plan> plans = MedStore.load(c);
        for (MedStore.Plan p : plans) scheduleAll(c, p);
    }

    public static void cancel(Context c, MedStore.Plan p) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < MAX_TIMES; i++) am.cancel(pi(c, p, i));
    }
}
