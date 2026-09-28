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
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URL;
import java.net.URLDecoder;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音/B站解析（源码同款一套）：
 * WebView 打开页面 → shouldInterceptRequest 嗅探 .flv/.m3u8/stream- →
 * 结果列表点「播放」→ 交给 VLC 播放器开播。
 * B站视频页走专用解析：view API → cid → playurl(html5 mp4) → 本地 bili 代理(带 Referer)。
 */
public class ParseActivity extends Activity {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+|bilibili://[^\\s]+");
    private static final Pattern BILI_VIDEO = Pattern.compile("bilibili://(?:video|bangumi|story)/([0-9]+)");
    private static final Pattern BILI_LIVE = Pattern.compile("bilibili://live/(\\d+)");

    private WebView webView;
    private EditText etInput;
    private LinearLayout list;
    private final Set<String> foundUrls = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean mobileUA = true;
    private int recordId = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parse);

        webView = findViewById(R.id.parseWebView);
        etInput = findViewById(R.id.etParseUrl);
        list = findViewById(R.id.parseList);

        findViewById(R.id.btnParseBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        // 输入框回车(→) = 跳转页面（不解析）
        etInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        etInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                    goNavigate();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.btnParseGo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { triggerParse(); }
        });
        findViewById(R.id.btnParseUA).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleUA(); }
        });

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        applyUA();

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String s = u.getScheme();
                if ("http".equals(s) || "https".equals(s)) return false;
                // bilibili:// 等私有协议 → 转网页版加载，避免 ERR_UNKNOWN_URL_SCHEME
                String web = biliSchemeToWeb(u.toString());
                if (web != null) {
                    view.loadUrl(web);
                    return true;
                }
                return true; // 其他协议（taobao:// 等）直接忽略
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // 输入框跟随网页地址变化（页面跳转/站内导航都同步）
                if (url != null && !url.startsWith("data:") && !parsing) {
                    etInput.setText(url);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && !url.startsWith("data:") && !parsing) {
                    etInput.setText(url);
                }
                parseDone();   // 页面加载完 → 按钮恢复「解析」
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // 前台只走源码嗅探规则：.flv / .m3u8 / stream-（B站条目只认后台 WebView）
                if (url.contains(".flv") || url.contains(".m3u8") || url.contains("stream-")) {
                    if (foundUrls.add(url)) {
                        final String f = url;
                        main.post(new Runnable() { public void run() { addRecord("直播流", f); } });
                    }
                }
                return null;
            }
        });

        // 外部直接带链接进来
        String pre = getIntent().getStringExtra("url");
        if (pre != null && !pre.isEmpty()) {
            etInput.setText(pre);
            goNavigate();
        }
    }

    private void applyUA() {
        webView.getSettings().setUserAgentString(mobileUA
            ? "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        ((TextView) findViewById(R.id.btnParseUA)).setText(mobileUA ? "UA:手机" : "UA:桌面");
    }

    private void toggleUA() {
        mobileUA = !mobileUA;
        applyUA();
        reloadCurrent();
    }

    private void reloadCurrent() {
        String cur = webView.getUrl();
        if (cur != null && !cur.startsWith("data:")) webView.loadUrl(cur);
    }

    // ---------- 解析/停止 切换 ----------
    private boolean parsing = false;

    /** 回车→：只跳转页面（同链接不重复加载；解析未停止时不刷新页面） */
    private void goNavigate() {
        if (parsing) return;   // 解析没停止 → 不动页面
        String url = normInput();
        if (url == null) return;
        if (isStreamUrl(url)) { addRecord("直播流", url); return; }
        String cur = webView.getUrl();
        if (cur != null && cur.startsWith(url)) return;   // 已经在这个页面，不刷新
        webView.loadUrl(url);
    }

    /** 输入框 → 规范化 URL（支持纯数字抖音房号 / bilibili:// 协议串） */
    private String normInput() {
        String raw = etInput.getText().toString().trim();
        if (raw.length() == 0) return null;
        String url = extractUrl(raw);
        if (url == null) {
            if (raw.matches("\\d+")) url = "https://live.douyin.com/" + raw;
            else return null;
        }
        if (url.startsWith("bilibili://")) {
            url = biliSchemeToWeb(url);
            if (url == null) return null;
        }
        if (!url.startsWith("http")) url = "https://" + url;
        return url;
    }

    /** 解析键：纯后台。链接直接取输入框（空则取当前页），前台页面零刷新 */
    private void triggerParse() {
        if (parsing) {
            stopParse();
            return;
        }
        String url = normInput();
        if (url == null) {
            url = webView.getUrl();
            if (url == null || url.startsWith("data:")) return;
        }
        parsing = true;
        ((TextView) findViewById(R.id.btnParseGo)).setText("停止");

        if (isStreamUrl(url)) { addRecord("直播流", url); parseDone(); return; }

        if (url.contains("bilibili.com")) {
            resolveBiliViaPeanut(url);   // 后台 peanutdl，前台不动
        }
        // 非 B站（抖音直播等）：靠前台页面嗅探；仅当当前页不是目标页才加载
        else {
            String cur = webView.getUrl();
            if (cur == null || !cur.startsWith(url)) webView.loadUrl(url);
        }
    }

    private void stopParse() {
        parsing = false;
        ((TextView) findViewById(R.id.btnParseGo)).setText("解析");
        if (bgWeb != null) {
            bgWeb.stopLoading();
            pendingBili = null;
        }
    }

    /** 解析完成（后台拿到结果）恢复按钮 */
    private void parseDone() {
        if (!parsing) return;
        parsing = false;
        ((TextView) findViewById(R.id.btnParseGo)).setText("解析");
    }

    @Override
    protected void onDestroy() {
        if (bgWeb != null) {
            try { bgWeb.destroy(); } catch (Throwable ignored) {}
            bgWeb = null;
        }
        super.onDestroy();
    }

    private boolean isStreamUrl(String url) {
        String l = url.toLowerCase();
        return l.endsWith(".flv") || l.endsWith(".m3u8") || l.endsWith(".ts")
            || l.contains(".flv?") || l.contains(".m3u8?");
    }

    private String extractUrl(String text) {
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) {
            String u = m.group();
            return u.replaceAll("[.,;:!?]+$", "").replaceFirst("&amp;", "&");
        }
        return null;
    }

    /** bilibili://video/117335771846453?page=0&... → https://www.bilibili.com/video/av117335771846453 */
    private String biliSchemeToWeb(String url) {
        Matcher lv = BILI_LIVE.matcher(url);
        if (lv.find()) return "https://live.bilibili.com/" + lv.group(1);
        Matcher mv = BILI_VIDEO.matcher(url);
        if (mv.find()) return "https://www.bilibili.com/video/av" + mv.group(1);
        return null;
    }

    // ---------- B站：peanutdl 代解析（1px 后台 WebView 自动跑，无验证码，嗅探结果） ----------
    private String pendingBili = null;
    private WebView bgWeb = null;
    private boolean bgDone = false;
    private final Set<String> seenMedia = new HashSet<>();

    /** 后台解析专用 1px WebView：不打扰前台，前台照常开 B站原链接 */
    private void ensureBgWeb() {
        if (bgWeb != null) return;
        bgWeb = new WebView(this);
        WebSettings s = bgWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(mobileUA
            ? "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        CookieManager.getInstance().setAcceptThirdPartyCookies(bgWeb, true);
        bgWeb.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.contains("peanutdl.com")) {
                    view.postDelayed(new Runnable() {
                        public void run() { injectPeanutFill(); }
                    }, 300);
                }
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                maybeRecordBiliMedia(request.getUrl().toString());
                return null;
            }
        });
        android.view.ViewGroup content = (android.view.ViewGroup) findViewById(android.R.id.content);
        content.addView(bgWeb, new android.view.ViewGroup.LayoutParams(1, 1));
    }

    private void resolveBiliViaPeanut(String biliUrl) {
        pendingBili = biliUrl;
        bgDone = false;   // 每次点解析只允许后台跑一轮，抓到即停
        seenMedia.clear();   // 清上一轮快照：重复解析同一视频也能立刻出结果
        ensureBgWeb();
        bgWeb.loadUrl("https://peanutdl.com/zh/bilibili");
    }

    /** 后台页面加载完自动填链接点「获取视频」；找不到输入框自动重试，每步回报列表 */
    private void injectPeanutFill() {
        injectPeanutFill(0);
    }

    private void injectPeanutFill(final int retry) {
        if (pendingBili == null || bgWeb == null) return;
        final String bili = pendingBili.replace("'", "");
        String js = "(function(){"
            + "var inp=document.querySelector('input[type=url]')"
            + "||document.querySelector('input[aria-label*=\"粘贴\"]');"
            + "if(!inp){return 'noinp';}"
            + "var d=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');"
            + "d.set.call(inp,'" + bili + "');"
            + "inp.dispatchEvent(new Event('input',{bubbles:true}));"
            + "setTimeout(function(){"
            + "var b=document.querySelector('button[type=submit]');"
            + "if(!b){var bs=document.getElementsByTagName('button');"
            + "for(var i=0;i<bs.length;i++){var t=bs[i].textContent||'';"
            + "if(t.indexOf('获取')>=0||t.indexOf('解析')>=0||t.indexOf('下载')>=0){b=bs[i];break;}}}"
            + "if(b){b.click();return 'ok';}"
            + "return 'nobtn';"
            + "},400);"
            + "return 'filled';"
            + "})()";
        bgWeb.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (v == null) return;
                if (v.contains("filled") || v.contains("ok")) {
                    if (retry == 0) {
                        main.postDelayed(new Runnable() { public void run() { pollPeanutResult(0); } }, 1500);
                    }
                } else if (retry < 5) {
                    main.postDelayed(new Runnable() {
                        public void run() { injectPeanutFill(retry + 1); }
                    }, 2000);
                } else {
                    main.post(new Runnable() { public void run() { addRecord("B站", "后台填表失败(页面结构变了)"); } });
                }
            }
        });
    }

    /** 从 peanutdl 结果页 DOM 里提取 mp4 直链（结果只是文字链接，不会发媒体请求） */
    private void pollPeanutResult(final int round) {
        if (bgWeb == null || !parsing) return;
        String js = "(function(){"
            + "var r=(document.documentElement.innerHTML.match(/https?:[^\\\"']{10,600}\\.mp4[^\\\"'\\\\s]{0,80}/g)||[])"
            + ".filter(function(u){return /bilivideo|upos/.test(u)});"
            + "return JSON.stringify(r.slice(0,5));"
            + "})()";
        bgWeb.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (v == null || "null".equals(v) || "[]".equals(v)) {
                    if (round < 20 && parsing) {
                        main.postDelayed(new Runnable() { public void run() { pollPeanutResult(round + 1); } }, 1500);
                    } else if (round >= 20) {
                        main.post(new Runnable() { public void run() { addRecord("B站", "后台约40秒未取到结果"); } });
                        parseDone();
                    }
                    return;
                }
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(v);
                    boolean got = false;
                    for (int i = 0; i < arr.length(); i++) {
                        String u = arr.getString(i).replace("\\u0026", "&").replace("&amp;", "&");
                        if (seenMedia.add(u)) {
                            got = true;
                            final String f = u;
                            main.post(new Runnable() { public void run() { addRecord("B站", f); } });
                        }
                    }
                    if (got) {
                        parseDone();
                        main.post(new Runnable() {
                            public void run() { if (bgWeb != null) bgWeb.stopLoading(); }
                        });
                    } else if (round < 12 && parsing) {
                        main.postDelayed(new Runnable() { public void run() { pollPeanutResult(round + 1); } }, 1500);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 嗅探 peanutdl 解析出的媒体直链 → 回填底部列表（v13.5 宽松规则，只看后台 WebView）
     *  仅排除 B站埋点（log），防前台页面请求干扰已由「只看后台」保证 */
    private void maybeRecordBiliMedia(String url) {
        if (url == null) return;
        String l = url.toLowerCase();
        if (l.contains("/log/") || l.contains("data.bilibili.com")) return;
        boolean hit = l.contains(".mp4") || l.contains(".m4s")
            || l.contains(".flv") || l.contains(".m3u8")
            || l.contains("bilivideo") || l.contains("upos-");
        if (!hit || !seenMedia.add(url)) return;
        if (bgWeb != null && !bgDone) {
            bgDone = true;
            main.post(new Runnable() {
                public void run() {
                    if (bgWeb != null) bgWeb.stopLoading();   // 已拿到结果，后台收工
                    parseDone();
                }
            });
        }
        final String f = url;
        main.post(new Runnable() { public void run() { addRecord("B站", f); } });
    }

    /** 记录行：点标题复制，点「播放」交 VLC 播放器；B站直链走本地代理补 Referer */
    private void addRecord(String label, final String rawUrl) {
        recordId++;
        final int id = recordId;
        final boolean isBili = rawUrl.contains("bilibili.com") || rawUrl.contains("bilivideo");
        final String streamUrl;
        try {
            streamUrl = isBili
                ? ("http://127.0.0.1:8123/bili?u=" + java.net.URLEncoder.encode(rawUrl, "UTF-8"))
                : rawUrl;
        } catch (Exception e) { return; }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (list.getChildCount() > 0) lp.topMargin = 2;
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(label + " · " + id);
        tv.setTextColor("B站".equals(label) ? 0xFF4FA8FF : 0xFF2ED573);
        tv.setTextSize(13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("流地址", streamUrl));
                Toast.makeText(ParseActivity.this, "已复制流地址", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(tv);

        TextView tvUrl2 = new TextView(this);
        tvUrl2.setText(rawUrl);
        tvUrl2.setTextColor(0xFF8A94A6);
        tvUrl2.setTextSize(10);
        tvUrl2.setPadding(0, 0, 8, 0);
        tvUrl2.setMaxLines(2);
        tvUrl2.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp2.leftMargin = 8;
        tvUrl2.setLayoutParams(lp2);
        tvUrl2.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("流地址", streamUrl));
                Toast.makeText(ParseActivity.this, "已复制流地址", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(tvUrl2);

        TextView btn = new TextView(this);
        btn.setText("播放");
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundResource(R.drawable.bg_btn);
        btn.setPadding(28, 12, 28, 12);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent it = new Intent(ParseActivity.this, PlayerActivity.class);
                it.putExtra("autoUrl", streamUrl);
                startActivity(it);
            }
        });
        row.addView(btn);

        list.addView(row, 0);
    }
}
