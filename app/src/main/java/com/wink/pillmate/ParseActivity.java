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

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖哔解析面板（对话框，挂 VLC 播放器上）：
 * 只读链接，1px 后台 WebView 干活（B站=peanutdl 代解析；抖音=页面嗅探），
 * 出地址直接交给底层播放器开播。无网页界面。
 */
public class ParseActivity extends Activity {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+|bilibili://[^\\s]+");
    private static final Pattern BILI_VIDEO = Pattern.compile("bilibili://(?:video|bangumi|story)/([0-9]+)");
    private static final Pattern BILI_LIVE = Pattern.compile("bilibili://live/(\\d+)");

    private EditText etInput;
    private LinearLayout list;
    private final Set<String> seenMedia = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean mobileUA = true;
    private int recordId = 0;

    // ---------- 后台解析状态 ----------
    private WebView bgWeb = null;
    private String pendingBili = null;
    private boolean parsing = false;
    private boolean bgDone = false;
    private boolean autoPlayed = false;
    private final java.util.List<String> autoCandidates = new java.util.ArrayList<>();

    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parse);

        etInput = findViewById(R.id.etParseUrl);
        list = findViewById(R.id.parseList);

        etInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        etInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                    triggerParse();
                    return true;
                }
                return false;
            }
        });

        findViewById(R.id.btnParseGo).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { triggerParse(); }
        });
        findViewById(R.id.btnParseUA).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mobileUA = !mobileUA;
                ((TextView) findViewById(R.id.btnParseUA)).setText(mobileUA ? "UA:手机" : "UA:桌面");
                if (bgWeb != null) bgWeb.getSettings().setUserAgentString(mobileUA ? UA_MOBILE : UA_DESKTOP);
            }
        });

        // 外部直接带链接进来
        String pre = getIntent().getStringExtra("url");
        if (pre != null && !pre.isEmpty()) {
            etInput.setText(pre);
            triggerParse();
        }
    }

    // ---------- 解析/停止 ----------
    private void triggerParse() {
        if (parsing) {
            stopParse();
            return;
        }
        String raw = etInput.getText().toString().trim();
        if (raw.length() == 0) return;
        String url = normInput(raw);
        if (url == null) return;

        parsing = true;
        ((TextView) findViewById(R.id.btnParseGo)).setText("停止");

        if (isStreamUrl(url)) { addRecord("直播流", url); parseDone(); return; }

        if (url.contains("bilibili.com")) {
            resolveBiliViaPeanut(url);   // B站 → peanutdl 后台代解析
        } else {
            // 抖音等：后台直接开页面嗅探
            ensureBgWeb();
            bgWeb.loadUrl(url);
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

    private void parseDone() {
        if (!parsing) return;
        parsing = false;
        ((TextView) findViewById(R.id.btnParseGo)).setText("解析");
    }

    private String normInput(String raw) {
        raw = raw.trim();
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

    /** bilibili://video/117335771846453?... → https://www.bilibili.com/video/av117335771846453 */
    private String biliSchemeToWeb(String url) {
        Matcher lv = BILI_LIVE.matcher(url);
        if (lv.find()) return "https://live.bilibili.com/" + lv.group(1);
        Matcher mv = BILI_VIDEO.matcher(url);
        if (mv.find()) return "https://www.bilibili.com/video/av" + mv.group(1);
        return null;
    }

    // ---------- 后台 1px WebView ----------
    private void ensureBgWeb() {
        if (bgWeb != null) return;
        bgWeb = new WebView(this);
        WebSettings s = bgWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(mobileUA ? UA_MOBILE : UA_DESKTOP);
        CookieManager.getInstance().setAcceptThirdPartyCookies(bgWeb, true);
        // 后台静音：页面视频不许出声
        final Handler mh2 = new Handler(Looper.getMainLooper());
        final Runnable muteTask2 = new Runnable() {
            public void run() {
                if (bgWeb == null) return;
                bgWeb.evaluateJavascript(
                    "(function(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++)m[i].muted=true;})()", null);
                mh2.postDelayed(this, 1200);
            }
        };
        mh2.postDelayed(muteTask2, 500);
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
                String url = request.getUrl().toString();
                maybeRecordBiliMedia(url);   // B站媒体直链
                // 源码嗅探规则：.flv / .m3u8 / stream-（抖音等）
                String l = url.toLowerCase();
                if (url.contains(".flv") || url.contains(".m3u8") || url.contains("stream-")) {
                    final String f = url;
                    main.post(new Runnable() { public void run() { addRecord("直播流", f); } });
                }
                return null;
            }
        });
        android.view.ViewGroup content = (android.view.ViewGroup) findViewById(android.R.id.content);
        content.addView(bgWeb, new android.view.ViewGroup.LayoutParams(1, 1));
    }

    private void resolveBiliViaPeanut(String biliUrl) {
        pendingBili = biliUrl;
        bgDone = false;      // 每次点解析只跑一轮
        seenMedia.clear();   // 清上一轮快照：重复解析同一视频也能立刻出结果
        autoPlayed = false;
        ensureBgWeb();
        bgWeb.loadUrl("https://peanutdl.com/zh/bilibili");
    }

    /** 页面就绪自动填链接点「获取视频」；找不到输入框自动重试 */
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

    /** 从 peanutdl 结果页 DOM 提取 mp4 直链（结果是文字链接，不发媒体请求） */
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
                    } else if (round < 20 && parsing) {
                        main.postDelayed(new Runnable() { public void run() { pollPeanutResult(round + 1); } }, 1500);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 嗅探 B站媒体直链（只看后台 WebView；排除埋点） */
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
                    if (bgWeb != null) bgWeb.stopLoading();   // 拿到结果后台收工
                    parseDone();
                }
            });
        }
        final String f = url;
        main.post(new Runnable() { public void run() { addRecord("B站", f); } });
    }

    /** 记录行：点地址复制；首条自动开播，其余点「播放」换片 */
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
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_btn);
        tv.setPadding(24, 12, 24, 12);
        tv.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                handToPlayer(streamUrl);
            }
        });
        row.addView(tv);

        TextView tvUrl2 = new TextView(this);
        tvUrl2.setText(rawUrl);
        tvUrl2.setTextColor(0xFF8A94A6);
        tvUrl2.setTextSize(10);
        tvUrl2.setPadding(8, 0, 8, 0);
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
                handToPlayer(streamUrl);
            }
        });
        row.addView(btn);

        list.addView(row, 0);

        // 自动开播：延迟收集，选最高码率那条
        if (rawUrl.startsWith("http") && (isBili || isStreamUrl(rawUrl))) {
            autoCandidates.add(streamUrl);
            if (!autoPlayed) {
                autoPlayed = true;
                new Thread(new Runnable() {
                    public void run() {
                        try { Thread.sleep(4000); } catch (Exception ignored) {}
                        main.post(new Runnable() {
                            public void run() {
                                String best = null; long bestBr = -1;
                                for (String u : autoCandidates) {
                                    long br = 0;
                                    Matcher vm = Pattern.compile("biz_vbitrate=(\\d+)").matcher(u);
                                    if (vm.find()) br = Long.parseLong(vm.group(1));
                                    Matcher rm = Pattern.compile("ratio=1080p").matcher(u);
                                    if (rm.find()) br += 10000000;
                                    if (br > bestBr) { bestBr = br; best = u; }
                                }
                                if (best == null && !autoCandidates.isEmpty()) best = autoCandidates.get(0);
                                if (best != null) handToPlayer(best);
                            }
                        });
                    }
                }).start();
            }
        }
    }

    /** 把地址交给底层播放器开播（先停后台页面视频，释放 CDN 会话与解码器） */
    private void handToPlayer(String streamUrl) {
        if (bgWeb != null) {
            bgWeb.evaluateJavascript(
                "(function(){var m=document.querySelectorAll('video');for(var i=0;i<m.length;i++){try{m[i].pause();m[i].removeAttribute('src');m[i].load();}catch(e){}}})()", null);
        }
        // 每次新开播放器实例（15.1 验证过的模式，规避换流黑屏）
        Intent it = new Intent(this, PlayerActivity.class);
        it.putExtra("autoUrl", streamUrl);
        startActivity(it);
    }

    @Override
    protected void onDestroy() {
        if (bgWeb != null) {
            try { bgWeb.destroy(); } catch (Throwable ignored) {}
            bgWeb = null;
        }
        super.onDestroy();
    }
}
