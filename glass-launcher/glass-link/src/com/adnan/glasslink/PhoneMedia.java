package com.adnan.glasslink;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * What music app plays on this phone (Spotify, YouTube Music, ...): title, artist, cover,
 * position and play state go to the car; the car's play/pause/next/previous/seek come back
 * and are passed to the app. Uses the same notification access as navigation.
 */
final class PhoneMedia implements MediaSessionManager.OnActiveSessionsChangedListener {
    private static PhoneMedia instance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context ctx;
    private final ComponentName listener;
    private MediaSessionManager msm;
    private MediaController controller;
    private String lastJson, sentArtId;
    private String artId, artData;
    static volatile String nowPlaying; // for the app screen

    private PhoneMedia(Context c) {
        ctx = c.getApplicationContext();
        listener = new ComponentName(ctx, NavListener.class);
    }

    /** Starts watching once notification access is granted (safe to call often). */
    static void ping(final Context c) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (instance == null) instance = new PhoneMedia(c);
                    instance.start();
                } catch (Throwable ignored) {}
            }
        });
    }

    /** The car connected: send the current state (and cover) again. */
    static void resend() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                PhoneMedia p = instance;
                if (p == null) return;
                p.lastJson = null;
                p.sentArtId = null;
                p.publish();
            }
        });
    }

    /** A command from the car: play, pause, next, prev, seek. */
    static void command(final String cmd, final long pos) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                PhoneMedia p = instance;
                MediaController mc = p != null ? p.controller : null;
                if (mc == null) return;
                try {
                    MediaController.TransportControls t = mc.getTransportControls();
                    if ("play".equals(cmd)) t.play();
                    else if ("pause".equals(cmd)) t.pause();
                    else if ("next".equals(cmd)) t.skipToNext();
                    else if ("prev".equals(cmd)) t.skipToPrevious();
                    else if ("seek".equals(cmd)) t.seekTo(Math.max(0, pos));
                    LinkService.log("Music: the car asked to " + cmd);
                } catch (Throwable e) {
                    LinkService.log("Music: " + mc.getPackageName() + " refused " + cmd);
                }
            }
        });
    }

    private void start() {
        if (msm != null) return;
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(this, listener, MAIN);
            onActiveSessionsChanged(msm.getActiveSessions(listener));
            LinkService.log("Music: watching this phone's music apps");
        } catch (SecurityException e) {
            msm = null; // notification access not granted yet
        }
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController mc : list) {
                if ("com.adnan.glasslauncher".equals(mc.getPackageName())) continue; // the car app, if installed here
                PlaybackState st = mc.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = mc; break; }
                if (best == null) best = mc;
            }
        }
        if (controller != null && best != null && controller.getSessionToken().equals(best.getSessionToken())) {
            publish();
            return;
        }
        if (controller != null) try { controller.unregisterCallback(cb); } catch (Throwable ignored) {}
        controller = best;
        if (controller != null) controller.registerCallback(cb, MAIN);
        publish();
    }

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
        @Override public void onSessionDestroyed() {
            controller = null;
            publish();
            if (msm != null) try { onActiveSessionsChanged(msm.getActiveSessions(listener)); } catch (Throwable ignored) {}
        }
    };

    /** Keeps the car's progress bar in step while music plays. */
    private final Runnable resync = new Runnable() {
        @Override
        public void run() {
            lastJson = null;
            publish();
        }
    };

    private void publish() {
        MAIN.removeCallbacks(resync);
        try {
            MediaController mc = controller;
            MediaMetadata md = mc != null ? mc.getMetadata() : null;
            PlaybackState st = mc != null ? mc.getPlaybackState() : null;
            JSONObject o = new JSONObject().put("t", "media");
            String title = md != null ? text(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE) : null;
            if (mc == null || title == null) {
                o.put("active", false);
                nowPlaying = null;
            } else {
                boolean playing = st != null && (st.getState() == PlaybackState.STATE_PLAYING
                        || st.getState() == PlaybackState.STATE_BUFFERING);
                long pos = 0;
                if (st != null) {
                    pos = st.getPosition();
                    if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
                        pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                    }
                }
                long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (dur > 0 && pos > dur) pos = dur;
                String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                String album = text(md, MediaMetadata.METADATA_KEY_ALBUM, null);
                String app = appName(mc.getPackageName());
                Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                String id = art != null ? title + "|" + artist + "|" + art.getWidth() + "x" + art.getHeight() + "|" + art.getGenerationId() : null;
                if (id != null && !id.equals(artId)) {
                    artId = Integer.toHexString(id.hashCode());
                    artData = encode(art);
                } else if (id == null) {
                    artId = null;
                    artData = null;
                }
                o.put("active", true).put("playing", playing).put("app", app).put("pkg", mc.getPackageName())
                        .put("title", title).put("dur", Math.max(0, dur)).put("pos", Math.max(0, pos));
                if (artist != null) o.put("artist", artist);
                if (album != null) o.put("album", album);
                if (artId != null) o.put("artId", artId);
                nowPlaying = title + (artist != null ? " · " + artist : "") + " (" + app + ")";
                if (playing) MAIN.postDelayed(resync, 10000);
            }
            String json = o.toString();
            if (!json.equals(lastJson)) {
                lastJson = json;
                LinkService.media(json);
            }
            if (artId != null && artData != null && !artId.equals(sentArtId) && LinkService.media(
                    new JSONObject().put("t", "media_art").put("artId", artId).put("data", artData).toString())) {
                sentArtId = artId;
            }
            MainActivity.refreshSoon();
        } catch (Throwable ignored) {
        }
    }

    private static String text(MediaMetadata md, String k1, String k2) {
        CharSequence c = md.getText(k1);
        if ((c == null || c.length() == 0) && k2 != null) c = md.getText(k2);
        if (c == null) return null;
        String s = c.toString().trim();
        return s.length() == 0 ? null : s;
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** Cover as a JPEG of at most 320 px (small enough for Bluetooth). */
    private static String encode(Bitmap b) {
        try {
            int max = Math.max(b.getWidth(), b.getHeight());
            if (max > 320) {
                float s = 320f / max;
                b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)), true);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }
}
