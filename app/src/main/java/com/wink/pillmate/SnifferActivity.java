package com.wink.pillmate;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 网页嗅探+直播代理入口：打开页面点播放，截获直播列表自动走本地代理开播 */
public class SnifferActivity extends Activity {

    private static final Pattern MEDIA = Pattern.compile(
        "\\.(m3u8|mp4|flv|mkv|avi|ts|webm|mp3|m4a|aac|flac|mov)(\\?|$)|\\.ts\\?|/stream/|media-worker", Pattern.CASE_INSENSITIVE);

    private WebView webView;
    public static final int[] kbState = {0};
    private static final java.util.LinkedHashSet<String> kbSeen = new java.util.LinkedHashSet<>();
    private static java.io.FileOutputStream kbOut = null;
    private static String kbOutName = "";
    private static java.io.File kbDir;
    private static final String[] kbStream = {""};
    private boolean launched = false;
    private static boolean mediaLocked = false;

    public static volatile boolean kbPageAlive = false;   // 网页解析页可用（下载页“录制当前”用）

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        kbPageAlive = true;
        setContentView(R.layout.activity_sniffer);

        // Android 13+ 通知运行时权限（后台录制通知必需）
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
        webView = findViewById(R.id.sniffWebView);
        final EditText etUrl = findViewById(R.id.etSniffUrl);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 页内换房间也重置嗅探锁，允许锁定新直播间的最高档
                mediaLocked = false;
                TextView r = findViewById(R.id.tvSniffResult);
                TextView b = findViewById(R.id.btnKbPlay);
                if (r != null) r.setText("嗅探结果");
                if (b != null) b.setVisibility(View.GONE);
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                    Uri u = request.getUrl();
                    if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme())) return null;
                    String url = u.toString();
                    if (kbState[0] == 1 && kbSeen.add(url) && url.toLowerCase().contains(".ts")) {
                        final long __t0 = System.currentTimeMillis();
                        final java.util.Map<String, String> __hdrs = request.getRequestHeaders();
                        // KB 模式（单次令牌版）：我们替页面下载这份分片，存档后回喂给页面
                        try {
                            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                            c.setConnectTimeout(6000); c.setReadTimeout(6000);
                            // 复刻页面原请求头 + Cookie（FC2 校验这些）
                            if (__hdrs != null) {
                                for (java.util.Map.Entry<String, String> h : __hdrs.entrySet()) {
                                    String hk = h.getKey();
                                    if (hk == null || hk.equalsIgnoreCase("host")) continue;
                                    try { c.setRequestProperty(hk, h.getValue()); } catch (Throwable ignored) {}
                                }
                            }
                            String ck = android.webkit.CookieManager.getInstance().getCookie(url);
                            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
                            int code = c.getResponseCode();
                            String ct = c.getContentType();
                            kblog("ts " + code + " " + url);
                            if (code == 200) {
                                java.io.InputStream in = c.getInputStream();
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                byte[] b = new byte[8192]; int n;
                                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                                in.close();
                                byte[] body = bo.toByteArray();
                                kblog2();
                                if (kbOut != null) { kbOut.write(body); kbOut.flush(); kblog("存 " + body.length + "B"); }
                                else kblog("kbOut 为空！");
                                c.disconnect();
                                return new android.webkit.WebResourceResponse(
                                    ct == null ? "video/mp2t" : ct, null,
                                    new java.io.ByteArrayInputStream(body));
                            }
                            c.disconnect();
                        } catch (Throwable ignored) {}
                    }
                    if (MEDIA.matcher(url).find()) {
                        final String fUrl = url;
                        runOnUiThread(new Runnable() { public void run() { onFound(fUrl); } });
                    }
                    // 画质强制最高：原样取 master，解析变体选 BANDWIDTH 最大者，只喂这一个给页面
                    if (url.contains("master_playlist")) {
                        try {
                            java.net.HttpURLConnection hc = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                            hc.setConnectTimeout(8000); hc.setReadTimeout(8000);
                            hc.setRequestProperty("User-Agent", webView.getSettings().getUserAgentString());
                            hc.setRequestProperty("Referer", "https://guangdongvideo.com/");
                            if (hc.getResponseCode() == 200) {
                                java.io.InputStream hin = hc.getInputStream();
                                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                                byte[] hb = new byte[8192]; int hn;
                                while ((hn = hin.read(hb)) > 0) bo.write(hb, 0, hn);
                                hin.close(); hc.disconnect();
                                String body = bo.toString("UTF-8");
                                // 解析变体：BANDWIDTH 最大的那个
                                long bestBw = -1; String bestUrl = null;
                                long curBw = -1;
                                for (String ln : body.split("\n")) {
                                    String t = ln.trim();
                                    if (t.startsWith("#EXT-X-STREAM-INF")) {
                                        try {
                                            java.util.regex.Matcher bm = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(t);
                                            curBw = bm.find() ? Long.parseLong(bm.group(1)) : -1;
                                        } catch (Exception e) { curBw = -1; }
                                    } else if (!t.isEmpty() && !t.startsWith("#")) {
                                        String abs = new java.net.URL(new java.net.URL(url), t).toString();
                                        if (curBw > bestBw) { bestBw = curBw; bestUrl = abs; }
                                    }
                                }
                                if (bestUrl != null) {
                                    LiveProxy.mediaUrl = bestUrl;   // 刷新器直接抓最高档
                                    mediaLocked = true;             // 锁定，后台 ABR 降档不跟随
                                    String out = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=" + bestBw + "\n"
                                        + "http://127.0.0.1:" + LiveProxy.PORT + "/relay?u="
                                        + java.net.URLEncoder.encode(bestUrl, "UTF-8") + "\n";
                                    return new android.webkit.WebResourceResponse("application/vnd.apple.mpegurl", "utf-8",
                                        new java.io.ByteArrayInputStream(out.getBytes("UTF-8")));
                                }
                                // 解析失败 → 原样回喂
                                return new android.webkit.WebResourceResponse("application/vnd.apple.mpegurl", "utf-8",
                                    new java.io.ByteArrayInputStream(bo.toByteArray()));
                            }
                            hc.disconnect();
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                return null;
            }
        });

        findViewById(R.id.btnKbPlay).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent it = new Intent(SnifferActivity.this, PlayerActivity.class);
                    it.putExtra("autoUrl", "http://127.0.0.1:" + LiveProxy.PORT + "/playlist.m3u8");
                    startActivity(it);
                } catch (Throwable t) { Toast.makeText(SnifferActivity.this, "打开失败", Toast.LENGTH_SHORT).show(); }
            }
        });
        findViewById(R.id.btnGo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                load(((EditText) findViewById(R.id.etSniffUrl)).getText().toString().trim());
            }
        });

            LiveProxy.start();
        kbDir = new java.io.File(getExternalFilesDir(null), "录制/segments");

        String init = getIntent().getStringExtra("url");
        if (init != null && !init.isEmpty()) {
            etUrl.setText(init);
            load(init);
        }
    }

    /** 截获直播列表：喂本地代理，首次成功自动跳播放器开播 */
    private void onFound(String url) {
        String lu = url.toLowerCase();
        if (lu.contains(".ts")) return; // 分片不处理（刷新器自己抓）
        if (lu.contains("/stream/") && lu.contains("playlist") && !lu.contains("master")) {
            if (mediaLocked) return; // 已锁定最高档，后台 ABR 降档不跟随
            mediaLocked = true;      // master 已被强制 targets=90，首个列表即最高档
            // 媒体列表地址交给独立刷新器（服务自己轮询，页面可退）
            LiveProxy.mediaUrl = url;
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("mediaUrl", url).apply();
            LiveProxy.fetchLatest(url);
            // 全手动：不自动跳播放器，点底部“▶ 播放”自己进
            runOnUiThread(new Runnable() { public void run() {
                TextView r = findViewById(R.id.tvSniffResult);
                TextView b = findViewById(R.id.btnKbPlay);
                if (r != null) r.setText("已嗅探到直播流，点 ▶ 播放，或去下载页录制");
                if (b != null) b.setVisibility(View.VISIBLE);
            }});
        }
    }

    private void load(String url) {
        if (url.isEmpty()) return;
        // 换房间：重置嗅探锁，允许重新锁定新直播间的最高档
        mediaLocked = false;
        if (!url.startsWith("http")) url = "https://" + url;
        Toast.makeText(this, "正在打开页面并嗅探…", Toast.LENGTH_SHORT).show();
        webView.loadUrl(url);
    }

    /** 开始 KB 捕获（页面轮询期间持续抓分片追加合并） */
    private static java.io.FileWriter kblogW = null;
    private static void kblog2() {
        try {
            if (kblogW == null && kbDir != null) {
                kblogW = new java.io.FileWriter(new java.io.File(kbDir.getParentFile(), "kbdebug.txt"), true);
            }
        } catch (Exception ignored) {}
    }
    private static void kblog(String msg) {
        try {
            kblog2();
            if (kblogW != null) {
                kblogW.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n");
                kblogW.flush();
            }
        } catch (Exception ignored) {}
    }

    public static void startKb(Context c) {
        if (kbState[0] == 1) return;
        try {
            if (kbDir == null) return;
            kbDir.mkdirs();
            kbOutName = "live_kb_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date()) + ".ts";
            kbOut = new java.io.FileOutputStream(new java.io.File(kbDir.getParentFile(), kbOutName), true);
            LiveProxy.kbOut = kbOut;
            kbState[0] = 1;
            PlayerActivity.registerKb(kbOutName, new java.io.File(kbDir.getParentFile(), kbOutName));
            kbSeen.clear();
            c.startService(new Intent(c, KbRecordService.class));
        } catch (Exception e) { kbState[0] = 0; }
    }

    /** 停止后台录制并关闭输出流 */
    public static void stopKb() {
        try {
            kbState[0] = 0;
            java.io.FileOutputStream fo = kbOut;
            kbOut = null;
            if (fo != null) { fo.flush(); fo.close(); }
            // 时间戳重建：消除追加分片的 PTS 跳变（录 5 秒显示 10 秒无效长度的根因）
            if (kbOutName != null) {
                final java.io.File src = new java.io.File(kbDir.getParentFile(), kbOutName);
                if (src.exists() && src.length() > 0) {
                    final java.io.File tmp = new java.io.File(src.getParentFile(), "fix_tmp.ts");
                    String[] args = { "-y", "-fflags", "+genpts", "-i", src.getAbsolutePath(),
                        "-c", "copy", "-map", "0", "-f", "mpegts", tmp.getAbsolutePath() };
                    com.arthenica.ffmpegkit.FFmpegKit.executeWithArgumentsAsync(args,
                        new com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback() {
                            public void apply(com.arthenica.ffmpegkit.FFmpegSession st) {
                                if (tmp.exists() && tmp.length() > 0) {
                                    src.delete();
                                    tmp.renameTo(src);
                                } else tmp.delete();
                            }
                        });
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 页面保留轮询（录制依赖令牌续命），退出 Activity 不销毁 WebView
        if (kbState[0] == 1) {
            try { startService(new Intent(this, KbRecordService.class)); } catch (Throwable ignored) {}
        }
    }
}
