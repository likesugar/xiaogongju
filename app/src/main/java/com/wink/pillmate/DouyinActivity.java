package com.wink.pillmate;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 抖音解析（源码同款界面）：全屏 WebView + 浮动顶栏(输入/解析/切UA/记录) + 底部记录面板 */
public class DouyinActivity extends Activity {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    private WebView webView;
    private EditText etUrl;
    private LinearLayout recordsPanel, recordList;
    private boolean desktopUA = false;
    private int recordId = 0;
    private final Set<String> foundUrls = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_douyin);

        webView = findViewById(R.id.webview);
        etUrl = findViewById(R.id.et_url);
        recordsPanel = findViewById(R.id.bottom_panel);
        recordList = findViewById(R.id.layout_records);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(UA_MOBILE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(makeSniffClient());

        // 解析：纯数字=抖音房间号，贴文案自动抽链接
        findViewById(R.id.btn_parse).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { parse(); }
        });
        etUrl.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                    parse();
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.btn_switch_ua).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                desktopUA = !desktopUA;
                webView.getSettings().setUserAgentString(desktopUA ? UA_DESKTOP : UA_MOBILE);
                ((TextView) findViewById(R.id.btn_switch_ua)).setText(desktopUA ? "切手机UA" : "切电脑UA");
                reloadCurrent();
            }
        });

        // 记录面板显隐
        findViewById(R.id.btn_records).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                recordsPanel.setVisibility(
                    recordsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        // ≡ 浮钮：顶栏显隐
        findViewById(R.id.btn_toggle_top).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                View top = findViewById(R.id.top_bar);
                top.setVisibility(top.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        applyImmersive();

        // 外部直接带链接进来
        String pre = getIntent().getStringExtra("url");
        if (pre != null && !pre.isEmpty()) {
            etUrl.setText(pre);
            parse();
        }
    }

    private void parse() {
        String raw = etUrl.getText().toString().trim();
        if (raw.length() == 0) return;
        String url;
        // snssdk1128://aweme/detail/<id> → 网页版视频页
        Matcher sm = Pattern.compile("snssdk\\d+://aweme/detail/(\\d+)").matcher(raw);
        if (sm.find()) {
            url = "https://www.douyin.com/video/" + sm.group(1);
        } else {
            Matcher m = URL_PATTERN.matcher(raw);
            if (m.find()) {
                url = m.group().replaceAll("[.,;:!?]+$", "");
            } else if (raw.matches("\\d+")) {
                url = "https://live.douyin.com/" + raw;   // 纯数字 → 抖音房间号
            } else {
                return;
            }
            if (!url.startsWith("http")) url = "https://" + url;
        }
        ensureDesktopForLive(url);
        bgLive.loadUrl(url);   // 后台桌面 UA 跑，前台保持不动
        recordsPanel.setVisibility(View.VISIBLE);
    }

    /** 前台/后台共用的嗅探 client */
    private WebViewClient makeSniffClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String s = u.getScheme();
                if ("http".equals(s) || "https".equals(s)) return false;
                // snssdk1128://aweme/detail/<id> → 网页版视频页
                if ("snssdk1128".equals(s) || "snssdk1233".equals(s) || "aweme".equals(s)) {
                    String path = u.getPath();
                    Matcher am = Pattern.compile("/detail/(\\d+)").matcher(path == null ? "" : path);
                    if (am.find()) {
                        view.loadUrl("https://www.douyin.com/video/" + am.group(1));
                        return true;
                    }
                }
                return true;   // 其他私有协议忽略
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("/log/")) return null;
                String l = url.toLowerCase();
                boolean hit = l.contains(".flv") || l.contains(".m3u8") || l.contains("stream-")
                    || l.contains(".mp4") || l.contains(".m4s")
                    || l.contains("douyinvod") || l.contains("/aweme/v1/play")
                    || l.contains("playwm");
                if (hit && foundUrls.add(url)) {
                    String hi = url.replaceAll("ratio=[a-zA-Z0-9_]+", "ratio=1080p");
                    if (hi.equals(url)) {
                        final String f = url;
                        main.post(new Runnable() { public void run() { addRecord(f); } });
                    } else {
                        final String f = hi;
                        foundUrls.add(f);
                        main.post(new Runnable() { public void run() { addRecord(f); } });
                    }
                    if (l.contains(".m3u8")) {
                        final String mu = url;
                        new Thread(new Runnable() {
                            public void run() { pickBestVariant(mu); }
                        }).start();
                    }
                }
                return null;
            }
        };
    }

    /** 后台解析：原画只有桌面 UA 给 → 1px 桌面 WebView 负责干活，前台保持不动 */
    private WebView bgLive = null;

    private void ensureBgLive() {
        if (bgLive != null) return;
        bgLive = new WebView(this);
        WebSettings s = bgLive.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(UA_DESKTOP);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(bgLive, true);
        bgLive.setWebChromeClient(new WebChromeClient());
        bgLive.setWebViewClient(makeSniffClient());
        ((android.view.ViewGroup) findViewById(android.R.id.content))
            .addView(bgLive, new android.view.ViewGroup.LayoutParams(1, 1));
    }

    /** 直播/视频解析都走后台桌面 WebView（原画只有桌面 UA 给），前台保持不动 */
    private void ensureDesktopForLive(String url) {
        ensureBgLive();
    }

    private void reloadCurrent() {
        String cur = webView.getUrl();
        if (cur != null && !cur.startsWith("data:")) webView.loadUrl(cur);
    }

    /** HLS 主清单择优：抓 BANDWIDTH 最大的变体流进记录 */
    private void pickBestVariant(String masterUrl) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(masterUrl).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", webView.getSettings().getUserAgentString());
            c.setRequestProperty("Referer", "https://live.douyin.com/");
            if (c.getResponseCode() != 200) return;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close(); c.disconnect();
            String body = bo.toString("UTF-8");
            if (!body.contains("#EXT-X-STREAM-INF")) return;   // 已是媒体清单
            long bestBw = -1; String best = null; long curBw = -1;
            java.net.URI base = java.net.URI.create(masterUrl);
            for (String ln : body.split("\n")) {
                String t = ln.trim();
                if (t.startsWith("#EXT-X-STREAM-INF")) {
                    Matcher bm = Pattern.compile("BANDWIDTH=(\\d+)").matcher(t);
                    curBw = bm.find() ? Long.parseLong(bm.group(1)) : -1;
                } else if (!t.isEmpty() && !t.startsWith("#") && curBw > bestBw) {
                    bestBw = curBw;
                    best = base.resolve(t).toString();
                }
            }
            if (best != null && foundUrls.add(best)) {
                final String f = best;
                main.post(new Runnable() { public void run() { addRecord(f); } });
            }
        } catch (Throwable ignored) {}
    }

    /** 同一直播间只保留最高码率：biz_vbitrate 大的顶掉小的 */
    private final java.util.HashMap<String, Object[]> streamBest = new java.util.HashMap<>();

    private void addRecord(final String rawUrl) {
        recordId++;
        final int id = recordId;
        // douyinvod/playwm 直链需要 Referer → 走本地 /dy 代理
        String lu = rawUrl.toLowerCase();
        final String streamUrl = (lu.contains("douyinvod") || lu.contains("/aweme/v1/play") || lu.contains("playwm"))
            ? ("http://127.0.0.1:8123/dy?u=" + android.net.Uri.encode(rawUrl))
            : rawUrl;

        // 直播流按 stream-<id> 分组，只留 biz_vbitrate 最高那条
        String streamKey = "";
        long bitrate = -1;
        Matcher km = Pattern.compile("stream-\\d+").matcher(rawUrl);
        if (km.find()) streamKey = km.group();
        Matcher bm = Pattern.compile("biz_vbitrate=(\\d+)").matcher(rawUrl);
        if (bm.find()) bitrate = Long.parseLong(bm.group(1));
        if (!streamKey.isEmpty()) {
            Object[] prev = streamBest.get(streamKey);
            if (prev != null) {
                if (bitrate <= (Long) prev[0]) { recordId--; return; }   // 低画质直接丢
                recordList.removeView((View) prev[1]);   // 新的更高，顶掉旧条
            }
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(8, 10, 8, 10);

        TextView tv = new TextView(this);
        tv.setText("直播流 " + id);
        tv.setTextColor(0xFF2ED573);
        tv.setTextSize(13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("流地址", streamUrl));
                Toast.makeText(DouyinActivity.this, "已复制流地址", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(tv);

        TextView btn = new TextView(this);
        btn.setText("播放");
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.bg_btn);
        btn.setPadding(28, 12, 28, 12);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent it = new Intent(DouyinActivity.this, PlayerActivity.class);
                it.putExtra("autoUrl", streamUrl);
                startActivity(it);
            }
        });
        row.addView(btn);

        recordList.addView(row, 0);
        if (!streamKey.isEmpty()) streamBest.put(streamKey, new Object[]{bitrate, row});
        recordsPanel.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (bgLive != null) {
            try { bgLive.destroy(); } catch (Throwable ignored) {}
            bgLive = null;
        }
        super.onDestroy();
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
}
