package com.wink.pillmate;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.util.LinkedHashSet;

/** 本地代理+独立刷新器：页面点播放后拿到媒体列表地址(c/d)，
 *  之后服务自己每 800ms 轮询该地址（页面可退），分片由刷新器抓取并写入录制文件，
 *  VLC 播 http://127.0.0.1:8123/playlist.m3u8（ts 经 /ts?u= 中转，绕过 ffmpeg-min 无 https） */
public class LiveProxy {

    public static final int PORT = 8123;
    public static volatile byte[] latestBody = null;
    public static volatile long latestAt = 0;
    public static volatile String mediaUrl = null;
    public static volatile String tsPipe = null;
    public static volatile long liveTsBytes = 0;
    public static volatile FileOutputStream kbOut = null;
    public static volatile String kbOutName = "";
    public static volatile int pollCount = 0;
    public static volatile int failCount = 0;
    private static final java.util.LinkedHashMap<String, byte[]> segCache = new java.util.LinkedHashMap<>();
    public static void putSeg(String url, byte[] body) {
        synchronized (segCache) {
            segCache.put(url, body);
            while (segCache.size() > 40) {
                String k = segCache.keySet().iterator().next();
                segCache.remove(k);
            }
        }
    }
    public static byte[] getSeg(String url) {
        synchronized (segCache) { return segCache.get(url); }
    }
    private static volatile boolean running = false;
    private static volatile boolean refreshing = false;
    private static final LinkedHashSet<String> fetched = new LinkedHashSet<>();

    public static void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    ServerSocket ss = new ServerSocket(PORT);
                    while (running) {
                        final Socket s = ss.accept();
                        new Thread(new Runnable() { public void run() {
                            try { handle(s); } catch (Throwable ignored) {}
                            finally { try { s.close(); } catch (Throwable ignored) {} }
                        } }).start();
                    }
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    /** 独立刷新器：服务里启动；每 800ms 轮询媒体列表 + 抓新分片写录制文件 */
    public static void startRefresher() {
        if (refreshing) return;
        refreshing = true;
        new Thread(new Runnable() {
            public void run() {
                int fails = 0;
                while (refreshing) {
                    try {
                        String mu = mediaUrl;
                        if (mu == null) { Thread.sleep(800); continue; }
                        byte[] body = httpGet(mu);
                        if (body == null) {
                            Thread.sleep(1500);
                            continue;
                        }
                        fails = 0;
                        latestBody = body;
                        latestAt = System.currentTimeMillis();
                        // 解析分片，抓新的写录制
                        for (String ln : new String(body, "UTF-8").split("\n")) {
                            String t = ln.trim();
                            if (t.startsWith("http") && t.contains(".ts") && !fetched.contains(t)) {
                                byte[] seg = httpGet(t);
                                if (seg != null) {
                                    fetched.add(t);
                                    FileOutputStream fo = kbOut;
                                    if (fo != null) {
                                        fo.write(seg);
                                        fo.flush();
                                    }
                                } // 失败不标记，下一轮重试（减少时间戳断口）
                            }
                        }
                        Thread.sleep(800);
                    } catch (Throwable e) {
                        try { Thread.sleep(800); } catch (Exception ignored) {}
                    }
                }
                refreshing = false;
            }
        }).start();
    }

    public static void stopRefresher() {
        refreshing = false;
        mediaUrl = null;
        fetched.clear();
    }

