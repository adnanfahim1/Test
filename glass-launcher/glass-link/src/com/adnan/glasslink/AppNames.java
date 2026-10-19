package com.adnan.glasslink;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Locale;

/**
 * Friendly app names ("Spotify", not "com.spotify.music") for what the car shows. Android 11+
 * only reveals apps listed under &lt;queries&gt; in the manifest (launchable apps are); if a name
 * still can't be read, well-known apps are named here and others are guessed from the package.
 */
final class AppNames {
    private static final String[][] KNOWN = {
            {"com.spotify.music", "Spotify"}, {"com.google.android.apps.youtube.music", "YouTube Music"},
            {"com.google.android.youtube", "YouTube"}, {"com.amazon.mp3", "Amazon Music"},
            {"com.apple.android.music", "Apple Music"}, {"deezer.android.app", "Deezer"},
            {"com.soundcloud.android", "SoundCloud"}, {"com.aspiro.tidal", "TIDAL"},
            {"com.pandora.android", "Pandora"}, {"com.audible.application", "Audible"},
            {"com.google.android.apps.podcasts", "Google Podcasts"}, {"au.com.shiftyjelly.pocketcasts", "Pocket Casts"},
            {"com.whatsapp", "WhatsApp"}, {"com.whatsapp.w4b", "WhatsApp Business"},
            {"com.google.android.apps.messaging", "Messages"}, {"com.samsung.android.messaging", "Messages"},
            {"org.telegram.messenger", "Telegram"}, {"org.thoughtcrime.securesms", "Signal"},
            {"com.facebook.orca", "Messenger"}, {"com.instagram.android", "Instagram"},
            {"com.viber.voip", "Viber"}, {"com.imo.android.imoim", "imo"},
            {"com.google.android.apps.maps", "Google Maps"}, {"com.waze", "Waze"}};
    private static final String[] SKIP = {"com", "org", "net", "android", "app", "apps", "mobile", "music", "client",
            "google", "media", "player", "www", "co", "uk", "au", "de", "in", "bd", "io", "me", "tv", "fm", "us", "my"};

    private AppNames() {}

    static String of(Context c, String pkg) {
        if (pkg == null) return null;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0 && !l.toString().equals(pkg)) return l.toString();
        } catch (Throwable ignored) {}
        return guess(pkg);
    }

    /** Name without asking Android: known apps, else the main word of the package ("com.foo.bar" → "Foo"). */
    static String guess(String pkg) {
        for (String[] k : KNOWN) if (k[0].equals(pkg)) return k[1];
        for (String part : pkg.split("\\.")) {
            boolean skip = part.length() < 2;
            for (String s : SKIP) if (s.equals(part)) skip = true;
            if (!skip) return part.substring(0, 1).toUpperCase(Locale.US) + part.substring(1);
        }
        return "Music app";
    }
}
