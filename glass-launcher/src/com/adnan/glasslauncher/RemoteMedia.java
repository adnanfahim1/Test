package com.adnan.glasslauncher;

import android.graphics.Bitmap;
import android.os.SystemClock;

/** What the phone is playing (Spotify, YouTube Music, ...), as reported by Glass Link. */
final class RemoteMedia {
    boolean active;
    boolean playing;
    String app, pkg, title, artist, album;
    long duration;      // ms, 0 when unknown
    long position;      // ms at receivedAt
    long receivedAt;    // SystemClock.elapsedRealtime() on the head unit
    String artId;
    Bitmap art;
    /** Where the phone's sound comes out: "bt" (Bluetooth, e.g. the car stereo), "wired" or "phone". */
    String route, routeName;
    /** The car stereo the phone uses for calls only ("Media audio" off), when the sound is on the phone. */
    String btCalls;
    /** The app's own buttons (Like, Shuffle, ...): {id, name}. */
    java.util.List<String[]> actions = new java.util.ArrayList<String[]>();
    /** The app's play queue, carried over from the previous message (sent only when it changes). */
    java.util.List<android.media.session.MediaSession.QueueItem> queue =
            new java.util.ArrayList<android.media.session.MediaSession.QueueItem>();
    long activeQueueId = -1;

    boolean throughBluetooth() { return "bt".equals(route); }

    /** True when the music can only be heard on the phone itself. */
    boolean onPhoneSpeaker() { return "phone".equals(route); }

    long positionNow() {
        long p = position;
        if (playing) p += SystemClock.elapsedRealtime() - receivedAt;
        if (duration > 0 && p > duration) p = duration;
        return Math.max(0, p);
    }

    /** Sends play/pause/next/previous/seek/queue/custom to the phone. */
    interface Control { void command(String cmd, long positionMs, long id, String action); }
}
