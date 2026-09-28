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

        webView.setWebViewClient(new WebViewClient() {
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
                // 源码规则（直播 flv/m3u8/stream-）+ 视频页扩展（douyinvod mp4/m4s、playwm 直链）
                String l = url.toLowerCase();
                boolean hit = l.contains(".flv") || l.contains(".m3u8") || l.contains("stream-")
                    || l.contains(".mp4") || l.contains(".m4s")
                    || l.contains("douyinvod") || l.contains("/aweme/v1/play")
                    || l.contains("playwm");
                if (hit && foundUrls.add(url)) {
                    // 画质拉满：ratio=540p/720p/default → 1080p（改写后与原链接都保留）
                    String hi = url.replaceAll("ratio=[a-zA-Z0-9_]+", "ratio=1080p");
                    final String f = hi.equals(url) ? url : hi;
                    foundUrls.add(f);
                    main.post(new Runnable() { public void run() { addRecord(f); } });
                }
                return null;
            }
        });

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
        webView.loadUrl(url);
        recordsPanel.setVisibility(View.VISIBLE);
    }

    private void reloadCurrent() {
        String cur = webView.getUrl();
        if (cur != null && !cur.startsWith("data:")) webView.loadUrl(cur);
    }

    private void addRecord(final String rawUrl) {
        recordId++;
        final int id = recordId;
        // douyinvod/playwm 直链需要 Referer → 走本地 /dy 代理
        String lu = rawUrl.toLowerCase();
        final String streamUrl = (lu.contains("douyinvod") || lu.contains("/aweme/v1/play") || lu.contains("playwm"))
            ? ("http://127.0.0.1:8123/dy?u=" + android.net.Uri.encode(rawUrl))
            : rawUrl;

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
        recordsPanel.setVisibility(View.VISIBLE);
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
