package com.wink.pillmate;

import android.app.Activity;
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
            public void onClick(View v) {
                try {
                    String u = (flvOriginalUrl != null) ? flvOriginalUrl : currentMediaUrl;
                    if (u == null || u.isEmpty() || "-".equals(u) || !u.startsWith("http")) {
                        android.widget.Toast.makeText(PlayerActivity.this, "还没有可下载的流", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 直播 flv → ffmpeg 直播录制到 files/录制
                    String lu = u.toLowerCase();
                    if (lu.contains(".flv") || lu.contains("douyincdn")) {
                        toggleLiveRecord(u);
                        return;
                    }
                    String fname = "pillmate_视频_" + System.currentTimeMillis() / 1000 + ".mp4";
                    android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(u));
                    req.setTitle(fname);
                    req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_MOVIES, fname);
                    req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    dm.enqueue(req);
                    android.widget.Toast.makeText(PlayerActivity.this, "已开始下载到 Movies", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    android.widget.Toast.makeText(PlayerActivity.this, "下载失败: " + t.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            }
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

    /** 硬解开但禁直渲染（官方 HW_ACCELERATION_DECODING 档），治有声无画 */
    private com.arthenica.ffmpegkit.FFmpegSession flvBridge = null;
    private int udpPort = 0;
    private java.io.File bridgeFile = null;
    private String flvOriginalUrl = null;
    private com.arthenica.ffmpegkit.FFmpegSession recSession = null;
    private String recFilePath = null;

    /** 直播录制开关：ffmpeg -c copy 存到 files/录制/ */
    private void toggleLiveRecord(String url) {
        if (recSession != null) {
            try { recSession.cancel(); } catch (Throwable ignored) {}
            recSession = null;
            android.widget.Toast.makeText(this, "已停止录制: " + recFilePath, Toast.LENGTH_LONG).show();
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.cancel(9001);
            } catch (Throwable ignored) {}
            recFilePath = null;
            return;
        }
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "录制");
            if (!dir.exists()) dir.mkdirs();
            String name = "录制_" + new java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + ".flv";
            java.io.File out = new java.io.File(dir, name);
            String headers = "Referer: https://live.douyin.com/\r\nUser-Agent: Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile\r\n";
            String[] cmd = {"-hide_banner", "-loglevel", "error",
                "-headers", headers,
                "-i", url,
                "-c", "copy",
                "-y", out.getAbsolutePath()};
            recFilePath = out.getAbsolutePath();
            final String fname = name;
            try {
                android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                android.app.NotificationChannel ch = new android.app.NotificationChannel("rec", "直播录制", android.app.NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
                android.app.Notification nt = new android.app.Notification.Builder(this, "rec")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("● 录制中")
                    .setContentText(fname)
                    .setOngoing(true)
                    .build();
                nm.notify(9001, nt);
            } catch (Throwable ignored) {}
            new Thread(new Runnable() {
                public void run() {
                    recSession = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(cmd);
                }
            }).start();
            android.widget.Toast.makeText(this, "开始录制 → 录制/" + fname + "（再点下载=停止）", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "录制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** h265-in-flv VLC 解不动：ffmpeg-kit 转 TS 推本地 UDP，VLC 播 UDP */
    private void playFlvViaBridge(String url) {
        // 同一链接已在桥接中 → 直接复用现有 TS 流，避免重启黑屏
        if (flvBridge != null && !flvBridge.getState().equals(com.arthenica.ffmpegkit.SessionState.COMPLETED)
            && !flvBridge.getState().equals(com.arthenica.ffmpegkit.SessionState.FAILED)
            && url.equals(flvOriginalUrl)) {
            play(Uri.parse("http://127.0.0.1:8123/live.ts"));
            return;
        }
        stopFlvBridge();
        flvOriginalUrl = url;
        try {
            bridgeFile = new java.io.File(getCacheDir(), "bridge_" + System.currentTimeMillis() + ".ts");
            LiveProxy.liveTsBytes = 0;
            LiveProxy.tsPipe = bridgeFile.getAbsolutePath();
            String headers = "Referer: https://live.douyin.com/\r\nUser-Agent: Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile\r\n";
            String[] cmd = {"-hide_banner", "-loglevel", "info",
                "-headers", headers,
                "-i", url,
                "-c", "copy", "-f", "mpegts", bridgeFile.getAbsolutePath()};
            setPlayState("桥接转封装中…");
            new Thread(new Runnable() {
                public void run() {
                    flvBridge = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(cmd);
                }
            }).start();
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(2500); } catch (Exception ignored) {}
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (LiveProxy.liveTsBytes == 0 && flvBridge != null) {
                                String lg = flvBridge.getAllLogsAsString();
                                setPlayState("桥接无数据: " + (lg.length() > 200 ? lg.substring(lg.length() - 200) : lg));
                            } else {
                                play(Uri.parse("http://127.0.0.1:8123/live.ts"));
                            }
                        }
                    });
                }
            }).start();
        } catch (Throwable t) {
            showError("桥接失败", t);
        }
    }

    private void stopFlvBridge() {
        if (flvBridge != null) {
            try { flvBridge.cancel(); } catch (Throwable ignored) {}
            flvBridge = null;
        }
        LiveProxy.tsPipe = null;
        bridgeFile = null;
    }

    private void play(Uri uri) {
        // flv（含 h265）→ 桥接成 TS 再播
        if (!"udp".equals(uri.getScheme()) && !"file".equals(uri.getScheme())
            && !"content".equals(uri.getScheme())) {
            String lu = uri.toString().toLowerCase();
            if (lu.contains(".flv") || lu.contains("douyincdn")) {
                playFlvViaBridge(uri.toString());
                return;
            }
        }
        currentMediaUrl = uri.toString();
        currentUrl = uri.toString();
        setPlayState("开始播放: " + uri);
        try {
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
                boolean local = uri.getHost() != null && uri.getHost().contains("127.0.0.1");
                if (local) {
                    // 本地桥接 TS(h265)：纯软解，硬解直渲染黑屏
                    m.setHWDecoderEnabled(false, false);
                    m.addOption(":avcodec-hw=none");
                } else {
                    m.setHWDecoderEnabled(true, true);
                    m.addOption(":no-mediacodec-dr");
                    m.addOption(":no-omxil-dr");
                }
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
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(PlayerActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 默认退到后台就暂停；开了后台播放模式则继续出声
        if (!backgroundMode && player != null) {
            try { player.pause(); } catch (Throwable ignored) {}
        }
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
        stopFlvBridge();
        if (recSession != null) { try { recSession.cancel(); } catch (Throwable ignored) {} recSession = null; }
        parseSink = null;
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
