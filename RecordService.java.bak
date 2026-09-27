package com.wink.pillmate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.DisplayMetrics;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 前台服务：MediaProjection 录屏（开始/暂停/继续/停止） */
public class RecordService extends Service {

    private static final String CHANNEL = "record";
    public static final String EXTRA_RESULT_CODE = "code";
    public static final String EXTRA_RESULT_DATA = "data";
    public static final String ACTION_PAUSE = "com.wink.pillmate.RECORD_PAUSE";
    public static final String ACTION_RESUME = "com.wink.pillmate.RECORD_RESUME";
    public static final String ACTION_STOP = "com.wink.pillmate.RECORD_STOP";

    private MediaProjection projection;
    private VirtualDisplay display;
    private MediaRecorder recorder;
    private File outFile;
    private boolean paused = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String act = intent.getAction();
        if (ACTION_PAUSE.equals(act)) {
            try { if (recorder != null && !paused) { recorder.pause(); paused = true; } } catch (Exception ignored) {}
        } else if (ACTION_RESUME.equals(act)) {
            try { if (recorder != null && paused) { recorder.resume(); paused = false; } } catch (Exception ignored) {}
        } else if (ACTION_STOP.equals(act)) {
            stopRecording();
            stopSelf();
            return START_NOT_STICKY;
        } else {
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startForeground(1, buildNotification("录制中"));
            startRecording(code, data);
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startRecording(int code, Intent data) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels, dpi = dm.densityDpi;
            recorder = new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            File dir = new File(getExternalFilesDir(null), "录屏");
            dir.mkdirs();
            outFile = new File(dir, new SimpleDateFormat("MMdd_HHmmss", Locale.CHINA).format(new Date()) + ".mp4");
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.setVideoEncodingBitRate(8 * 1000 * 1000);
            recorder.setVideoFrameRate(30);
            recorder.setVideoSize(w, h);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.prepare();
            display = projection.createVirtualDisplay("pillmate_rec",
                w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(), null, null);
            recorder.start();
        } catch (Exception e) {
            stopRecording();
            stopSelf();
        }
    }

    private void stopRecording() {
        try { if (recorder != null) { recorder.stop(); recorder.release(); } } catch (Exception ignored) {}
        recorder = null; paused = false;
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        display = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "录屏", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("小工具录屏")
            .setContentText(text)
            .build();
    }
}
