package com.waze;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaDescription;
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

import java.util.ArrayList;
import java.util.List;

/**
 * TEST ONLY: a fake navigation notification (custom layout, like newer Google Maps), a playing
 * media session with a queue and a "Like" button, and (with --ez msg true) a chat message
 * that can be answered or marked read.
 */
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
        if (getIntent().getBooleanExtra("msg", false)) postMessage(this);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("msg", false)) postMessage(this);
    }

    @SuppressWarnings("deprecation")
    static void postMessage(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("chat", "Chats", NotificationManager.IMPORTANCE_DEFAULT));
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent reply = PendingIntent.getBroadcast(c, 1, new Intent(c, Reply.class).setAction("reply"),
                PendingIntent.FLAG_UPDATE_CURRENT | mutable);
        PendingIntent read = PendingIntent.getBroadcast(c, 2, new Intent(c, Reply.class).setAction("read"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder ra = new Notification.Action.Builder(android.R.drawable.ic_menu_send, "Reply", reply)
                .addRemoteInput(new RemoteInput.Builder("e2e_reply").setLabel("Reply").build());
        if (Build.VERSION.SDK_INT >= 28) ra.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY);
        Notification.Action.Builder ma = new Notification.Action.Builder(android.R.drawable.ic_menu_view, "Mark as read", read);
        if (Build.VERSION.SDK_INT >= 28) ma.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ);
        Notification n = new Notification.Builder(c, "chat")
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setStyle(new Notification.MessagingStyle("Me").addMessage("Where are you? E2E message", System.currentTimeMillis(), "Alex"))
                .addAction(ra.build())
                .addAction(ma.build())
                .build();
        nm.notify(7, n);
        Log.i("E2E_MSG", "posted");
    }

    /** Receives the reply / mark-as-read sent from the car through Glass Link. */
    public static final class Reply extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            if ("read".equals(i.getAction())) {
                Log.i("E2E_MSG", "read");
                return;
            }
            Bundle r = RemoteInput.getResultsFromIntent(i);
            CharSequence t = r != null ? r.getCharSequence("e2e_reply") : null;
            Log.i("E2E_MSG", "reply=" + t);
        }
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
            @Override public void onSkipToQueueItem(long id) { cmd("queue " + id); track = (int) (id - 100); setMeta(); setState(true); }
            @Override public void onCustomAction(String action, Bundle extras) { cmd("custom=" + action); }
        });
        List<MediaSession.QueueItem> q = new ArrayList<MediaSession.QueueItem>();
        for (int i = 1; i <= 3; i++) {
            q.add(new MediaSession.QueueItem(new MediaDescription.Builder().setMediaId("q" + i)
                    .setTitle("E2E Test Song " + i).setSubtitle("Queue item " + i).build(), 100 + i));
        }
        session.setQueue(q);
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
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM)
                .addCustomAction(new PlaybackState.CustomAction.Builder("like", "Like", android.R.drawable.star_off).build())
                .setActiveQueueItemId(100 + track)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 30000, 1f, SystemClock.elapsedRealtime())
                .build());
    }
}
