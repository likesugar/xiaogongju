package com.wink.pillmate;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 新增用药计划表单 */
public class AddActivity extends Activity {

    private String relation = "";
    private int repeat = 0;
    private int weekdays = 0b0111110; // 默认勾 周一~周五 之外的？默认给 全勾
    private String unit = "片";
    private String sound = "system";
    private long startDay, endDay;
    private final List<int[]> times = new ArrayList<int[]>();

    private TextView tvStart, tvEnd, tvSound;
    private LinearLayout timeChips, weekdayRow;
    private TextView addTimeBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add);

        tvStart = findViewById(R.id.tvStart);
        tvEnd = findViewById(R.id.tvEnd);
        tvSound = findViewById(R.id.tvSound);
        timeChips = findViewById(R.id.timeChips);
        weekdayRow = findViewById(R.id.weekdayRow);

        Calendar today = Calendar.getInstance();
        startDay = ymd(today);
        tvStart.setText(fmtDay(startDay));

        weekdays = 0b1111111;

        // 添加时间胶囊：跟在时间 chips 后面同一行
        addTimeBtn = new TextView(this);
        addTimeBtn.setText("＋ 添加时间");
        addTimeBtn.setTextColor(0xFF1677FF);
        addTimeBtn.setTextSize(13);
        addTimeBtn.setGravity(Gravity.CENTER);
        addTimeBtn.setBackgroundResource(R.drawable.bg_dashed);
        addTimeBtn.setPadding((int) dp(14), 0, (int) dp(14), 0);
        addTimeBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Calendar now = Calendar.getInstance();
                new TimePickerDialog(AddActivity.this,
                        new TimePickerDialog.OnTimeSetListener() {
                            public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
                                times.add(new int[]{h, m});
                                renderTimes();
                            }
                        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
            }
        });

        bindUnit();
        bindRelation();
        bindRepeat();
        bindDates();
        bindSound();

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });

        renderTimes();
    }

    // ---------- 单位：片 / 粒 ----------
    private void bindUnit() {
        final TextView pill = findViewById(R.id.unitPill);
        final TextView gran = findViewById(R.id.unitGran);
        pill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { unit = "片"; paintUnit(pill, gran); }
        });
        gran.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { unit = "粒"; paintUnit(pill, gran); }
        });
        paintUnit(pill, gran);
    }

    private void paintUnit(TextView pill, TextView gran) {
        pill.setBackgroundResource("片".equals(unit) ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        gran.setBackgroundResource("粒".equals(unit) ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        pill.setTextColor("片".equals(unit) ? 0xFF1677FF : 0xFF6B7280);
        gran.setTextColor("粒".equals(unit) ? 0xFF1677FF : 0xFF6B7280);
    }

    // ---------- 服药关系 ----------
    private void bindRelation() {
        int[] ids = {R.id.relBefore, R.id.relAfter, R.id.relSleep};
        final String[] vals = {"before", "after", "sleep"};
        for (int i = 0; i < ids.length; i++) {
            final int fi = i;
            final TextView tv = findViewById(ids[i]);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    relation = vals[fi].equals(relation) ? "" : vals[fi];
                    paintChips(ids, vals, relation);
                }
            });
        }
        paintChips(ids, vals, "");
    }

    private void paintChips(int[] ids, String[] vals, String sel) {
        for (int i = 0; i < ids.length; i++) {
            TextView tv = findViewById(ids[i]);
            boolean on = vals[i].equals(sel);
            tv.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            tv.setTextColor(on ? 0xFF1677FF : 0xFF6B7280);
        }
    }

    // ---------- 重复周期 ----------
    private void bindRepeat() {
        int[] ids = {R.id.rpDaily, R.id.rpWork, R.id.rpCustom};
        for (int i = 0; i < ids.length; i++) {
            final int fi = i;
            final TextView tv = findViewById(ids[i]);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    repeat = fi;
                    paintRepeat();
                }
            });
        }
        paintRepeat();

        int[] wids = {R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6, R.id.wd7};
        for (int i = 0; i < wids.length; i++) {
            final int fi = i;
            final TextView tv = findViewById(wids[i]);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    weekdays ^= (1 << fi);
                    paintWeekdays();
                }
            });
        }
        paintWeekdays();
    }

    private void paintRepeat() {
        int[] ids = {R.id.rpDaily, R.id.rpWork, R.id.rpCustom};
        for (int i = 0; i < ids.length; i++) {
            TextView tv = findViewById(ids[i]);
            boolean on = repeat == i;
            tv.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            tv.setTextColor(on ? 0xFF1677FF : 0xFF6B7280);
        }
        weekdayRow.setVisibility(repeat == 2 ? View.VISIBLE : View.GONE);
    }

    private void paintWeekdays() {
        int[] wids = {R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6, R.id.wd7};
        for (int i = 0; i < wids.length; i++) {
            TextView tv = findViewById(wids[i]);
            boolean on = (weekdays & (1 << i)) != 0;
            tv.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
            tv.setTextColor(on ? 0xFF1677FF : 0xFF6B7280);
        }
    }

    // ---------- 起止日期 ----------
    private void bindDates() {
        findViewById(R.id.rowStart).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(startDay, new DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(android.widget.DatePicker dp, int y, int m, int d) {
                        Calendar cal = Calendar.getInstance();
                        cal.set(y, m, d);
                        startDay = ymd(cal);
                        if (endDay > 0 && endDay < startDay) endDay = startDay;
                        tvStart.setText(fmtDay(startDay));
                        tvEnd.setText(endDay > 0 ? fmtDay(endDay) : "未设置");
                    }
                });
            }
        });
        findViewById(R.id.rowEnd).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDate(endDay > 0 ? endDay : startDay, new DatePickerDialog.OnDateSetListener() {
                    public void onDateSet(android.widget.DatePicker dp, int y, int m, int d) {
                        Calendar cal = Calendar.getInstance();
                        cal.set(y, m, d);
                        long v2 = ymd(cal);
                        if (v2 < startDay) {
                            Toast.makeText(AddActivity.this, "结束日期不能早于开始日期", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        endDay = v2;
                        tvEnd.setText(fmtDay(endDay));
                    }
                });
            }
        });
    }

    private void pickDate(long init, DatePickerDialog.OnDateSetListener cb) {
        long base = init > 0 ? init : ymd(Calendar.getInstance());
        int y = (int) (base / 10000), m = (int) (base / 100 % 100) - 1, d = (int) (base % 100);
        new DatePickerDialog(this, cb, y, m, d).show();
    }

    // ---------- 提醒铃声 ----------
    private void bindSound() {
        final String[] names = {"系统提示音", "仅振动", "静音"};
        final String[] vals = {"system", "vibrate", "silent"};
        findViewById(R.id.rowSound).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = 0;
                for (int i = 0; i < vals.length; i++) if (vals[i].equals(sound)) cur = i;
                cur = (cur + 1) % vals.length;
                sound = vals[cur];
                tvSound.setText(names[cur]);
            }
        });
        tvSound.setText(names[0]);
    }

    // ---------- 时间 chips ----------
    private void renderTimes() {
        timeChips.removeAllViews();
        final List<int[]> sorted = new ArrayList<int[]>(times);
        java.util.Collections.sort(sorted, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[0] * 60 + a[1] - (b[0] * 60 + b[1]); }
        });
        for (int i = 0; i < sorted.size(); i++) {
            final int[] t = sorted.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_chip_on);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (int) dp(34));
            lp.leftMargin = i > 0 ? (int) dp(8) : 0;
            chip.setLayoutParams(lp);
            chip.setPadding((int) dp(12), 0, (int) dp(12), 0);

            TextView tv = new TextView(this);
            tv.setText(String.format(Locale.US, "%02d:%02d", t[0], t[1]));
            tv.setTextColor(0xFF1677FF);
            tv.setTextSize(14);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.addView(tv);

            TextView del = new TextView(this);
            del.setText(" ✕");
            del.setTextColor(0xFF9AA5B5);
            del.setTextSize(12);
            del.setPadding((int) dp(6), 0, 0, 0);
            chip.addView(del);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    times.remove(t);
                    renderTimes();
                }
            });

            timeChips.addView(chip);
        }

        // 添加时间按钮始终跟在最后一个 chip 后面
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) dp(34));
        alp.leftMargin = (int) dp(8);
        addTimeBtn.setLayoutParams(alp);
        timeChips.addView(addTimeBtn);
    }

    // ---------- 保存 ----------
    private void save() {
        String name = ((EditText) findViewById(R.id.etName)).getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "先填写药品名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (times.isEmpty()) {
            Toast.makeText(this, "至少添加一个服用时间", Toast.LENGTH_SHORT).show();
            return;
        }
        if (repeat == 2 && weekdays == 0) {
            Toast.makeText(this, "自定义周期至少选一天", Toast.LENGTH_SHORT).show();
            return;
        }
        String spec = ((EditText) findViewById(R.id.etSpec)).getText().toString().trim();
        String num = ((EditText) findViewById(R.id.etDose)).getText().toString().trim();
        if (num.isEmpty()) num = "1";
        String dose = num + " " + unit;

        MedStore.Plan p = new MedStore.Plan();
        p.id = MedStore.nextId(this);
        MedStore.bumpId(this);
        p.name = name;
        p.spec = spec;
        p.dose = dose;
        p.times.addAll(times);
        p.relation = relation;
        p.repeat = repeat;
        p.weekdays = weekdays;
        p.startDay = startDay;
        p.endDay = endDay;
        p.sound = sound;

        List<MedStore.Plan> plans = MedStore.load(this);
        plans.add(p);
        MedStore.save(this, plans);
        Scheduler.scheduleAll(this, p);
        Toast.makeText(this, "计划已保存 ✓", Toast.LENGTH_SHORT).show();
        finish();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static long ymd(Calendar c) {
        return c.get(Calendar.YEAR) * 10000L + (c.get(Calendar.MONTH) + 1) * 100L
                + c.get(Calendar.DAY_OF_MONTH);
    }

    private static String fmtDay(long v) {
        return String.format(Locale.US, "%04d-%02d-%02d", v / 10000, v / 100 % 100, v % 100);
    }
}
