package com.wink.pillmate;

import android.app.Activity;
import android.app.AlarmManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

/** 首页：工具箱（纯黑/冰蓝主题切换 · 全屏） */
public class ToolboxActivity extends Activity {

    private TextView tvNext;

    // ---------- 主题（纯黑 / 冰蓝） ----------
    private void applyTheme() {
        boolean dark = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark", false);
        findViewById(R.id.toolRoot).setBackgroundColor(dark ? 0xFF000000 : 0xFFEEF4FF);
        findViewById(R.id.toolColumn).setBackgroundColor(dark ? 0xFF000000 : 0xFFEEF4FF);
        ((TextView) findViewById(R.id.themeToggle)).setText(dark ? "☀️" : "🌙");
        applyTraversal((android.view.ViewGroup) findViewById(R.id.toolColumn), dark);
    }

    private void applyTraversal(android.view.ViewGroup vg, boolean dark) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View c = vg.getChildAt(i);
            String t = c.getTag() == null ? "" : c.getTag().toString();
            if (c instanceof TextView) {
                if (t.equals("title")) ((TextView) c).setTextColor(dark ? 0xFFFFFFFF : 0xFF1F2329);
                else if (t.equals("sub")) ((TextView) c).setTextColor(dark ? 0xFF9AA3AE : 0xFF8A94A6);
                else if (t.equals("chip")) {
                    ((TextView) c).setBackgroundResource(dark ? R.drawable.bg_chip_dark : R.drawable.bg_chip_off);
                    ((TextView) c).setTextColor(dark ? 0xFF9AA3AE : 0xFF8A94A6);
                }
            }
            if (t.equals("card")) c.setBackgroundResource(dark ? R.drawable.bg_card_dark : R.drawable.bg_card);
            if (c instanceof android.view.ViewGroup) applyTraversal((android.view.ViewGroup) c, dark);
        }
    }

    // ---------- 沉浸全屏 ----------
    private void applyImmersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toolbox);
        applyTheme();
        applyImmersive();

        findViewById(R.id.themeToggle).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean dark = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("dark", false);
                getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("dark", !dark).apply();
                applyTheme();
            }
        });

        findViewById(R.id.cardMeds).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(ToolboxActivity.this, PlansActivity.class));
            }
        });
        findViewById(R.id.cardPlayer).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(ToolboxActivity.this, PlayerActivity.class));
            }
        });
        findViewById(R.id.cardParse).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(ToolboxActivity.this, DouyinActivity.class));
            }
        });

        tvNext = findViewById(R.id.tvNextDose);

        // 版本标识（确认装的是哪个包）
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            android.widget.Toast.makeText(this, "v" + pi.versionName + " (vc" + pi.versionCode + ")", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }
        ensureExactAlarm();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNext();
    }

    private void refreshNext() {
        List<MedStore.Plan> plans = MedStore.load(this);
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        String best = null;
        int bestMin = 24 * 60 + 1;
        int total = 0, done = 0;
        for (MedStore.Plan p : plans) {
            if (!p.activeToday(c)) continue;
            total++;
            if (todayStr().equals(p.doneDate)) { done++; continue; }
            for (int[] t : p.times) {
                int m = t[0] * 60 + t[1];
                if (m >= now && m < bestMin) {
                    bestMin = m;
                    best = String.format("%02d:%02d · %s", t[0], t[1], p.name);
                }
            }
        }
        if (best != null) {
            tvNext.setText("⏰ 下一次 " + best);
        } else if (total > 0) {
            tvNext.setText("✅ 今日计划已完成 " + done + "/" + total);
        } else {
            tvNext.setText("🌱 还没有计划，点进去添加");
        }
    }

    private void ensureExactAlarm() {
        if (Build.VERSION.SDK_INT < 31) return;
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am != null && !am.canScheduleExactAlarms()) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "请到系统设置允许「闹钟和提醒」", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static String todayStr() {
        Calendar c = Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
