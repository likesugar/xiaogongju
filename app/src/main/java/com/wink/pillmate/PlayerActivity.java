package com.wink.pillmate;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import android.view.SurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;





import java.io.File;
import java.util.ArrayList;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** VLC 播放器：Media3(ExoPlayer) 播放内核 + DKVideoPlayer 风格控制条 */
public class PlayerActivity extends Activity {

    /** 抖哔面板解析出地址后回传开播 */
    public interface ParseSink { void onParsed(String url); }
    public static volatile ParseSink parseSink;


    private ExoPlayer player;
    private final androidx.media3.datasource.DefaultHttpDataSource.Factory httpFactory =
        new androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile")
            .setAllowCrossProtocolRedirects(true);
    private SurfaceView videoSurface;
    private ParcelFileDescriptor pfd;

    private SeekBar seek;
    private ProgressBar bottomProgress;
    private TextView tvTime, tvTotal, btnToggle;
    private View btnBg;

    private View loading;
    private TextView tvSpeed;
    private LinearLayout speedMenu;
    private LinearLayout speedList;
    private float curSpeed = 1.0f;

    private boolean proxyOn = false;
    private static final java.util.regex.Pattern MEDIA_URL = java.util.regex.Pattern.compile(
        "\\.(m3u8|ts|mp4|flv)(\\?|$)|/stream/|media-worker", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String FRAME_JS =
        "(function(){if(window.__gdvP)return;window.__gdvP=1;" +
        "var n=0;var t=setInterval(function(){n++;if(n>15){clearInterval(t);return;}" +
        "var v=document.querySelector('video');" +
        "if(v){try{v.muted=true;v.play().catch(function(e){});}catch(e){}}" +
        "var sels=['[class*=play]','[id*=play]','[class*=Play]','.vjs-big-play-button','video','[class*=player]'];" +
        "for(var i=0;i<sels.length;i++){try{var e=document.querySelector(sels[i]);if(e){e.click();}}catch(e2){}}" +
        "try{document.body.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));}catch(e3){}}" +
        "},800);})();";

    private String currentMediaUrl = "-";
    private String currentUrl = null;
    

    private void refreshSpeedMenu() {
        for (int i = 0; i < speedList.getChildCount(); i++) {
            TextView it = (TextView) speedList.getChildAt(i);
            boolean cur = it.getText().toString().equals(fmtSpeed(curSpeed));
            it.setTextColor(cur ? 0xFF39C5BB : 0xFFFFFFFF);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static String fmtSpeed(float sp) {
        return (sp == (int) sp ? String.valueOf((int) sp) : String.valueOf(sp)) + "X";
    }

    private boolean backgroundMode = false;
    private boolean fullscreen = false;
    private View headerBar, bottomBar, videoContainer, controlsOverlay;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        public void run() {
            try {
                if (player != null) {
                    long pos = player.getCurrentPosition(), len = player.getDuration();
                    tvTime.setText(fmt(pos));
                    tvTotal.setText(fmt(len));
                    int p = len > 0 ? (int) (pos * 1000 / len) : 0;
                    if (len > 0 && !seek.isPressed()) {
                        seek.setProgress(p);
                        seek.setEnabled(true);
                    } else if (len <= 0) {
                        seek.setEnabled(false);
                    }
                    if (controlsOverlay.getVisibility() != View.VISIBLE && !seek.isPressed())
                        bottomProgress.setProgress(p);
                    btnToggle.setText(player.isPlaying() ? "Ⅱ" : "▶");
                }
            } catch (Throwable ignored) {}
            handler.postDelayed(this, 500);
        }
    };

    private static String fmt(long ms) {
        if (ms <= 0 || ms == Long.MAX_VALUE) return "--:--";
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 崩溃日志落盘（crash.log），用于定位自动退出
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(
                        new java.io.File(getExternalFilesDir(null), "crash.log"), true);
                    fw.append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss ", java.util.Locale.US)
                        .format(new java.util.Date())).append(e.toString()).append("\n");
                    for (StackTraceElement se : e.getStackTrace()) fw.append("  at ").append(se.toString()).append("\n");
                    if (e.getCause() != null) fw.append("caused: ").append(e.getCause().toString()).append("\n");
                    fw.close();
                } catch (Throwable ignored) {}
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
        super.onCreate(savedInstanceState);
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(getExternalFilesDir(null), "crash.txt"), true);
                    fw.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                        + " " + android.util.Log.getStackTraceString(e) + "\n\n");
                    fw.close();
                } catch (Exception ignored) {}
                if (def != null) def.uncaughtException(t, e);
            }
        });
        try {
            setContentView(R.layout.activity_player);
        } catch (Throwable t) {
            showError("布局加载失败", t);
            return;
        }
        // 全出血窗口：铺满整屏含刘海/系统栏区域，背景纯黑（消除上下白边）
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            videoSurface = findViewById(R.id.videoLayout);
            seek = findViewById(R.id.seek);
            bottomProgress = findViewById(R.id.bottomProgress);
            tvTime = findViewById(R.id.tvTime);
            tvTotal = findViewById(R.id.tvTotal);
            btnToggle = findViewById(R.id.btnToggle);
            btnBg = findViewById(R.id.btnBg);
            tvPlayState = findViewById(R.id.tvPlayState);

            loading = findViewById(R.id.loading);
            headerBar = findViewById(R.id.headerBar);
            bottomBar = findViewById(R.id.bottomBar);
            videoContainer = findViewById(R.id.videoContainer);
            controlsOverlay = findViewById(R.id.controlsOverlay);
            bind();
            handler.post(tick);
            applyImmersive();
            LiveProxy.start();
            if (LiveProxy.mediaUrl == null) {
                LiveProxy.mediaUrl = getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("mediaUrl", null);
            }
            LiveProxy.startRefresher();
            mergeLeftoverTemps();
            String autoUrl = getIntent().getStringExtra("autoUrl");
            if (autoUrl != null && !autoUrl.isEmpty()) {
                setPlayState("直播代理模式: " + autoUrl);
                play(Uri.parse(autoUrl));
            }
        } catch (Throwable t) {
            showError("初始化失败", t);
        }
    }

    private void applyImmersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    // DK：控制条显示 4 秒后自动淡出（mDefaultTimeout=4000）
    private static final long CONTROLLER_TIMEOUT = 4000L;
    private final Runnable fadeOut = new Runnable() {
        public void run() {
            if (seek.isPressed()) { handler.postDelayed(this, CONTROLLER_TIMEOUT); return; }
            controlsOverlay.setVisibility(View.GONE);
            bottomProgress.setVisibility(View.VISIBLE);
        }
    };

    private void showController() {
        controlsOverlay.setVisibility(View.VISIBLE);
        bottomProgress.setVisibility(View.GONE);
        handler.removeCallbacks(fadeOut);
        handler.postDelayed(fadeOut, CONTROLLER_TIMEOUT);
    }

    private void hideController() {
        handler.removeCallbacks(fadeOut);
        controlsOverlay.setVisibility(View.GONE);
        bottomProgress.setVisibility(View.VISIBLE);
    }

    private void showLoading() { runOnUiThread(new Runnable() { public void run() { loading.setVisibility(View.VISIBLE); } }); }
    private void hideLoading() { runOnUiThread(new Runnable() { public void run() { loading.setVisibility(View.GONE); } }); }



    private android.widget.TextView tvPlayState;

    private void setPlayState(final String t) {
        runOnUiThread(new Runnable() { public void run() {
            if (tvPlayState != null) tvPlayState.setText(t);
        }});
    }

    private void mergeLeftoverTemps() {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "录制");
            java.io.File[] temps = dir.listFiles(new java.io.FilenameFilter() {
                public boolean accept(java.io.File d, String name) { return name.startsWith("rec_tmp_") && name.endsWith(".ts"); }
            });
            if (temps == null || temps.length == 0) return;
            java.util.Arrays.sort(temps);
            java.io.File out = new java.io.File(dir, "live_合并.ts");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out, true);
            for (java.io.File t : temps) {
                java.io.FileInputStream fis = new java.io.FileInputStream(t);
                byte[] b = new byte[65536]; int n;
                while ((n = fis.read(b)) > 0) fos.write(b, 0, n);
                fis.close();
                t.delete();
            }
            fos.close();
        } catch (Throwable ignored) {}
    }

    private void hideSysBar() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** 全屏：不动视频容器（Surface 重挂会黑屏），只隐藏底部栏 + 横屏 + 沉浸 */
    private void startFullScreen() {
        if (fullscreen) return;
        fullscreen = true;
        View bb = findViewById(R.id.bottomBar);
        if (bb != null) bb.setVisibility(View.GONE);
        View ps = findViewById(R.id.tvPlayState);
        if (ps != null) ps.setVisibility(View.GONE);
        hideSysBar();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void stopFullScreen() {
        if (!fullscreen) return;
        fullscreen = false;
        View bb = findViewById(R.id.bottomBar);
        if (bb != null) bb.setVisibility(View.VISIBLE);
        View ps = findViewById(R.id.tvPlayState);
        if (ps != null) ps.setVisibility(View.VISIBLE);
        applyImmersive();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void bind() {
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });

        if (!recRxRegistered) {
            recRxRegistered = true;
            getApplicationContext().registerReceiver(stopRecReceiver, new android.content.IntentFilter("pillmate_stop_rec"));
        }

        // 抖哔解析面板：出地址直接开播
        findViewById(R.id.btnDouchi).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                parseSink = new ParseSink() {
                    public void onParsed(String url) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                setPlayState("▶ 抖哔解析: " + url);
                                play(Uri.parse(url));
                            }
                        });
                    }
                };
                startActivity(new Intent(PlayerActivity.this, ParseActivity.class));
            }
        });

        findViewById(R.id.btnDl).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleRec(); }
        });

        // 点视频区 显示/隐藏 内置控制条（DK：显示后 4 秒自动淡出）
        videoContainer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (controlsOverlay.getVisibility() == View.VISIBLE) hideController();
                else showController();
            }
        });

        // 后台播放模式：开启后 Home 出去继续出声
        btnBg.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                backgroundMode = !backgroundMode;
                btnBg.setAlpha(backgroundMode ? 1f : 0.45f);
            }
        });

        // 倍速（B 站风格：右侧竖排菜单，当前倍速高亮）
        tvSpeed = findViewById(R.id.tvSpeed);
        speedMenu = findViewById(R.id.speedMenu);
        float[] speeds = {3.0f, 2.5f, 2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f, 0.25f};
        speedList = new LinearLayout(this);
        speedList.setOrientation(LinearLayout.VERTICAL);
        for (final float sp : speeds) {
            TextView item = new TextView(this);
            item.setText(fmtSpeed(sp));
            item.setTextColor(Color.WHITE);
            item.setTextSize(16);
            item.setPadding(36, 26, 36, 26);
            item.setMinHeight(dp(46));
            item.setGravity(android.view.Gravity.CENTER);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        if (player != null) player.setPlaybackSpeed(sp);
                        curSpeed = sp;
                        refreshSpeedMenu();
                        speedMenu.setVisibility(View.GONE);
                    } catch (Throwable t) { showError("倍速失败", t); }
                }
            });
            speedList.addView(item);
        }
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(speedList);
        speedMenu.addView(sv);
        tvSpeed.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (speedMenu.getVisibility() == View.VISIBLE) speedMenu.setVisibility(View.GONE);
                else { refreshSpeedMenu(); speedMenu.setVisibility(View.VISIBLE); }
            }
        });

        // 全屏：DK 横屏（跟随传感器正反向）；退出回竖屏；顶栏/底栏隐藏
        // DK 式全屏：播放器容器挂到 DecorView（真正只有视频），横屏
        findViewById(R.id.btnFull).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    if (!fullscreen) startFullScreen(); else stopFullScreen();
                } catch (Throwable t) { showError("全屏失败", t); }
            }
        });

        findViewById(R.id.btnSniff).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivityForResult(new Intent(PlayerActivity.this, SnifferActivity.class), 2); }
                catch (Throwable t) { showError("打开解析失败", t); }
            }
        });

        findViewById(R.id.btnPick).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    startActivityForResult(i, 1);
                } catch (Throwable t) {
                    showError("选择文件失败", t);
                }
            }
        });

        btnToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    if (player == null) return;
                    if (player.isPlaying()) player.pause();
                    else player.play();
                } catch (Throwable t) { showError("切换失败", t); }
                showController();
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {}
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                try {
                    if (player != null && player.getDuration() > 0)
                        player.seekTo(sb.getProgress() * player.getDuration() / 1000);
                } catch (Throwable ignored) {}
                showController();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 2 && res == RESULT_OK && data != null) {
            String url = data.getStringExtra("url");
            setPlayState("收到嗅探地址: " + url);
            if (url != null && !url.isEmpty()) {
                                play(Uri.parse(url));
            }
            return;
        }
        if (req == 1 && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                                play(uri);
            } catch (Throwable t) {
                showError("播放失败", t);
            }
        }
    }

    private void ensurePlayer() {
        if (player == null) {
            player = new ExoPlayer.Builder(this,
                new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory)).build();
            player.addListener(new Player.Listener() {
                public void onPlaybackStateChanged(int st) {
                    if (st == Player.STATE_BUFFERING) showLoading();
                    else if (st == Player.STATE_READY) hideLoading();
                    else if (st == Player.STATE_ENDED) { hideLoading(); setPlayState("⏹ 播放结束"); }
                }
                public void onPlayerError(PlaybackException e) {
                    hideLoading();
                    setPlayState("❌ 播放错误: " + currentMediaUrl + " / " + e.getMessage());
                }
                public void onIsPlayingChanged(boolean ip) {
                    btnToggle.setText(ip ? "Ⅱ" : "▶");
                }
            });
            player.setVideoSurfaceView(videoSurface);
        }
    }






    // ---------- 直播录制（纯 Java 拉流写文件，支持多路并行） ----------
    private static class RecJob {
        volatile boolean active = true;
        volatile java.net.HttpURLConnection conn;
        Thread thread;
        String name;
        java.io.File file;
        int notifId;
    }
    private static final java.util.concurrent.ConcurrentHashMap<Integer, RecJob> recJobs =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int recSeq = 0;
    private static android.os.PowerManager.WakeLock recWake = null;

    private void acquireWake() {
        try {
            if (recWake == null) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
                recWake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pillmate:rec");
                recWake.acquire(4 * 3600 * 1000L);
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeIfIdle() {
        try {
            if (recJobs.isEmpty() && recWake != null) { recWake.release(); recWake = null; }
        } catch (Throwable ignored) {}
    }

    private void showError(String title, Throwable t) {
        StringBuilder sb = new StringBuilder(title + "\n" + t + "\n");
        Throwable c = t.getCause();
        while (c != null) { sb.append("Caused by: ").append(c).append("\n"); c = c.getCause(); }
        setPlayState(sb.toString());
    }

    private void toggleRec() {
        String u = currentMediaUrl;
        if (u == null || u.isEmpty() || "-".equals(u) || !u.startsWith("http")) {
            
            return;
        }
        startRecJob(u);
    }

    private RecJob startRecJob(final String url) {
        final RecJob job = new RecJob();
        final int jid = ++recSeq;
        job.notifId = 9000 + jid;
        recJobs.put(jid, job);
        acquireWake();
        final String lu = url.toLowerCase();
        try {
            java.io.File dir = new java.io.File(getApplicationContext().getExternalFilesDir(null), "录制");
            if (!dir.exists()) dir.mkdirs();
            String ext = lu.contains(".flv") ? "flv" : "mp4";
            job.file = new java.io.File(dir, "录制_" + new java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + "_" + jid + "." + ext);
        } catch (Throwable t) { return null; }
        job.name = job.file.getName();
        LiveProxy.liveFile = job.file.getAbsolutePath();
        new Thread(new Runnable() {
            public void run() {
                java.io.FileOutputStream fo = null;
                try {
                    showRecNote(job.notifId, "● 录制中 " + jid, job.name + "（点此停止）", jid);
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    job.conn = c;
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    String ref = lu.contains("bilibili") ? "https://www.bilibili.com/" : "https://live.douyin.com/";
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    c.setRequestProperty("Referer", ref);
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    java.io.InputStream in = c.getInputStream();
                    fo = new java.io.FileOutputStream(job.file);
                    byte[] b = new byte[32768];
                    long total = 0;
                    int n;
                    while ((n = in.read(b)) > 0 && job.active) {
                        fo.write(b, 0, n);
                        total += n;
                    }
                    fo.close(); in.close();
                    try { c.disconnect(); } catch (Throwable ignored) {}
                    final long sz = total;
                    final String fname = job.name;
                } catch (Throwable t) {
                    try { if (fo != null) fo.close(); } catch (Exception ignored) {}
                    try { if (job.conn != null) job.conn.disconnect(); } catch (Exception ignored) {}
                    final String msg = t.getMessage();
                } finally {
                    job.active = false;
                    recJobs.remove(jid);
                    try { nmCancel(job.notifId); } catch (Throwable ignored) {}
                    releaseWakeIfIdle();
                    // 转封装 flv→mp4（-c copy 秒级），获得可拖动快进的文件
                    if (job.file != null && job.file.length() > 0 && job.file.getName().endsWith(".flv")) {
                        try {
                            String mp4 = job.file.getAbsolutePath().replace(".flv", ".mp4");
                            com.arthenica.ffmpegkit.FFmpegSession st = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                                new String[]{"-y", "-i", job.file.getAbsolutePath(), "-c", "copy",
                                    "-movflags", "+faststart", mp4});
                            if (st.getState().equals(com.arthenica.ffmpegkit.SessionState.COMPLETED)
                                && new java.io.File(mp4).length() > 0) {
                                job.file.delete();
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }).start();
        return job;
        
    }

    private void stopJob(int jid) {
        RecJob job = recJobs.get(jid);
        if (job != null) {
            job.active = false;
            try { if (job.conn != null) job.conn.disconnect(); } catch (Throwable ignored) {}
            try { if (job.thread != null) job.thread.interrupt(); } catch (Throwable ignored) {}
        }
    }

    private void stopAllRec() {
        for (Integer id : recJobs.keySet().toArray(new Integer[0])) stopJob(id);
    }

    private void nmCancel(int id) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.cancel(id);
        } catch (Throwable ignored) {}
    }

    private void showRecNote(int id, String title, String text, int jid) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            android.app.NotificationChannel ch = new android.app.NotificationChannel("rec", "直播录制", android.app.NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            android.app.Notification nt = new android.app.Notification.Builder(this, "rec")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(android.app.PendingIntent.getBroadcast(this, jid,
                    new android.content.Intent("pillmate_stop_rec").setPackage(getPackageName()).putExtra("jid", jid),
                    android.app.PendingIntent.FLAG_IMMUTABLE))
                .build();
            nm.notify(id, nt);
        } catch (Throwable ignored) {}
    }

    private static boolean recRxRegistered = false;
    private android.content.BroadcastReceiver stopRecReceiver = new android.content.BroadcastReceiver() {
        public void onReceive(android.content.Context ctx, android.content.Intent i) {
            int jid = i.getIntExtra("jid", -1);
            if (jid > 0) stopJob(jid);
        }
    };


    // ---------- 实时播放：录制(拉流写文件) + 尾随播放同一文件 ----------
    private void playFlvLive(final String url) {
        RecJob job = startRecJob(url);
        if (job == null) { showError("录制启动失败", new Exception("job null")); return; }
        LiveProxy.liveFile = job.file.getAbsolutePath();
        setPlayState("实时播放录制流: " + job.name);
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(2000); } catch (Exception ignored) {}
                runOnUiThread(new Runnable() {
                    public void run() {
                        play(Uri.parse("http://127.0.0.1:8123/live.flv"));
                    }
                });
            }
        }).start();
    }

    /** 直连播放 */
    private void play(Uri uri) {
        currentMediaUrl = uri.toString();
        currentUrl = uri.toString();
        setPlayState("开始播放: " + uri);
        try {
            String bl = uri.toString().toLowerCase();
            if ((bl.contains(".flv") || bl.contains("douyincdn")) && !"127.0.0.1".equals(uri.getHost())) {
                playFlvLive(uri.toString());
                return;
            }
            ensurePlayer();
            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();
            showController();
        } catch (Throwable t) {
            showError("播放失败", t);
        }
    }

}
