package com.adnan.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Keeps the launcher usable on any head unit, and explains what went wrong.
 *
 * Start-up ladder (decided at the beginning of every launch):
 *   - a start that never reached "stable" (Java crash, native/graphics crash, or the firmware
 *     closing the app) counts as a failed boot;
 *   - after 1 failed boot the launcher starts in LITE mode (software drawing, no animations,
 *     no 360° spin) which avoids most GPU-driver problems on cheap head units;
 *   - after 2+ failed boots it starts in SAFE mode (plain app list + recovery buttons).
 * Any Java crash also opens {@link HelpActivity} in its own process, showing the error with a
 * Share button, instead of the app silently disappearing.
 */
final class CrashGuard implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "GlassLauncher";
    static final String K_BOOT_PENDING = "boot_pending", K_FAILED_BOOTS = "failed_boots", K_STAGE = "stage",
            K_LITE = "lite_mode", K_LITE_AUTO = "lite_auto";
    private final Thread.UncaughtExceptionHandler previous;
    private final Context app;

    private CrashGuard(Context c, Thread.UncaughtExceptionHandler previous) {
        this.previous = previous;
        this.app = c.getApplicationContext();
    }

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
    }

    static void install(Context c) {
        Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
        if (cur instanceof CrashGuard) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashGuard(c, cur));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            SharedPreferences s = sp(app);
            s.edit().putInt("crash_count", s.getInt("crash_count", 0) + 1)
                    .putLong("crash_time", System.currentTimeMillis())
                    .putString("last_error", "During \"" + s.getString(K_STAGE, "?") + "\":\n" + describe(e))
                    .commit();
            // Show the error in the Help screen (separate process, so it survives this crash).
            Intent i = new Intent(app, HelpActivity.class)
                    .putExtra("crashed", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            app.startActivity(i);
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        } catch (Throwable ignored) {
            if (previous != null) previous.uncaughtException(t, e);
        }
    }

    /** Records how far start-up got (shown in the Help screen if the app dies). */
    static void stage(Context c, String s) {
        try { sp(c).edit().putString(K_STAGE, s).commit(); } catch (Throwable ignored) {}
    }

    /**
     * Called first thing in onCreate. Returns 0 = normal, 1 = lite mode, 2 = safe mode.
     */
    static int beginBoot(Context c) {
        try {
            SharedPreferences s = sp(c);
            int failed = s.getInt(K_FAILED_BOOTS, 0);
            if (s.getBoolean(K_BOOT_PENDING, false)) {
                failed++;
                String st = s.getString(K_STAGE, "?");
                if (s.getString("last_error", null) == null || failed > s.getInt("reported_boots", 0)) {
                    s.edit().putString("last_error", "The previous start stopped during \"" + st
                            + "\" without a Java error (closed by the system/firmware, or a graphics-driver crash).")
                            .putInt("reported_boots", failed).commit();
                }
            }
            SharedPreferences.Editor e = s.edit().putBoolean(K_BOOT_PENDING, true).putInt(K_FAILED_BOOTS, failed);
            if (failed >= 1 && !s.getBoolean(K_LITE, false)) e.putBoolean(K_LITE, true).putBoolean(K_LITE_AUTO, true);
            e.commit();
            if (failed >= 2) return 2;
            return s.getBoolean(K_LITE, false) ? 1 : 0;
        } catch (Throwable t) {
            return 1;
        }
    }

    /** Start-up finished and the launcher has been on screen for a while. */
    static void markStable(Context c) {
        try {
            sp(c).edit().putBoolean(K_BOOT_PENDING, false).putInt(K_FAILED_BOOTS, 0).putInt("reported_boots", 0)
                    .putInt("crash_count", 0).putString(K_STAGE, "running").commit();
        } catch (Throwable ignored) {
        }
    }

    /** Leaving normally (e.g. the user opened another app before start-up finished). */
    static void endBootCleanly(Context c) {
        try { sp(c).edit().putBoolean(K_BOOT_PENDING, false).commit(); } catch (Throwable ignored) {}
    }

    /** Records a problem that was caught (screen build failure etc.) without crashing. */
    static void report(Context c, String where, Throwable e) {
        Log.w(TAG, where, e);
        try {
            sp(c).edit().putString("last_error", where + ": " + describe(e)).apply();
        } catch (Throwable ignored) {
        }
    }

    static boolean shouldUseSafeMode(Context c) {
        try {
            return sp(c).getInt(K_FAILED_BOOTS, 0) >= 2;
        } catch (Throwable t) {
            return false;
        }
    }

    static String describe(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
