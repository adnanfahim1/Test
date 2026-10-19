package com.adnan.glasslink;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
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
    private String lastJson, sentArtId, lastQueue;
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

    /** Bluetooth audio changed: send the state again (where the sound goes). */
    static void refresh() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                PhoneMedia p = instance;
                if (p == null) return;
                p.lastJson = null;
                p.publish();
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
                p.lastQueue = null;
                p.publish();
            }
        });
    }

    /** A command from the car: play, pause, next, prev, seek, queue (jump to an item), custom. */
    static void command(final String cmd, final long pos, final long id, final String action) {
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
                    else if ("queue".equals(cmd)) t.skipToQueueItem(id);
                    else if ("custom".equals(cmd) && action != null) t.sendCustomAction(action, p.actionExtras(action));
                    else return;
                    LinkService.log("Music: the car asked to " + ("custom".equals(cmd) ? action : cmd));
                } catch (Throwable e) {
                    LinkService.log("Music: " + mc.getPackageName() + " refused " + cmd);
                }
            }
        });
    }

    private android.os.Bundle actionExtras(String action) {
        try {
            PlaybackState st = controller != null ? controller.getPlaybackState() : null;
            if (st != null) {
                for (PlaybackState.CustomAction ca : st.getCustomActions()) {
                    if (action.equals(ca.getAction())) return ca.getExtras();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Where this phone's music comes out: {"bt", name} for Bluetooth audio (car, earbuds), else {"phone", null}. */
    static String[] route(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            String wired = null;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                // 26/27/30 = Bluetooth LE headset / speaker / broadcast (Android 12+)
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 26 || t == 27 || t == 30) {
                    CharSequence n = d.getProductName();
                    return new String[]{"bt", n != null && n.length() > 0 ? n.toString() : "Bluetooth audio"};
                }
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                        || t == AudioDeviceInfo.TYPE_USB_DEVICE || t == 22 /* USB headset */) {
                    wired = "Cable / USB audio";
                }
            }
            if (wired != null) return new String[]{"wired", wired};
        } catch (Throwable ignored) {}
        return new String[]{"phone", null};
    }

    private final AudioDeviceCallback devices = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { lastJson = null; publish(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { lastJson = null; publish(); }
    };

    private void start() {
        if (msm != null) return;
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(this, listener, MAIN);
            try {
                ((AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE)).registerAudioDeviceCallback(devices, MAIN);
            } catch (Throwable ignored) {}
            BtAudio.init(ctx);
            try {
                android.content.IntentFilter f = new android.content.IntentFilter("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
                f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
                android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, android.content.Intent i) { refresh(); }
                };
                if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
                else ctx.registerReceiver(r, f);
            } catch (Throwable ignored) {}
            onActiveSessionsChanged(msm.getActiveSessions(listener));
            LinkService.log("Music: watching this phone's music apps");
        } catch (SecurityException e) {
            msm = null; // notification access not granted yet
        } catch (Throwable t) {
            msm = null; // anything else: try again on the next ping
            LinkService.log("Music: couldn't watch music apps yet (" + t.getClass().getSimpleName() + "); retrying");
        }
    }

    /** Looks at the music apps again (in case Android missed telling us). Safe to call often. */
    static void rescan(final Context c) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (instance == null) instance = new PhoneMedia(c);
                    PhoneMedia p = instance;
                    if (p.msm == null) { p.start(); return; }
                    p.onActiveSessionsChanged(p.msm.getActiveSessions(p.listener));
                } catch (SecurityException e) {
                    if (instance != null) instance.msm = null;
                } catch (Throwable ignored) {}
            }
        });
    }

    /** True once Glass Link can read this phone's music apps. */
    static boolean watching() { return instance != null && instance.msm != null; }

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
        @Override public void onQueueChanged(List<MediaSession.QueueItem> queue) { publish(); }
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
                String[] r = route(ctx);
                o.put("route", r[0]);
                if (r[1] != null) o.put("routeName", r[1]);
                if ("phone".equals(r[0])) {
                    BtAudio.init(ctx);
                    String calls = BtAudio.callsOnly();
                    if (calls != null) o.put("btCalls", calls); // connected for calls only: Media audio is off
                }
                JSONArray acts = new JSONArray();
                if (st != null) {
                    for (PlaybackState.CustomAction ca : st.getCustomActions()) {
                        CharSequence n = ca.getName();
                        if (ca.getAction() == null || n == null || n.length() == 0) continue;
                        acts.put(new JSONObject().put("id", ca.getAction()).put("name", n.toString()));
                        if (acts.length() >= 4) break;
                    }
                    o.put("qid", st.getActiveQueueItemId());
                }
                if (acts.length() > 0) o.put("actions", acts);
                nowPlaying = title + (artist != null ? " · " + artist : "") + " (" + app + ")";
                if (playing) MAIN.postDelayed(resync, 10000);
            }
            String json = o.toString();
            if (!json.equals(lastJson)) {
                lastJson = json;
                LinkService.media(json);
            }
            String q = queueJson(mc);
            if (!q.equals(lastQueue) && LinkService.media(q)) lastQueue = q;
            if (artId != null && artData != null && !artId.equals(sentArtId) && LinkService.media(
                    new JSONObject().put("t", "media_art").put("artId", artId).put("data", artData).toString())) {
                sentArtId = artId;
            }
            MainActivity.refreshSoon();
        } catch (Throwable ignored) {
        }
    }

    /** The app's play queue ("Up next"), at most 60 items. */
    private static String queueJson(MediaController mc) {
        try {
            JSONArray items = new JSONArray();
            List<MediaSession.QueueItem> q = mc != null ? mc.getQueue() : null;
            if (q != null) {
                for (MediaSession.QueueItem it : q) {
                    MediaDescription d = it.getDescription();
                    if (d == null || d.getTitle() == null) continue;
                    JSONObject o = new JSONObject().put("id", it.getQueueId()).put("title", d.getTitle().toString());
                    if (d.getSubtitle() != null) o.put("sub", d.getSubtitle().toString());
                    items.put(o);
                    if (items.length() >= 60) break;
                }
            }
            return new JSONObject().put("t", "media_queue").put("items", items).toString();
        } catch (Throwable t) {
            return "{\"t\":\"media_queue\",\"items\":[]}";
        }
    }

    private static String text(MediaMetadata md, String k1, String k2) {
        CharSequence c = md.getText(k1);
        if ((c == null || c.length() == 0) && k2 != null) c = md.getText(k2);
        if (c == null) return null;
        String s = c.toString().trim();
        return s.length() == 0 ? null : s;
    }

    private String appName(String pkg) { return AppNames.of(ctx, pkg); }

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
