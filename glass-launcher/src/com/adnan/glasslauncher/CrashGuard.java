package com.adnan.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Keeps the launcher usable no matter what. Any crash is recorded; two crashes within two
 * minutes put the next start into safe mode (a plain app list with recovery buttons), so a
 * bad setting, a vendor firmware quirk or an Android update can never lock the user out of
 * the head unit.
 */
final class CrashGuard implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "GlassLauncher";
    private static final long WINDOW_MS = 2 * 60 * 1000L;
    private final Thread.UncaughtExceptionHandler previous;
    private final SharedPreferences sp;

    private CrashGuard(Context c, Thread.UncaughtExceptionHandler previous) {
        this.previous = previous;
        this.sp = c.getApplicationContext().getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
    }

    static void install(Context c) {
        Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
        if (cur instanceof CrashGuard) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashGuard(c, cur));
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            long now = System.currentTimeMillis();
            long last = sp.getLong("crash_time", 0);
            int count = now - last < WINDOW_MS ? sp.getInt("crash_count", 0) + 1 : 1;
            sp.edit().putInt("crash_count", count).putLong("crash_time", now)
                    .putString("last_error", describe(e)).commit();
        } catch (Throwable ignored) {
        }
        if (previous != null) previous.uncaughtException(t, e);
    }

    /** Records a problem that was caught (screen build failure etc.) without crashing. */
    static void report(Context c, String where, Throwable e) {
        Log.w(TAG, where, e);
        try {
            c.getApplicationContext().getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
                    .putString("last_error", where + ": " + describe(e)).apply();
        } catch (Throwable ignored) {
        }
    }

    static boolean shouldUseSafeMode(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
            return sp.getInt("crash_count", 0) >= 2 && System.currentTimeMillis() - sp.getLong("crash_time", 0) < WINDOW_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Called once the launcher has run fine for a while. */
    static void markStable(Context c) {
        try {
            c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit().putInt("crash_count", 0).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String describe(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 3000 ? s.substring(0, 3000) : s;
    }
}
