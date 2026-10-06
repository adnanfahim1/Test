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
    private boolean registered;
    private int savedVolume;

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

    void start() {
        if (registered) { refresh(); return; }
        if (!hasAccess()) { notifyChanged(); return; }
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(this, listenerComponent, handler);
            registered = true;
            refresh();
        } catch (SecurityException e) {
            registered = false;
            notifyChanged();
        }
    }

    void stop() {
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
        MediaController best = null;
        if (list != null) {
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = mc; break; }
            }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
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
    boolean hasSession() { return controller != null && controller.getMetadata() != null; }

    String packageName() { return controller != null ? controller.getPackageName() : null; }

    private MediaMetadata meta() { return controller != null ? controller.getMetadata() : null; }

    String title() {
        MediaMetadata m = meta();
        if (m == null) return null;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return t != null ? t.toString() : null;
    }

    String artist() {
        MediaMetadata m = meta();
        if (m == null) return null;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (t == null) t = m.getText(MediaMetadata.METADATA_KEY_ALBUM);
        return t != null ? t.toString() : null;
    }

    Bitmap art() {
        MediaMetadata m = meta();
        if (m == null) return null;
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    long duration() {
        MediaMetadata m = meta();
        return m != null ? m.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
    }

    long position() {
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
        PlaybackState st = controller != null ? controller.getPlaybackState() : null;
        if (st != null) {
            int s = st.getState();
            return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING || s == PlaybackState.STATE_CONNECTING;
        }
        return audio != null && audio.isMusicActive();
    }

    List<MediaSession.QueueItem> queue() {
        List<MediaSession.QueueItem> q = controller != null ? controller.getQueue() : null;
        return q != null ? q : Collections.<MediaSession.QueueItem>emptyList();
    }

    long activeQueueId() {
        PlaybackState st = controller != null ? controller.getPlaybackState() : null;
        return st != null ? st.getActiveQueueItemId() : MediaSession.QueueItem.UNKNOWN_ID;
    }

    // ---- Controls ------------------------------------------------------------------------
    void togglePlay() {
        if (controller != null) {
            if (isPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else {
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
        else key(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    void prev() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
        else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    void seekTo(long ms) {
        if (controller != null) controller.getTransportControls().seekTo(Math.max(0, ms));
    }

    void skipToQueueItem(long id) {
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
