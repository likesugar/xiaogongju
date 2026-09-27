package com.wink.pillmate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** 用药计划页：列表 + 新增 */
public class PlansActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_plans);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnAdd).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(PlansActivity.this, AddActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderList();
    }

    private void renderList() {
        LinearLayout box = findViewById(R.id.listContainer);
        box.removeAllViews();
        List<MedStore.Plan> plans = MedStore.load(this);
        findViewById(R.id.tvEmpty).setVisibility(plans.isEmpty() ? View.VISIBLE : View.GONE);

        for (final MedStore.Plan p : plans) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            int pad = (int) dp(16);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (box.getChildCount() > 0) lp.topMargin = (int) dp(12);
            card.setLayoutParams(lp);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText("💊 " + p.name);
            name.setTextColor(0xFF1F2329);
            name.setTextSize(17);
            name.setTypeface(null, Typeface.BOLD);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setSingleLine(true);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(name);

            TextView t1 = new TextView(this);
            t1.setText(p.timesText());
            t1.setTextColor(0xFF1677FF);
            t1.setTextSize(14);
            t1.setTypeface(null, Typeface.BOLD);
            row.addView(t1);
            card.addView(row);

            StringBuilder sb = new StringBuilder();
            if (p.dose != null && !p.dose.isEmpty()) sb.append(p.dose);
            if (p.spec != null && !p.spec.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(p.spec);
            }
            if (!p.relationText().isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(p.relationText());
            }
            String rep = p.repeat == 0 ? "每天" : p.repeat == 1 ? "工作日" : "自定义";
            if (sb.length() > 0) sb.append(" · ");
            sb.append(rep);
            boolean doneToday = p.doneDate != null && p.doneDate.equals(todayStr());
            TextView meta = new TextView(this);
            meta.setText(sb + (doneToday ? " · 今日已完成 ✓" : ""));
            meta.setTextColor(doneToday ? 0xFF0E9F6E : 0xFF8A94A6);
            meta.setTextSize(13);
            meta.setPadding(0, (int) dp(6), 0, 0);
            card.addView(meta);

            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            ops.setPadding(0, (int) dp(10), 0, 0);

            TextView done = new TextView(this);
            done.setText(doneToday ? "撤销完成" : "完成 ✓");
            done.setTextColor(0xFF0E9F6E);
            done.setTextSize(14);
            done.setTypeface(null, Typeface.BOLD);
            done.setPadding((int) dp(16), (int) dp(6), (int) dp(16), (int) dp(6));
            done.setBackgroundResource(R.drawable.bg_chip_off);
            done.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String nd = p.doneDate != null && p.doneDate.equals(todayStr()) ? "" : todayStr();
                    List<MedStore.Plan> all = MedStore.load(PlansActivity.this);
                    for (MedStore.Plan x : all) if (x.id == p.id) x.doneDate = nd;
                    MedStore.save(PlansActivity.this, all);
                    renderList();
                }
            });
            ops.addView(done);

            TextView del = new TextView(this);
            del.setText("删除");
            del.setTextColor(0xFFE5484D);
            del.setTextSize(14);
            del.setPadding((int) dp(16), (int) dp(6), (int) dp(16), (int) dp(6));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.leftMargin = (int) dp(8);
            del.setLayoutParams(dlp);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new AlertDialog.Builder(PlansActivity.this)
                            .setTitle("删除计划")
                            .setMessage("确定删除「" + p.name + "」的提醒吗？")
                            .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                                public void onClick(android.content.DialogInterface d, int w) {
                                    Scheduler.cancel(PlansActivity.this, p);
                                    List<MedStore.Plan> all = MedStore.load(PlansActivity.this);
                                    for (int i = 0; i < all.size(); i++)
                                        if (all.get(i).id == p.id) all.remove(i--);
                                    MedStore.save(PlansActivity.this, all);
                                    renderList();
                                }
                            })
                            .setNegativeButton("取消", null).show();
                }
            });
            ops.addView(del);
            card.addView(ops);

            box.addView(card);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static String todayStr() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }
}
