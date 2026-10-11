package com.waze;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.TextView;

/** TEST ONLY: a fake navigation notification (custom layout, like newer Google Maps) and a playing media session. */
public class TestActivity extends Activity {
    static MediaSession session;
    static int track = 1;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        status = new TextView(this);
        status.setTextSize(22);
        setContentView(status);
        postNav();
        if (session == null) startMedia();
        status.setText("E2E test app running: track " + track);
    }

    private void postNav() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("navigation", "Navigation", NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, "navigation");
        } else {
            nb = new Notification.Builder(this);
        }
        RemoteViews rv = new RemoteViews(getPackageName(), R.layout.nav);
        rv.setTextViewText(R.id.dist, "300 m");
        rv.setTextViewText(R.id.instr, "Turn right onto Test Street");
        rv.setTextViewText(R.id.eta, "ETA 10:45");
        rv.setImageViewBitmap(R.id.arrow, arrow());
        nb.setSmallIcon(android.R.drawable.ic_dialog_map).setCategory(Notification.CATEGORY_NAVIGATION)
                .setContent(rv);
        nm.notify(1, nb.build());
    }

    private static Bitmap arrow() {
        Bitmap bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(12);
        c.drawLine(30, 90, 30, 30, p);
        c.drawLine(30, 30, 80, 30, p);
        return bm;
    }

    private void startMedia() {
        session = new MediaSession(this, "e2e");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { cmd("play"); setState(true); }
            @Override public void onPause() { cmd("pause"); setState(false); }
            @Override public void onSkipToNext() { cmd("next"); track++; setMeta(); setState(true); }
            @Override public void onSkipToPrevious() { cmd("prev"); if (track > 1) track--; setMeta(); setState(true); }
            @Override public void onSeekTo(long pos) { cmd("seek " + pos); }
        });
        setMeta();
        setState(true);
        session.setActive(true);
    }

    private void cmd(String c) {
        Log.i("E2E_MEDIA", "cmd=" + c);
        if (status != null) status.setText("E2E test app: last command " + c + ", track " + track);
    }

    private static void setMeta() {
        Bitmap art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
        art.eraseColor(track % 2 == 1 ? 0xFF1DB954 : 0xFFE0784A);
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "E2E Test Song " + track)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Glass Tester")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180000)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
    }

    private static void setState(boolean playing) {
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 30000, 1f, SystemClock.elapsedRealtime())
                .build());
    }
}
