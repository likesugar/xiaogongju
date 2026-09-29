package com.wink.pillmate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.util.ArrayList;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** VLC 播放器：官方 VLC-Android 播放路径 + DKVideoPlayer 风格控制条 */
public class PlayerActivity extends Activity {

    /** 抖哔面板解析出地址后回传开播 */
    public interface ParseSink { void onParsed(String url); }
    public static volatile ParseSink parseSink;

    private LibVLC libVLC;
    private MediaPlayer player;
    private VLCVideoLayout videoLayout;
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
                    long pos = player.getTime(), len = player.getLength();
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
        sCtx = this;
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
        // libvlc AAR 引用 org.videolan.R，运行时映射到本应用同名资源（必须在 setContentView 前）
        org.videolan.R.layout.vlc_video_layout = com.wink.pillmate.R.layout.vlc_video_layout;
        org.videolan.R.layout.player_remote = com.wink.pillmate.R.layout.player_remote;
        org.videolan.R.color.black = com.wink.pillmate.R.color.black;
        org.videolan.R.id.player_surface_frame = com.wink.pillmate.R.id.player_surface_frame;
        org.videolan.R.id.surface_video = com.wink.pillmate.R.id.surface_video;
        org.videolan.R.id.surface_stub = com.wink.pillmate.R.id.surface_stub;
        org.videolan.R.id.surface_subtitles = com.wink.pillmate.R.id.surface_subtitles;
        org.videolan.R.id.subtitles_surface_stub = com.wink.pillmate.R.id.subtitles_surface_stub;
        org.videolan.R.id.texture_video = com.wink.pillmate.R.id.texture_video;
        org.videolan.R.id.texture_stub = com.wink.pillmate.R.id.texture_stub;
        org.videolan.R.id.remote_player_surface = com.wink.pillmate.R.id.remote_player_surface;
        org.videolan.R.id.remote_player_surface_frame = com.wink.pillmate.R.id.remote_player_surface_frame;
        org.videolan.R.id.remote_subtitles_surface = com.wink.pillmate.R.id.remote_subtitles_surface;
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
            videoLayout = findViewById(R.id.videoLayout);
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
            } else {
                String last = getSharedPreferences("settings", MODE_PRIVATE).getString("lastUrl", null);
                if (last != null && !last.isEmpty()) {
                    setPlayState("恢复上次直播: " + last);
                    play(Uri.parse(last));
                }
            }
        } catch (Throwable t) {
            showError("初始化失败", t);
        }
    }

    /** 二次解析修复：已有播放器实例时复用，原地换流（不叠新实例） */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        try {
            String autoUrl = intent.getStringExtra("autoUrl");
            if (autoUrl != null && !autoUrl.isEmpty()) {
                setPlayState("直播代理模式: " + autoUrl);
                play(android.net.Uri.parse(autoUrl));
            }
        } catch (Throwable t) {
            showError("换流失败", t);
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
            public void onClick(View v) { startActivity(new Intent(PlayerActivity.this, RecordActivity.class)); }
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
                        if (player != null) player.setRate(sp);
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
                    if (player != null && player.getLength() > 0)
                        player.setTime(sb.getProgress() * player.getLength() / 1000);
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
        if (libVLC == null) {
            libVLC = new LibVLC(this, officialArgs());
        }
        if (player == null) {
            player = new MediaPlayer(libVLC);
            player.setEventListener(new MediaPlayer.EventListener() {
                public void onEvent(MediaPlayer.Event e) {
                    switch (e.type) {
                        case MediaPlayer.Event.Playing: hideLoading(); setPlayState("▶ 播放中"); break;
                        case MediaPlayer.Event.Paused: hideLoading(); showMsg("⏸ 暂停"); break;
                        case MediaPlayer.Event.Buffering:
                            setPlayState("缓冲 " + (int) e.getBuffering() + "%");
                            if (e.getBuffering() < 100) showLoading(); else hideLoading();
                            break;
                        case MediaPlayer.Event.EndReached: hideLoading(); showMsg("⏹ 播放结束"); break;
                        case MediaPlayer.Event.EncounteredError: hideLoading(); setPlayState("❌ 解码/打开错误: " + currentMediaUrl); break;
                    }
                }
            });
            player.attachViews(videoLayout, null, true, false);
            // 保持比例（不拉伸）；全出血窗口下遮幅边为纯黑
            player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        }
    }

    /** 官方 VLCOptions.getLibVLCOptions 默认值子集 */
    private ArrayList<String> officialArgs() {
        ArrayList<String> options = new ArrayList<String>();
        // 直播 HLS（1 秒分片滑动窗口）需要加大缓存，否则秒挂
        options.add("--network-caching=3000");
        options.add("--live-caching=3000");
        options.add("--audio-time-stretch");
        options.add("--avcodec-skiploopfilter");
        options.add("1");
        options.add("--avcodec-skip-frame");
        options.add("0");
        options.add("--avcodec-skip-idct");
        options.add("0");
        options.add("--audio-resampler");
        options.add("soxr");
        options.add("--freetype-rel-fontsize");
        options.add("16");
        options.add("--sout-keep");
        options.add("-vv");
        String dir = getDir("vlc", MODE_PRIVATE).getAbsolutePath();
        options.add("--keystore");
        options.add("file_crypt,none");
        options.add("--keystore-file");
        options.add(new File(dir, "keystore").getAbsolutePath());
        return options;
    }

    private java.io.File bridgeFile = null;




    // ---------- 直播录制（纯 Java 拉流写文件，支持多路并行） ----------
    static class RecJob {
        int id;
        volatile boolean active = true;
        volatile boolean paused = false;
        volatile java.net.HttpURLConnection conn;
        Thread thread;
        String name;
        String url;
        java.io.File file;
        android.net.Uri storeUri;
        volatile long bytes = 0;
        volatile boolean finishNow = false;
        volatile String state = null;   // null=暂停录制 / 转换MP4中 / 转换失败
        int notifId;
        volatile long secs = 0;
        volatile long startTs = 0;
    }
    public static final java.util.concurrent.ConcurrentHashMap<Integer, RecJob> recJobs =
        new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<Integer, RecJob> stoppedJobs =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int recSeq = 0;
    private static android.os.PowerManager.WakeLock recWake = null;
    private static Context sCtx;
    public static volatile String lastStreamUrl = null;

    private static void acquireWake() {
        try {
            if (recWake == null && sCtx != null) {
                android.os.PowerManager pm = (android.os.PowerManager) sCtx.getSystemService(Context.POWER_SERVICE);
                recWake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pillmate:rec");
                recWake.acquire(4 * 3600 * 1000L);
            }
        } catch (Throwable ignored) {}
    }

    private static void releaseWakeIfIdle() {
        try {
            if (recJobs.isEmpty() && recWake != null) { recWake.release(); recWake = null; }
        } catch (Throwable ignored) {}
    }

    private void toggleRec() {
        String u = currentMediaUrl;
        if (u == null || u.isEmpty() || "-".equals(u) || !u.startsWith("http")) {

            return;
        }
        startRecJob(u);
    }

    static void startRecJob(final String url) {
        RecJob job = new RecJob();
        job.id = ++recSeq;
        job.notifId = 9000 + job.id;
        job.url = url;
        String name;
        try {
            String lu = url.toLowerCase();
            String ext = lu.contains(".flv") ? "flv" : "mp4";
            name = "录制_" + new java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + "_" + job.id + "." + ext;
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "mp4".equals(ext) ? "video/mp4" : "video/x-flv");
            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/录制");
            job.storeUri = sCtx.getContentResolver().insert(
                android.provider.MediaStore.Video.Media.getContentUri("external_primary"), cv);
            if (job.storeUri == null) return;
        } catch (Throwable t) { return; }
        job.name = name;
        startPull(job, false);
    }

    private static void startPull(final RecJob job, final boolean append) {
        job.active = true;
        job.paused = false;
        job.startTs = System.currentTimeMillis();
        recJobs.put(job.id, job);
        stoppedJobs.remove(job.id);
        acquireWake();
        updateRecNote();
        job.thread = new Thread(new Runnable() {
            public void run() {
                java.io.FileOutputStream fo = null;
                boolean ended = false;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(job.url).openConnection();
                    job.conn = c;
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    String lu = job.url.toLowerCase();
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    c.setRequestProperty("Referer", lu.contains("bilibili") ? "https://www.bilibili.com/" : "https://live.douyin.com/");
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    java.io.InputStream in = c.getInputStream();
                    java.io.OutputStream os = sCtx.getContentResolver().openOutputStream(job.storeUri, append ? "wa" : "w");
                    fo = (os instanceof java.io.FileOutputStream) ? (java.io.FileOutputStream) os : null;
                    java.io.OutputStream out = fo != null ? fo : os;
                    byte[] b = new byte[32768]; int n;
                    while ((n = in.read(b)) > 0 && job.active && !job.paused) { out.write(b, 0, n); job.bytes += n; }
                    try { out.close(); } catch (Exception ignored) {} fo = null;
                    try { in.close(); } catch (Exception ignored) {}
                    try { c.disconnect(); } catch (Throwable ignored) {}
                    ended = !job.paused;
                } catch (Throwable t) {
                } finally {
                    try { if (fo != null) fo.close(); } catch (Exception ignored) {}
                    try { if (job.conn != null) job.conn.disconnect(); } catch (Throwable ignored) {}
                    job.conn = null;
                    if (job.startTs > 0) job.secs += (System.currentTimeMillis() - job.startTs) / 1000;
                    job.startTs = 0;
                    job.active = false;
                    recJobs.remove(job.id);
                    updateRecNote();
                    releaseWakeIfIdle();
                    try {   // 收尾：解除 pending，让系统文件管理器可见
                        android.content.ContentValues cv = new android.content.ContentValues();
                        cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                        sCtx.getContentResolver().update(job.storeUri, cv, null, null);
                    } catch (Throwable ignored) {}
                    if (job.finishNow) {
                        stoppedJobs.put(job.id, job);
                        convertToMp4(job);
                    } else {
                        job.state = null;
                        stoppedJobs.put(job.id, job);   // 暂停/断流：可继续
                    }
                }
            }
        });
        job.thread.start();
    }

    /** 停止（暂停）录制：断流、通知取消，文件保留可继续 */
    public static void recStop(int jid) {
        RecJob job = recJobs.get(jid);
        if (job == null) return;
        job.paused = true;
        job.active = false;
        try { if (job.conn != null) job.conn.disconnect(); } catch (Throwable ignored) {}
        try { if (job.thread != null) job.thread.interrupt(); } catch (Throwable ignored) {}
    }

    /** 继续：同一路重新拉流，追加写入同一文件 */
    public static void recContinue(int jid) {
        RecJob job = stoppedJobs.get(jid);
        if (job == null) return;
        startPull(job, true);
    }

    /** 结束录制：合并转封装成 mp4（后台执行），完成后从列表移除 */
    public static void recFinish(int jid) {
        RecJob live = recJobs.get(jid);
        if (live != null) {
            live.finishNow = true;
            recStop(jid);
            return;
        }
        final RecJob job = stoppedJobs.get(jid);
        if (job == null) return;
        stoppedJobs.remove(job.id);
        new Thread(new Runnable() { public void run() { convertToMp4(job); } }).start();
    }

    static void convertToMp4(final RecJob job) {
        convertToMp4(job, false);
    }

    static void convertToMp4(final RecJob job, final boolean reencode) {
        job.state = "转换MP4中…";
        try {
            String src = job.storeUri != null
                ? com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(sCtx, job.storeUri)
                : job.file.getAbsolutePath();
            java.io.File tmp = new java.io.File(sCtx.getCacheDir(), "conv_" + System.currentTimeMillis() + ".mp4");
            // KB 抓取流时间戳跳变严重，copy 会被 MP4 截断 → 重编码重生时间戳（保证全长顺滑）
            String[] args = reencode
                ? new String[]{"-y", "-fflags", "+genpts", "-i", src,
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "23",
                    "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", tmp.getAbsolutePath()}
                : new String[]{"-y", "-i", src, "-c", "copy", "-movflags", "+faststart", tmp.getAbsolutePath()};
            com.arthenica.ffmpegkit.FFmpegSession st = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(args);
            if (st.getState().equals(com.arthenica.ffmpegkit.SessionState.COMPLETED) && tmp.length() > 0) {
                String name = job.name != null ? job.name : "rec.mp4";
                String mp4Name = (name.endsWith(".flv") ? name.substring(0, name.length() - 4) : name) + ".mp4";
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, mp4Name);
                cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/录制");
                cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1);
                android.net.Uri out = sCtx.getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.getContentUri("external_primary"), cv);
                java.io.InputStream in = new java.io.FileInputStream(tmp);
                java.io.OutputStream os = sCtx.getContentResolver().openOutputStream(out);
                byte[] b = new byte[32768]; int n;
                while ((n = in.read(b)) > 0) os.write(b, 0, n);
                os.close(); in.close();
                cv.clear();
                cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                sCtx.getContentResolver().update(out, cv, null, null);
                try { sCtx.getContentResolver().delete(job.storeUri, null, null); } catch (Throwable ignored) {}
            }
            tmp.delete();
            stoppedJobs.remove(job.id);   // 成功：从列表移除
        } catch (Throwable t) {
            job.state = "转换失败(保留flv)";
        }
    }

    /** 彻底取消：删文件、从列表移除 */
    public static void recCancel(int jid) {
        RecJob j = recJobs.get(jid);
        if (j != null) recStop(jid);
        RecJob job = (j != null) ? j : stoppedJobs.get(jid);
        if (job == null) return;
        stoppedJobs.remove(job.id);
        try { if (job.storeUri != null) sCtx.getContentResolver().delete(job.storeUri, null, null); } catch (Throwable ignored) {}
    }

    private void stopAllRec() {
        for (Integer id : recJobs.keySet().toArray(new Integer[0])) recStop(id);
    }

    private static void cancelRecNote(int id) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) sCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(id);
        } catch (Throwable ignored) {}
    }

    /** 网页解析 KB 抓取任务（挂在下载页显示） */
    public static RecJob kbJob = null;

    static void registerKb(String name, java.io.File f) {
        RecJob job = new RecJob();
        job.id = ++recSeq;
        job.name = name;
        job.file = f;
        job.notifId = -1;
        kbJob = job;
    }

    /** KB 结束录制：PTS 重建 + 转 MP4 入 Movies/录制 */
    public static void kbFinish() {
        final RecJob job = kbJob;
        if (job == null) return;
        new Thread(new Runnable() { public void run() {
            try { SnifferActivity.stopKb(); } catch (Throwable ignored) {}
            // 等待 stopKb 的异步 PTS 重建（fix_tmp.ts）完成，最多 20s
            try {
                java.io.File fix = new java.io.File(SnifferActivity.kbDir.getParentFile(), "fix_tmp.ts");
                for (int i = 0; i < 60 && fix.exists(); i++) Thread.sleep(300);
            } catch (Throwable ignored) {}
            convertToMp4(job, true);   // KB 流必须重编码
            if (kbJob == job) kbJob = null;
        }}).start();
    }

    /** 单条录制总通知（含 KB 抓取）：N路 · 点击进入 VLC 播放器 */
    static int recNoteCount() {
        return recJobs.size() + (SnifferActivity.kbState[0] == 1 ? 1 : 0);
    }

    static android.app.Notification buildRecNote(Context ctx) {
        android.app.NotificationChannel ch = new android.app.NotificationChannel("rec", "直播录制", android.app.NotificationManager.IMPORTANCE_LOW);
        android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
        return new android.app.Notification.Builder(ctx, "rec")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("小工具 · 录制中 " + recNoteCount() + " 路")
            .setContentText("点击进入 VLC 播放器")
            .setOngoing(true)
            .setContentIntent(android.app.PendingIntent.getActivity(ctx, 9100,
                new Intent(ctx, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build();
    }

    public static void updateRecNote() {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) sCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (recNoteCount() == 0) { nm.cancel(9100); return; }
            nm.notify(9100, buildRecNote(sCtx));
        } catch (Throwable ignored) {}
    }

    /** 录制通知：点击 → 回到 VLC 播放器；通知栏不做停止 */
    private static void showRecNote(int id, String title, String text) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) sCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            android.app.NotificationChannel ch = new android.app.NotificationChannel("rec", "直播录制", android.app.NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            android.app.Notification nt = new android.app.Notification.Builder(sCtx, "rec")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(android.app.PendingIntent.getActivity(sCtx, id,
                    new Intent(sCtx, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
            nm.notify(id, nt);
        } catch (Throwable ignored) {}
    }

    private static boolean recRxRegistered = false;
    private android.content.BroadcastReceiver stopRecReceiver = new android.content.BroadcastReceiver() {
        public void onReceive(android.content.Context ctx, android.content.Intent i) {
            int jid = i.getIntExtra("jid", -1);
            if (jid > 0) recStop(jid);
        }
    };

    private void play(Uri uri) {
        // 直连播放
        currentMediaUrl = uri.toString();
        lastStreamUrl = uri.toString();
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("lastUrl", uri.toString()).apply();
        currentUrl = uri.toString();
        setPlayState("开始播放: " + uri);
        try {
            // 换流 → 销毁重建整个播放器实例（等价“退出重进”，根治换流黑屏）
            boolean switching = currentMediaUrl != null && !"-".equals(currentMediaUrl)
                && !currentMediaUrl.equals(uri.toString());
            if (switching && player != null) {
                try { player.stop(); } catch (Throwable ignored) {}
                try { if (player.getVLCVout().areViewsAttached()) player.detachViews(); } catch (Throwable ignored) {}
                try { player.release(); } catch (Throwable ignored) {}
                player = null;
                try { libVLC.release(); } catch (Throwable ignored) {}
                libVLC = null;
            }
            ensurePlayer();
            player.stop();
            Media m;
            String scheme = uri.getScheme();
            if ("content".equals(scheme) || "file".equals(scheme)) {
                if (pfd != null) { try { pfd.close(); } catch (Exception ignored) {} }
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                m = new Media(libVLC, pfd.getFileDescriptor());
            } else {
                m = new Media(libVLC, uri);
                m.setHWDecoderEnabled(true, true);
                m.addOption(":no-mediacodec-dr");
                m.addOption(":no-omxil-dr");
            }
            player.setMedia(m);
            m.release();
            setPlayState("已装载媒体，启动播放…");
            player.play();
            showController();
        } catch (Throwable t) {
            showError("播放失败", t);
        }
    }

    private void showMsg(final String msg) {
        setPlayState(msg);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 默认退到后台就暂停；开了后台播放模式则继续出声
        if (!backgroundMode && recJobs.isEmpty() && player != null) {
            try { player.pause(); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从别的页面回来：重新挂视频 Surface 并继续播（治返回黑屏）
        try {
            if (player != null && currentMediaUrl != null && !"-".equals(currentMediaUrl)) {
                if (!player.getVLCVout().areViewsAttached()) {
                    player.attachViews(videoLayout, null, true, false);
                }
                player.play();
            }
        } catch (Throwable ignored) {}
    }

    private void showError(String title, Throwable t) {
        StringBuilder sb = new StringBuilder(title + "\n" + t + "\n");
        Throwable c = t.getCause();
        while (c != null) {
            sb.append("Caused by: ").append(c).append("\n");
            c = c.getCause();
        }

        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(Color.BLACK);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setPadding(24, 24, 24, 24);
        sv.addView(tv);
        setContentView(sv);
    }

    @Override
    protected void onDestroy() {
        parseSink = null;   // 录制任务继续在后台跑，通知栏可停
        super.onDestroy();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(fadeOut);
        if (player != null) {
            try { if (player.getVLCVout().areViewsAttached()) player.detachViews(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        if (pfd != null) { try { pfd.close(); } catch (Exception ignored) {} pfd = null; }
        if (libVLC != null) {
            try { libVLC.release(); } catch (Throwable ignored) {}
            libVLC = null;
        }
    }
}