    private static byte[] httpGet(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
            c.setRequestProperty("Referer", "https://guangdongvideo.com/");
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            c.disconnect();
            return bo.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    public static void fetchLatest(String url) {
        final String fUrl = url;
        new Thread(new Runnable() { public void run() {
            byte[] body = httpGet(fUrl);
            if (body != null) { latestBody = body; latestAt = System.currentTimeMillis(); }
        } }).start();
    }

    private static void handle(Socket s) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String reqLine = br.readLine();
            if (reqLine == null) return;
            String path = reqLine.split(" ")[1];

            if (path.startsWith("/relay")) {
                // 通用中转：u=原始地址（m3u8 内容递归改写；分片流式转发）
                try {
                    String raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                    HttpURLConnection oc = (HttpURLConnection) new URL(raw).openConnection();
                    oc.setConnectTimeout(8000);
                    oc.setReadTimeout(8000);
                    oc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    oc.setRequestProperty("Referer", "https://guangdongvideo.com/");
                    int code = oc.getResponseCode();
                    if (code == 200) {
                        InputStream in = oc.getInputStream();
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] rb = new byte[8192];
                        int rn;
                        while ((rn = in.read(rb)) > 0) bo.write(rb, 0, rn);
                        in.close();
                        oc.disconnect();
                        byte[] body = bo.toByteArray();
                        String head = new String(body, 0, Math.min(body.length, 64), "UTF-8");
                        if (head.startsWith("#EXTM3U")) {
                            StringBuilder sb = new StringBuilder();
                            for (String ln : new String(body, "UTF-8").split("\n")) {
                                String t = ln.trim();
                                if (!t.isEmpty() && !t.startsWith("#")) {
                                    String abs = new URL(new URL(raw), t).toString();
                                    ln = "http://127.0.0.1:" + PORT + "/relay?u="
                                        + java.net.URLEncoder.encode(abs, "UTF-8");
                                }
                                sb.append(ln).append("\n");
                            }
                            writeResp(s, "200 OK", "application/vnd.apple.mpegurl", sb.toString().getBytes());
                        } else {
                            writeResp(s, "200 OK", "video/mp2t", body);
                        }
                        return;
                    }
                    oc.disconnect();
                    writeResp(s, "404 Not Found", "text/plain", "upstream err".getBytes());
                } catch (Throwable e) {
                    writeResp(s, "404 Not Found", "text/plain", "relay err".getBytes());
                }
                return;
            }

            if (path.startsWith("/live.ts")) {
                // 桥接 TS 流：尾随增长的缓存文件流式吐给 VLC
                try {
                    String f = tsPipe;
                    if (f == null) { writeResp(s, "404 Not Found", "text/plain", "no bridge".getBytes()); return; }
                    OutputStream os = s.getOutputStream();
                    os.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".getBytes());
                    os.flush();
                    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
                    long pos = 0; long last = System.currentTimeMillis();
                    byte[] rb = new byte[65536];
                    while (true) {
                        long len = raf.length();
                        if (len > pos) {
                            last = System.currentTimeMillis();
                            raf.seek(pos);
                            int rn = raf.read(rb);
                            if (rn > 0) { os.write(rb, 0, rn); os.flush(); liveTsBytes += rn; pos += rn; }
                        } else {
                            if (System.currentTimeMillis() - last > 20000) break;
                            Thread.sleep(250);
                        }
                    }
                    raf.close();
                } catch (Throwable ignored) {}
                return;
            }

            if (path.startsWith("/bili")) {
                // B站中转：u=原始媒体地址，带 B站 Referer/UA；流式转发 + Range 支持
                try {
                    String raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                    // 读请求头里的 Range
                    String range = null;
                    String hl;
                    while ((hl = br.readLine()) != null && !hl.isEmpty()) {
                        if (hl.toLowerCase().startsWith("range:")) {
                            range = hl.substring(6).trim();
                        }
                    }
                    HttpURLConnection oc = (HttpURLConnection) new URL(raw).openConnection();
                    oc.setConnectTimeout(8000);
                    oc.setReadTimeout(30000);
                    oc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    oc.setRequestProperty("Referer", "https://www.bilibili.com/");
                    if (range != null) oc.setRequestProperty("Range", range);
                    int code = oc.getResponseCode();
                    if (code == 200 || code == 206) {
                        OutputStream os = s.getOutputStream();
                        StringBuilder hh = new StringBuilder("HTTP/1.1 " + code + (code == 206 ? " Partial Content" : " OK") + "\r\n");
                        String cr = oc.getHeaderField("Content-Range");
                        String cl = oc.getHeaderField("Content-Length");
                        String ct = oc.getHeaderField("Content-Type");
                        hh.append("Content-Type: ").append(ct != null ? ct : "video/mp4").append("\r\n");
                        if (cr != null) hh.append("Content-Range: ").append(cr).append("\r\n");
                        if (cl != null) hh.append("Content-Length: ").append(cl).append("\r\n");
                        hh.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
                        os.write(hh.toString().getBytes());
                        InputStream in = oc.getInputStream();
                        byte[] rb = new byte[65536];
                        int rn;
                        while ((rn = in.read(rb)) > 0) os.write(rb, 0, rn);
                        os.flush();
                        in.close(); oc.disconnect();
                        return;
                    }
                    oc.disconnect();
                    writeResp(s, "404 Not Found", "text/plain", "bili upstream err".getBytes());
                } catch (Throwable e) {
                    writeResp(s, "404 Not Found", "text/plain", "bili relay err".getBytes());
                }
                return;
            }

