package com.adnan.glasslauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.media.AudioManager;
import android.os.Build;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * The ONLY file that calls Android APIs newer than 5.0 (API 21). Every method here must
 * be called behind the matching {@code Build.VERSION.SDK_INT} check. The build compiles
 * all other sources against the API 21 framework, so a newer call anywhere else fails
 * the build instead of crashing an older head unit.
 */
final class NewApi {
    private NewApi() {}

    static final int SDK = Build.VERSION.SDK_INT;

    // ---- API 23 (Android 6.0) -------------------------------------------------------------
    static boolean granted(Context c, String permission) {
        if (SDK < 23) return true; // granted at install time
        try {
            return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void request(Activity a, String[] permissions, int code) {
        if (SDK < 23) return;
        try {
            a.requestPermissions(permissions, code);
        } catch (Throwable ignored) {
        }
    }

    static boolean isStreamMute(AudioManager am, int stream) {
        if (SDK < 23) return false;
        try { return am.isStreamMute(stream); } catch (Throwable t) { return false; }
    }

    /** Toggles mute; returns false when the platform can't (caller falls back). */
    static boolean toggleMute(AudioManager am, int stream) {
        if (SDK < 23) return false;
        try {
            am.adjustStreamVolume(stream, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** PendingIntent flags with FLAG_IMMUTABLE added where Android knows it (required on 12+). */
    static int immutable(int flags) {
        return SDK >= 23 ? flags | android.app.PendingIntent.FLAG_IMMUTABLE : flags;
    }

    /** PendingIntent flags with FLAG_MUTABLE (Android 12+ needs it when the system adds extras). */
    static int mutable(int flags) {
        return SDK >= 31 ? flags | android.app.PendingIntent.FLAG_MUTABLE : flags;
    }

    // ---- API 24 (Android 7.0) -------------------------------------------------------------
    /** "SD card", "USB drive" etc. for the storage volume holding {@code f}, or null. */
    static String volumeDescription(Context c, java.io.File f) {
        if (SDK < 24) return null;
        try {
            android.os.storage.StorageManager sm = (android.os.storage.StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            android.os.storage.StorageVolume v = sm != null ? sm.getStorageVolume(f) : null;
            return v != null ? v.getDescription(c) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- API 26 (Android 8.0) -------------------------------------------------------------
    static void startForegroundService(Context c, android.content.Intent i) {
        if (SDK >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    /** Notification builder with a low-importance channel on Android 8+. */
    static android.app.Notification.Builder notificationBuilder(Context c, String channel, String channelName) {
        if (SDK < 26) return new android.app.Notification.Builder(c);
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(channel) == null) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(channel, channelName,
                        android.app.NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {}
        return new android.app.Notification.Builder(c, channel);
    }

    /** ApplicationInfo.category, or -1 when unknown. */
    static int appCategory(ApplicationInfo ai) {
        if (SDK < 26 || ai == null) return -1;
        try { return ai.category; } catch (Throwable t) { return -1; }
    }

    // ---- API 29 (Android 10) --------------------------------------------------------------
    /** Media-store volume names (internal + every SD card / USB drive), or null before 10. */
    static java.util.List<String> audioVolumes(Context c) {
        if (SDK < 29) return null;
        try {
            return new java.util.ArrayList<String>(android.provider.MediaStore.getExternalVolumeNames(c));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Android 10+ no longer lets apps switch Wi-Fi or join networks directly: show the system panel. */
    static boolean openWifiPanel(Activity a) {
        if (SDK < 29) return false;
        try {
            a.startActivity(new android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- API 30 (Android 11) --------------------------------------------------------------
    /** Hides the status bar (swipe to reveal). Returns false when not handled here. */
    static boolean hideStatusBar(Window w) {
        if (SDK < 30) return false;
        try {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return false;
            c.hide(WindowInsets.Type.statusBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** System bar insets as {left, top, right, bottom}, or null when not handled here. */
    static int[] systemBars(WindowInsets in) {
        if (SDK < 30 || in == null) return null;
        try {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new int[]{i.left, i.top, i.right, i.bottom};
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- API 33 (Android 13) --------------------------------------------------------------
    static void registerReceiver(Context c, BroadcastReceiver r, IntentFilter f) {
        if (SDK >= 33) {
            c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            c.registerReceiver(r, f);
        }
    }
}
