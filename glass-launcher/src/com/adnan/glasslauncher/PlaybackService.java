package com.adnan.glasslauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * Keeps on-device music playing while other apps (maps, phone) are in front, and shows the
 * standard media notification with play/pause, previous, next and close.
 */
public final class PlaybackService extends Service {
    private static final int ID = 4107;
    static final String ACTION_TOGGLE = "com.adnan.glasslauncher.TOGGLE", ACTION_NEXT = "com.adnan.glasslauncher.NEXT",
            ACTION_PREV = "com.adnan.glasslauncher.PREV", ACTION_CLOSE = "com.adnan.glasslauncher.CLOSE";

    private static PlaybackService running;
    private boolean foreground;

    /** Called by the player on every change: starts, refreshes or stops the service. */
    static void update(Context c, LocalPlayer p) {
        if (running != null) {
            running.refresh(p);
            return;
        }
        if (!p.isPlaying()) return;
        try {
            NewApi.startForegroundService(c, new Intent(c, PlaybackService.class));
        } catch (Throwable ignored) {
            // Android can refuse to start services from the background; playback continues
            // while the launcher process is alive.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LocalPlayer p = LocalPlayer.get(this);
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(a)) p.toggle();
        else if (ACTION_NEXT.equals(a)) p.next();
        else if (ACTION_PREV.equals(a)) p.prev();
        else if (ACTION_CLOSE.equals(a)) p.stop();
        refresh(p);
        return START_NOT_STICKY;
    }

    private void refresh(LocalPlayer p) {
        try {
            if (!p.hasTrack()) {
                // Android 8+ requires startForeground() after startForegroundService(), even
                // when there is nothing left to show.
                if (!foreground) startForeground(ID, NewApi.notificationBuilder(this, "playback", "Music playing on this device")
                        .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Music").build());
                foreground = false;
                stopForeground(true);
                stopSelf();
                return;
            }
            Notification n = build(p);
            // Stays in the foreground while a song is loaded, so the steering-wheel keys can
            // resume it later without Android blocking the start.
            startForeground(ID, n);
            foreground = true;
        } catch (Throwable t) {
            if (!foreground) stopSelf();
        }
    }

    private Notification build(LocalPlayer p) {
        LocalMusic.Track t = p.current();
        Notification.Builder b = NewApi.notificationBuilder(this, "playback", "Music playing on this device");
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t != null ? t.title : "Music")
                .setContentText(t != null ? t.subtitle() : "")
                .setLargeIcon(p.art())
                .setShowWhen(false)
                .setOngoing(p.isPlaying())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 1,
                        new Intent(this, MainActivity.class).putExtra("screen", "music").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        NewApi.immutable(PendingIntent.FLAG_UPDATE_CURRENT)))
                .addAction(android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREV, 2))
                .addAction(p.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        p.isPlaying() ? "Pause" : "Play", action(ACTION_TOGGLE, 3))
                .addAction(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT, 4))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", action(ACTION_CLOSE, 5))
                .setStyle(new Notification.MediaStyle().setMediaSession(p.token()).setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent action(String act, int code) {
        return PendingIntent.getService(this, code, new Intent(this, PlaybackService.class).setAction(act),
                NewApi.immutable(PendingIntent.FLAG_UPDATE_CURRENT));
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
