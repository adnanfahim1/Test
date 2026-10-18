package com.adnan.glasslauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * "Always open on Home": when the car is switched on, the launcher's Home screen shows,
 * whatever app or screen was open when the car was switched off.
 *
 * Head units power on in three ways, all covered without vendor-specific code:
 *  - a real boot (BOOT_COMPLETED / QUICKBOOT_POWERON, see {@link Boot});
 *  - waking from sleep: the device was suspended, noticed because the time spent asleep
 *    (elapsedRealtime minus uptimeMillis) jumps;
 *  - the screen coming back on after being off for a while (ACC off without sleeping).
 * Android 10+ only lets an app in the background open its screen with "Display over other
 * apps" (granted by the setup script, or in Settings › Display & home); without it the
 * launcher still resets to Home the next time it is shown.
 */
final class HomeKeeper {
    static final String TAG = "GlassHome";
    static final String EXTRA = "home_reset";
    /** Screen off at least this long counts as the car having been switched off. */
    static final long SCREEN_OFF_MS = 90 * 1000L;
    /** Asleep at least this long counts as the car having been switched off. */
    static final long SLEEP_MS = 20 * 1000L;
    private static final long CHECK_MS = 5000;

    private static boolean installed;
    private static volatile boolean pending;
    private static volatile long pendingAt;
    /** A reset not shown within this time is dropped (e.g. "Display over other apps" was off). */
    static final long PENDING_MS = 2 * 60 * 1000L;
    private static long screenOffAt = -1;
    private static long sleptBase = -1;
    private static long lastBring;
    private static Context app;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private HomeKeeper() {}

    /** Starts watching (safe to call often; runs for the life of the process). */
    static void install(Context c) {
        Context ac = c.getApplicationContext();
        if (installed && app == ac) return;
        installed = true;
        app = ac;
        lastBring = 0;
        screenOffAt = -1;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        try { NewApi.registerReceiver(app, screen, f); } catch (Throwable ignored) {}
        sleptBase = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
        H.removeCallbacks(watch);
        H.postDelayed(watch, CHECK_MS);
    }

    private static final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOffAt = SystemClock.elapsedRealtime();
                pending = false; // an old reset that never showed doesn't carry over
            } else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                long off = screenOffAt < 0 ? 0 : SystemClock.elapsedRealtime() - screenOffAt;
                screenOffAt = -1;
                if (off >= SCREEN_OFF_MS) carOn(c, "screen on after " + off / 1000 + " s");
            }
        }
    };

    /** Main-thread timer; it doesn't run while the device sleeps, so the first run after waking sees the gap. */
    private static final Runnable watch = new Runnable() {
        @Override
        public void run() {
            long slept = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis();
            long gap = slept - sleptBase;
            sleptBase = slept;
            if (gap >= SLEEP_MS) carOn(app, "woke after " + gap / 1000 + " s asleep");
            H.postDelayed(this, CHECK_MS);
        }
    };

    /** The car was (probably) just switched on: show Home. */
    static void carOn(Context c, String why) {
        if (c == null) return;
        Prefs p = new Prefs(c);
        if (!p.alwaysHome()) return;
        long now = SystemClock.elapsedRealtime();
        if (lastBring > 0 && now - lastBring < 10000) return; // one reset per power-on
        lastBring = now;
        pending = true;
        pendingAt = now;
        Log.i(TAG, "Car on (" + why + "): opening Home");
        bringHome(c);
    }

    /** Opens the launcher on its Home screen. Never throws. */
    static boolean bringHome(Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(c.getPackageName(), MainActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    .putExtra(EXTRA, true);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Couldn't open Home: " + t);
            return false;
        }
    }

    /** True once after a power-on: the launcher shows Home instead of the screen it had open. */
    static boolean consumePending() {
        boolean p = pending && SystemClock.elapsedRealtime() - pendingAt < PENDING_MS;
        pending = false;
        return p;
    }

    /** True when Android lets the launcher open itself from the background (overlay permission). */
    static boolean canOpenFromBackground(Context c) {
        return NewApi.SDK < 29 || NewApi.canDrawOverlays(c);
    }

    /** Boot of the head unit (some units only "quick boot"). */
    public static final class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(final Context c, Intent i) {
            final Context ac = c.getApplicationContext();
            install(ac);
            final PendingResult r = goAsync();
            // Give the firmware a moment: some units reopen the last app right after boot.
            H.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try { carOn(ac, "boot"); } finally { r.finish(); }
                }
            }, 6000);
        }
    }
}
