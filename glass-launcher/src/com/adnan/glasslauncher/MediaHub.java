package com.adnan.glasslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.Collections;
import java.util.List;

/**
 * "Now playing" for whichever app is playing, via MediaSessionManager. Reading sessions
 * needs notification access (granted once by the user). Without it, play/pause/next/prev
 * still work by sending media key events, but title/artist/artwork are unknown.
 */
final class MediaHub implements MediaSessionManager.OnActiveSessionsChangedListener {
    interface Listener { void onMediaChanged(); }

    private final Context ctx;
    private final ComponentName listenerComponent;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private Listener listener;
    private boolean registered, running;
    private int savedVolume;
    private long otherPlayingAt;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { notifyChanged(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { notifyChanged(); }
        @Override public void onQueueChanged(List<MediaSession.QueueItem> queue) { notifyChanged(); }
        @Override public void onSessionDestroyed() { pick(null); }
    };

    MediaHub(Context c) {
        ctx = c.getApplicationContext();
        listenerComponent = new ComponentName(ctx, MediaListenerService.class);
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    void setListener(Listener l) { listener = l; }

    boolean hasAccess() {
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(listenerComponent.flattenToString());
    }

    /** The built-in player calls this when it starts, pauses or changes track. */
    void localChanged() {
        if (!running) return;
        if (registered) refresh();
        else onActiveSessionsChanged(null);
    }

    private final Runnable localHook = new Runnable() {
        @Override
        public void run() { localChanged(); }
    };

    /** The built-in player, connected to this hub. */
    LocalPlayer player() {
        LocalPlayer lp = LocalPlayer.get(ctx);
        lp.setOnActive(localHook);
        return lp;
    }

    private MediaController local() {
        LocalPlayer lp = LocalPlayer.peek();
        return lp != null && lp.hasTrack() ? lp.controller() : null;
    }

    boolean isLocal() {
        LocalPlayer lp = LocalPlayer.peek();
        return controller != null && lp != null && controller.getSessionToken().equals(lp.token());
    }

    void start() {
        running = true;
        LocalPlayer lp = LocalPlayer.peek();
        if (lp != null) lp.setOnActive(localHook);
        if (registered) { refresh(); return; }
        if (!hasAccess()) { onActiveSessionsChanged(null); return; }
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(this, listenerComponent, handler);
            registered = true;
            refresh();
        } catch (SecurityException e) {
            registered = false;
            onActiveSessionsChanged(null);
        }
    }

    void stop() {
        running = false;
        if (registered && msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(this); } catch (Exception ignored) {}
        }
        registered = false;
        if (controller != null) controller.unregisterCallback(cb);
        controller = null;
    }

    private void refresh() {
        if (msm == null) return;
        try {
            onActiveSessionsChanged(msm.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            onActiveSessionsChanged(null);
        }
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> list) {
        // Prefer whatever is playing; the built-in player wins while it plays or when it was
        // the last thing that played. Otherwise the most recent other session.
        MediaController loc = local();
        LocalPlayer lp = LocalPlayer.peek();
        MediaController best = null;
        if (loc != null && lp.isPlaying()) best = loc;
        if (best == null && list != null) {
            for (MediaController mc : list) {
                if (loc != null && mc.getSessionToken().equals(loc.getSessionToken())) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    best = mc;
                    otherPlayingAt = SystemClock.elapsedRealtime();
                    break;
                }
            }
        }
        if (best == null && loc != null && lp.lastPlayingAt >= otherPlayingAt) best = loc;
        if (best == null && list != null) {
            for (MediaController mc : list) {
                if (loc == null || !mc.getSessionToken().equals(loc.getSessionToken())) { best = mc; break; }
            }
        }
        if (best == null) best = loc;
        pick(best);
    }

