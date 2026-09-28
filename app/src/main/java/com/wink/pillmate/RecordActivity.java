package com.wink.pillmate;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 录制下载页：一列任务，右侧 ⋮ 弹菜单（开始/播放/结束录制/取消） */
public class RecordActivity extends Activity {

    private Handler handler;
    private Runnable tick;
    private LinearLayout list;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(24, 40, 24, 24);

        TextView title = new TextView(this);
        title.setText("下载");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, 20);
        col.addView(title);

        tvEmpty = new TextView(this);
        tvEmpty.setText("暂无录制任务");
        tvEmpty.setTextColor(0xFF8A919E);
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, 120, 0, 0);
        col.addView(tvEmpty);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);

        sv.addView(col);
        root.addView(sv);
        setContentView(root);

        handler = new Handler();
        tick = new Runnable() {
            public void run() {
                rebuild();
                handler.postDelayed(this, 1500);
            }
        };
    }

    @Override
    protected void onResume() { super.onResume(); handler.post(tick); }

    @Override
    protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }

    private static String fmtDur(long s) {
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    private static String fmtSize(long b) {
        if (b >= 1048576) return String.format(java.util.Locale.US, "%.2fMB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.0fKB", b / 1024.0);
    }

    private void rebuild() {
        list.removeAllViews();
        int n = PlayerActivity.recJobs.size() + PlayerActivity.stoppedJobs.size();
        tvEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        for (PlayerActivity.RecJob j : PlayerActivity.recJobs.values()) addRow(list, j, true);
        for (PlayerActivity.RecJob j : PlayerActivity.stoppedJobs.values()) addRow(list, j, false);
    }

    private void addRow(LinearLayout parent, final PlayerActivity.RecJob j, final boolean live) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 24, 0, 24);

        // 左侧占位缩略块（▶）
        FrameLayout thumb = new FrameLayout(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(140, 90);
        tp.rightMargin = 24;
        thumb.setLayoutParams(tp);
        thumb.setBackgroundColor(0xFF1E242E);
        TextView play = new TextView(this);
        play.setText("▶");
        play.setTextColor(0xFF8A919E);
        play.setGravity(Gravity.CENTER);
        thumb.addView(play, new FrameLayout.LayoutParams(-1, -1));
        row.addView(thumb);

        // 中间信息
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2);
        mp.weight = 1;
        mid.setLayoutParams(mp);

        TextView tvName = new TextView(this);
        tvName.setText(j.name);
        tvName.setTextColor(Color.WHITE);
        tvName.setTextSize(16);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        tvName.setSingleLine(true);
        mid.addView(tvName);

        long secs = j.secs + (live && j.startTs > 0 ? (System.currentTimeMillis() - j.startTs) / 1000 : 0);
        long size = j.file != null && j.file.exists() ? j.file.length() : 0;
        String info = "录制时长: " + fmtDur(secs)
            + "\n录制大小: " + fmtSize(size)
            + "\n录制状态: " + (live ? "录制中" : "暂停录制");
        TextView tvInfo = new TextView(this);
        tvInfo.setText(info);
        tvInfo.setTextColor(0xFF8A919E);
        tvInfo.setTextSize(13);
        tvInfo.setLineSpacing(4, 1);
        mid.addView(tvInfo);
        row.addView(mid);

        // 右侧 ⋮ 菜单
        TextView more = new TextView(this);
        more.setText("⋮");
        more.setTextColor(Color.WHITE);
        more.setTextSize(22);
        more.setGravity(Gravity.CENTER);
        more.setPadding(24, 24, 24, 24);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMenu(v, j, live); }
        });
        row.addView(more);

        parent.addView(row);
    }

    private void showMenu(View anchor, PlayerActivity.RecJob j, boolean live) {
        PopupMenu pm = new PopupMenu(this, anchor);
        if (live) {
            pm.getMenu().add("结束录制").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(android.view.MenuItem it) { PlayerActivity.recStop(j.id); rebuild(); return true; }
            });
        } else {
            pm.getMenu().add("开始").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(android.view.MenuItem it) { PlayerActivity.recContinue(j.id); rebuild(); return true; }
            });
        }
        pm.getMenu().add("播放").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) { play(j); return true; }
        });
        pm.getMenu().add("取消").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) { PlayerActivity.recCancel(j.id); rebuild(); return true; }
        });
        pm.show();
    }

    private void play(PlayerActivity.RecJob j) {
        try {
            if (j.file == null || !j.file.exists()) { Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show(); return; }
            Intent it = new Intent(this, PlayerActivity.class);
            it.putExtra("autoUrl", "file://" + j.file.getAbsolutePath());
            startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }
}
