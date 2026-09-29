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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, 20);
        TextView title = new TextView(this);
        title.setText("下载");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2);
        hlp.weight = 1;
        title.setLayoutParams(hlp);
        head.addView(title);
        TextView recNow = new TextView(this);
        recNow.setText("⏺ 录制当前流");
        recNow.setTextColor(Color.WHITE);
        recNow.setTextSize(14);
        recNow.setPadding(20, 12, 20, 12);
        recNow.setBackgroundColor(0xFF24485E);
        recNow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String u = PlayerActivity.lastStreamUrl;
                if (u == null || u.isEmpty() || !u.startsWith("http")) {
                    Toast.makeText(RecordActivity.this, "先在播放器里开播一条直播流", Toast.LENGTH_SHORT).show();
                    return;
                }
                PlayerActivity.startRecJob(u);
                rebuild();
                Toast.makeText(RecordActivity.this, "已开始录制", Toast.LENGTH_SHORT).show();
            }
        });
        head.addView(recNow);
        col.addView(head);

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
        long size = j.bytes;
        String info = "录制时长: " + fmtDur(secs)
            + "\n录制大小: " + fmtSize(size)
            + "\n录制状态: " + (live ? "录制中" : (j.state != null ? j.state : "暂停录制"));
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
            pm.getMenu().add("暂停").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(android.view.MenuItem it) { PlayerActivity.recStop(j.id); rebuild(); return true; }
            });
        } else {
            pm.getMenu().add("开始").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
                public boolean onMenuItemClick(android.view.MenuItem it) { PlayerActivity.recContinue(j.id); rebuild(); return true; }
            });
        }
        pm.getMenu().add("结束录制(转MP4)").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                PlayerActivity.recFinish(j.id);
                rebuild();
                return true;
            }
        });
        pm.getMenu().add("在VLC播放").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) { play(j); return true; }
        });
        pm.getMenu().add("复制下载地址").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", j.url != null ? j.url : ""));
                    Toast.makeText(RecordActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) { Toast.makeText(RecordActivity.this, "复制失败", Toast.LENGTH_SHORT).show(); }
                return true;
            }
        });
        pm.getMenu().add("打开所在目录").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) { openDir(); return true; }
        });
        pm.getMenu().add("取消").setOnMenuItemClickListener(new android.view.MenuItem.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) { PlayerActivity.recCancel(j.id); rebuild(); return true; }
        });
        pm.show();
    }

    private void openDir() {
        // 1) MT 管理器 OpenFileActivity（data+type 都给）
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setClassName("bin.mt.plus", "bin.mt.plus.OpenFileActivity");
            i.setDataAndType(android.net.Uri.parse("file:///storage/emulated/0/Movies/录制"), "resource/folder");
            i.putExtra("path", "/storage/emulated/0/Movies/录制");
            i.putExtra("com.bin.mt.plus.path", "/storage/emulated/0/Movies/录制");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        // 2) MT MainLightIcon
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setClassName("bin.mt.plus", "bin.mt.plus.MainLightIcon");
            i.setDataAndType(android.net.Uri.parse("file:///storage/emulated/0/Movies/录制"), "resource/folder");
            i.putExtra("path", "/storage/emulated/0/Movies/录制");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        // 3) 系统目录选择器，直接定位到 Movies/录制
        try {
            android.net.Uri dir = android.net.Uri.parse(
                "content://com.android.externalstorage.documents/document/primary:Movies/录制");
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, dir);
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        // 兜底：拉起 MT 主界面
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("bin.mt.plus");
            if (i != null) { startActivity(i); Toast.makeText(this, "进 Movies/录制 目录", Toast.LENGTH_LONG).show(); return; }
        } catch (Throwable ignored) {}
        Toast.makeText(this, "请到 Movies/录制 目录查看", Toast.LENGTH_LONG).show();
    }

    private void play(PlayerActivity.RecJob j) {
        try {
            if (j.storeUri == null) { Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show(); return; }
            Intent it = new Intent(this, PlayerActivity.class);
            it.putExtra("autoUrl", j.storeUri.toString());
            startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }
}
