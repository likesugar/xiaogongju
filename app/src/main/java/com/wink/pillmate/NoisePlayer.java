package com.wink.pillmate;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/** 离线白噪音：AudioTrack 实时合成，无限循环 */
public class NoisePlayer {

    public static final int RAIN = 0;
    public static final int WAVE = 1;
    public static final int FIRE = 2;
    public static final int WIND = 3;

    private static final int SR = 44100;
    private static NoisePlayer sInstance;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean playing;
    private int kind = RAIN;

    public static synchronized NoisePlayer get() {
        if (sInstance == null) sInstance = new NoisePlayer();
        return sInstance;
    }

    public boolean isPlaying() { return playing; }
    public int getKind() { return kind; }

    public synchronized void play(int kind) {
        stop();
        this.kind = kind;
        playing = true;

        int buf = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SR, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(buf, SR), AudioTrack.MODE_STREAM);
        if (Build.VERSION.SDK_INT >= 21) {
            track.setVolume(0.75f);
        }
        track.play();

        thread = new Thread(new Runnable() {
            public void run() {
                synthLoop();
            }
        }, "noise-synth");
        thread.start();
    }

    public synchronized void stop() {
        playing = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        if (track != null) {
            try { track.stop(); track.release(); } catch (Exception ignored) {}
            track = null;
        }
    }

    // ---------- 合成 ----------
    private void synthLoop() {
        // 状态量
        float lp = 0f;          // 一阶低通
        float lp2 = 0f;         // 二级低通
        float wavePhase = 0f;   // 海浪 LFO
        float windPhase = 0f;   // 风 LFO
        float crackleEnv = 0f;  // 篝火爆裂包络
        java.util.Random rnd = new java.util.Random();
        short[] pcm = new short[SR / 10]; // 100ms 一块
        int t = 0;

        while (playing) {
            for (int i = 0; i < pcm.length && playing; i++, t++) {
                float n = (float) (rnd.nextDouble() * 2 - 1); // 白噪音
                float out = 0f;

                if (kind == RAIN) {
                    // 沙沙雨声：两级低通 + 偶发雨滴高频点缀
                    lp += 0.32f * (n - lp);
                    lp2 += 0.22f * (lp - lp2);
                    float drop = 0f;
                    if (rnd.nextInt(3000) < 3) drop = (float) (rnd.nextDouble() - 0.5) * 0.9f;
                    out = lp2 * 2.6f + drop * 0.25f;

                } else if (kind == WAVE) {
                    // 海浪：低频噪音 + 慢 LFO 起伏
                    lp += 0.06f * (n - lp);
                    wavePhase += 2f * (float) Math.PI / (SR * 9f); // 9 秒一个浪
                    float lfo = 0.5f + 0.5f * (float) Math.sin(wavePhase);
                    lfo = (float) Math.pow(lfo, 2.2);
                    out = lp * 3.2f * (0.25f + 0.75f * lfo);

                } else if (kind == FIRE) {
                    // 篝火：低沉底噪 + 随机爆裂
                    lp += 0.08f * (n - lp);
                    if (crackleEnv <= 0f && rnd.nextInt(2200) < 4) {
                        crackleEnv = 0.6f + rnd.nextFloat() * 0.4f;
                    }
                    float crack = 0f;
                    if (crackleEnv > 0f) {
                        crack = (rnd.nextDouble() < 0.5 ? 1 : -1) * crackleEnv;
                        crackleEnv *= 0.90f;
                        if (crackleEnv < 0.02f) crackleEnv = 0f;
                    }
                    out = lp * 2.2f + crack * 0.5f;

                } else { // WIND
                    // 风声：超低通 + 双正弦调幅
                    lp += 0.015f * (n - lp);
                    lp2 += 0.05f * (lp - lp2);
                    windPhase += 2f * (float) Math.PI / (SR * 13f);
                    float lfo = 0.6f + 0.4f * (float) Math.sin(windPhase)
                            + 0.15f * (float) Math.sin(windPhase * 3.7f);
                    out = lp2 * 4.0f * lfo;
                }

                if (out > 1f) out = 1f;
                if (out < -1f) out = -1f;
                pcm[i] = (short) (out * 32767f * 0.6f);
            }
            if (!playing) break;
            try {
                track.write(pcm, 0, pcm.length);
            } catch (Exception e) {
                break;
            }
        }
    }
}