    private void pick(MediaController mc) {
        if (controller != null && mc != null && controller.getSessionToken().equals(mc.getSessionToken())) {
            notifyChanged();
            return;
        }
        if (controller != null) controller.unregisterCallback(cb);
        controller = mc;
        if (controller != null) controller.registerCallback(cb, handler);
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) listener.onMediaChanged();
    }

    // ---- State ---------------------------------------------------------------------------
    /** The built-in player when it is the one shown; read directly (no round trip through Android). */
    private LocalPlayer lp() { return isLocal() ? LocalPlayer.peek() : null; }

    boolean hasSession() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.hasTrack();
        return controller != null && controller.getMetadata() != null;
    }

    String packageName() { return controller != null ? controller.getPackageName() : null; }

    private MediaMetadata meta() { return controller != null ? controller.getMetadata() : null; }

    String title() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.current() != null ? lp.current().title : null;
        MediaMetadata m = meta();
        if (m == null) return null;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return t != null ? t.toString() : null;
    }

    String artist() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.current() != null ? lp.current().artist : null;
        MediaMetadata m = meta();
        if (m == null) return null;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_ALBUM);
        return t != null ? t.toString() : null;
    }

    Bitmap art() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.art();
        MediaMetadata m = meta();
        if (m == null) return null;
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    long duration() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.duration();
        MediaMetadata m = meta();
        return m != null ? m.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
    }

    long position() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.position();
        PlaybackState st = controller != null ? controller.getPlaybackState() : null;
        if (st == null) return 0;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        long d = duration();
        if (d > 0 && pos > d) pos = d;
        return Math.max(0, pos);
    }

    boolean isPlaying() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.isPlaying();
        PlaybackState st = controller != null ? controller.getPlaybackState() : null;
        if (st != null) {
            int s = st.getState();
            return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING || s == PlaybackState.STATE_CONNECTING;
        }
        return audio != null && audio.isMusicActive();
    }

    List<MediaSession.QueueItem> queue() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.queueWindow();
        List<MediaSession.QueueItem> q = controller != null ? controller.getQueue() : null;
        return q != null ? q : Collections.<MediaSession.QueueItem>emptyList();
    }

    long activeQueueId() {
        LocalPlayer lp = lp();
        if (lp != null) return lp.queuePosition();
        PlaybackState st = controller != null ? controller.getPlaybackState() : null;
        return st != null ? st.getActiveQueueItemId() : MediaSession.QueueItem.UNKNOWN_ID;
    }

    // ---- Controls ------------------------------------------------------------------------
    void togglePlay() {
        LocalPlayer lp = lp();
        if (lp != null) { lp.toggle(); return; }
        if (controller != null) {
            if (isPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else {
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    void next() {
        LocalPlayer lp = lp();
        if (lp != null) { lp.next(); return; }
        if (controller != null) controller.getTransportControls().skipToNext();
        else key(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    void prev() {
        LocalPlayer lp = lp();
        if (lp != null) { lp.prev(); return; }
        if (controller != null) controller.getTransportControls().skipToPrevious();
        else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    void seekTo(long ms) {
        LocalPlayer lp = lp();
        if (lp != null) { lp.seekTo(ms); return; }
        if (controller != null) controller.getTransportControls().seekTo(Math.max(0, ms));
    }

    void skipToQueueItem(long id) {
        LocalPlayer lp = lp();
        if (lp != null) { lp.jumpTo((int) id); return; }
        if (controller != null) controller.getTransportControls().skipToQueueItem(id);
    }

    private void key(int code) {
        if (audio == null) return;
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    // ---- Volume (STREAM_MUSIC) -----------------------------------------------------------
    int volume() { return audio != null ? audio.getStreamVolume(AudioManager.STREAM_MUSIC) : 0; }
    int maxVolume() { return audio != null ? Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) : 1; }

    void setVolume(int v) {
        if (audio == null) return;
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(maxVolume(), v)), 0);
        } catch (SecurityException ignored) {
            // Do Not Disturb can block volume changes.
        }
    }

    boolean isMuted() {
        if (audio == null) return false;
        return NewApi.isStreamMute(audio, AudioManager.STREAM_MUSIC) || volume() == 0;
    }

    void toggleMute() {
        if (audio == null) return;
        try {
            if (volume() == 0 && !NewApi.isStreamMute(audio, AudioManager.STREAM_MUSIC)) {
                setVolume(Math.max(1, maxVolume() / 3));
            } else if (!NewApi.toggleMute(audio, AudioManager.STREAM_MUSIC)) {
                // Android 5.x: remember the level and drop to 0, restore on the next tap.
                if (volume() > 0) {
                    savedVolume = volume();
                    setVolume(0);
                } else {
                    setVolume(savedVolume > 0 ? savedVolume : Math.max(1, maxVolume() / 3));
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