            if (path.startsWith("/dy")) {
                // 抖音中转：u=原始媒体地址，带抖音 Referer/UA
                try {
                    String raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                    String range = null;
                    String hl;
                    while ((hl = br.readLine()) != null && !hl.isEmpty()) {
                        if (hl.toLowerCase().startsWith("range:")) range = hl.substring(6).trim();
                    }
                    HttpURLConnection oc = (HttpURLConnection) new URL(raw).openConnection();
                    oc.setConnectTimeout(8000);
                    oc.setReadTimeout(30000);
                    oc.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                    oc.setRequestProperty("Referer", "https://www.douyin.com/");
                    if (range != null) oc.setRequestProperty("Range", range);
                    int code = oc.getResponseCode();
                    if (code == 200 || code == 206) {
                        OutputStream os = s.getOutputStream();
                        StringBuilder hh = new StringBuilder("HTTP/1.1 " + code + (code == 206 ? " Partial Content" : " OK") + "\r\n");
                        String cr = oc.getHeaderField("Content-Range");
                        String cl = oc.getHeaderField("Content-Length");
                        String ct = oc.getHeaderField("Content-Type");
                        hh.append("Content-Type: ").append(ct != null ? ct : "video/mp4").append("\r\n");
                        if (cr != null) hh.append("Content-Range: ").append(cr).append("\r\n");
                        if (cl != null) hh.append("Content-Length: ").append(cl).append("\r\n");
                        hh.append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
                        os.write(hh.toString().getBytes());
                        InputStream in = oc.getInputStream();
                        byte[] rb = new byte[65536];
                        int rn;
                        while ((rn = in.read(rb)) > 0) os.write(rb, 0, rn);
                        os.flush();
                        in.close(); oc.disconnect();
                        return;
                    }
                    oc.disconnect();
                    writeResp(s, "404 Not Found", "text/plain", "dy upstream err".getBytes());
                } catch (Throwable e) {
                    writeResp(s, "404 Not Found", "text/plain", "dy relay err".getBytes());
                }
                return;
            }

            if (path.startsWith("/playlist.m3u8")) {
                byte[] body = latestBody;
                // 竞态修复：播放器先到就就地等 refresher 抓到第一份列表（最多 10s）
                for (int i = 0; body == null && i < 20 && mediaUrl != null; i++) {
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                    body = latestBody;
                }
                if (body == null) body = httpGet(mediaUrl);   // 最后再亲自补抓一次
                if (body == null) { writeResp(s, "404 Not Found", "text/plain", "no stream".getBytes()); return; }
                latestBody = body;
                StringBuilder sb = new StringBuilder();
                for (String line : new String(body, "UTF-8").split("\n")) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) { sb.append(line).append("\n"); continue; }
                    String enc = java.net.URLEncoder.encode(line, "UTF-8");
                    sb.append("http://127.0.0.1:").append(PORT).append("/ts?u=").append(enc).append("\n");
                }
                writeResp(s, "200 OK", "application/vnd.apple.mpegurl", sb.toString().getBytes());
                return;
            }

            if (path.startsWith("/ts?u=")) {
                String raw = URLDecoder.decode(queryParam(path, "u"), "UTF-8");
                byte[] body = httpGet(raw);
                if (body == null) { writeResp(s, "404 Not Found", "text/plain", "seg err".getBytes()); return; }
                writeResp(s, "200 OK", "video/mp2t", body);
                return;
            }

            writeResp(s, "404 Not Found", "text/plain", "no".getBytes());
        } catch (Throwable ignored) {}
    }

    private static String queryParam(String path, String key) {
        try {
            int qi = path.indexOf('?');
            if (qi < 0) return "";
            for (String kv : path.substring(qi + 1).split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static void writeResp(Socket s, String status, String type, byte[] body) {
        try {
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes());
            os.write(body);
            os.flush();
        } catch (Throwable ignored) {}
    }
}
