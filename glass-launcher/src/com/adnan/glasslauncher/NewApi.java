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

    // ---- API 26 (Android 8.0) -------------------------------------------------------------
    /** ApplicationInfo.category, or -1 when unknown. */
    static int appCategory(ApplicationInfo ai) {
        if (SDK < 26 || ai == null) return -1;
        try { return ai.category; } catch (Throwable t) { return -1; }
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
