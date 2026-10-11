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

    long positionNow() {
        long p = position;
        if (playing) p += SystemClock.elapsedRealtime() - receivedAt;
        if (duration > 0 && p > duration) p = duration;
        return Math.max(0, p);
    }

    /** Sends play/pause/next/previous/seek to the phone. */
    interface Control { void command(String cmd, long positionMs); }
}
