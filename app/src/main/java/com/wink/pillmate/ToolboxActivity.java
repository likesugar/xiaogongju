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

/** 首页：工具箱 */
public class ToolboxActivity extends Activity {

    private TextView tvNext;
    private TextView tvNoiseState;

    private void applyImmersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
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
        applyImmersive();

        tvNext = findViewById(R.id.tvNextDose);
        tvNoiseState = findViewById(R.id.tvNoiseState); // 已挪入白噪音卡片

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

        bindNoise();

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
        updateNoiseState();
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

    // ---------- ASMR ----------
    private void bindNoise() {
        int[] ids = {R.id.noiseRain, R.id.noiseWave, R.id.noiseFire, R.id.noiseWind};
        final int[] kinds = {NoisePlayer.RAIN, NoisePlayer.WAVE, NoisePlayer.FIRE, NoisePlayer.WIND};
        for (int i = 0; i < ids.length; i++) {
            final int kind = kinds[i];
            findViewById(ids[i]).setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    NoisePlayer np = NoisePlayer.get();
                    if (np.isPlaying() && np.getKind() == kind) np.stop();
                    else np.play(kind);
                    updateNoiseState();
                }
            });
        }
        findViewById(R.id.btnStopNoise).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                NoisePlayer.get().stop();
                updateNoiseState();
            }
        });
    }

    private void updateNoiseState() {
        NoisePlayer np = NoisePlayer.get();
        String[] names = {"🌧️ 雨声播放中", "🌊 海浪播放中", "🔥 篝火播放中", "🍃 风声播放中"};
        if (np.isPlaying()) {
            tvNoiseState.setText(names[np.getKind()]);
            tvNoiseState.setTextColor(0xFF1677FF);
        } else {
            tvNoiseState.setText("未播放");
            tvNoiseState.setTextColor(0xFF8A94A6);
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
