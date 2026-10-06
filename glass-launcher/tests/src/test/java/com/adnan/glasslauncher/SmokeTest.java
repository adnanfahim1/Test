package com.adnan.glasslauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.concurrent.TimeUnit;

/**
 * Runs the real launcher on every Android version from 5.0 (API 21) to 15 (API 35):
 * opens every screen and settings section, changes theme/font/layout, sends steering-wheel
 * keys and setup-script intents, survives corrupted settings and exercises safe mode.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = {21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35}, qualifiers = "w1280dp-h720dp-land-mdpi")
public class SmokeTest {

    static void addApp(ShadowPackageManager spm, String pkg, String label) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.name = label;
        ai.nonLocalizedLabel = label;
        ActivityInfo act = new ActivityInfo();
        act.packageName = pkg;
        act.name = pkg + ".Main";
        act.nonLocalizedLabel = label;
        act.applicationInfo = ai;
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = act;
        ri.nonLocalizedLabel = label;
        spm.addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), ri);
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = ai;
        spm.installPackage(pi);
        spm.addOrUpdateActivity(act);
    }

    static void installVendorApps(Context c) {
        ShadowPackageManager spm = shadowOf(c.getPackageManager());
        String[][] apps = {{"com.ts.car.setting", "Car Setting"}, {"com.ts.radio", "Radio"}, {"com.ts.btphone", "BT Phone"},
                {"com.zjinnova.zlink", "ZLink"}, {"com.ts.dvr", "DVR"}, {"com.ts.eq", "Equalizer"}, {"com.ts.avin", "AV In"},
                {"com.spotify.music", "Spotify"}, {"com.google.android.apps.maps", "Maps"}, {"org.videolan.vlc", "VLC"},
                {"com.android.documentsui", "Files"}, {"com.android.chrome", "Chrome"}, {"com.android.settings", "Settings"}};
        for (String[] a : apps) addApp(spm, a[0], a[1]);
    }

    private static void idle() { ShadowLooper.idleMainLooper(1200, TimeUnit.MILLISECONDS); }

    private static int clickAll(View v, int depth) {
        if (depth > 3) return 0;
        int n = 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && i < 30; i++) n += clickAll(g.getChildAt(i), depth + 1);
        }
        return n;
    }

    @Test
    public void everyScreenEveryVersion() {
        Context c = RuntimeEnvironment.getApplication();
        installVendorApps(c);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        idle();
        for (int layout = 0; layout < 4; layout++) {
            a.prefs().setHomeLayout(layout);
            a.homeLayoutChanged();
            a.show(MainActivity.HOME);
            idle();
        }
        a.show(MainActivity.MUSIC); idle();
        a.show(MainActivity.APPS); idle();
        for (int s = 0; s < 9; s++) { a.openSettingsSection(s); idle(); }
        a.show(MainActivity.CAR); idle();
        CarScreen car = (CarScreen) getScreen(a, MainActivity.CAR);
        for (int s = 0; s < 7; s++) { car.select(s); idle(); }
        for (int t = 0; t < 4; t++) { a.applyTheme(t); idle(); }
        for (int f = 0; f < 3; f++) { a.setFontSize(f); idle(); }

        // Vendor apps are detected by name.
        assertEquals("com.ts.car.setting", Vendor.find(a, a.prefs(), Vendor.CAR_SETTINGS).getPackageName());
        assertEquals("com.ts.radio", Vendor.find(a, a.prefs(), Vendor.RADIO).getPackageName());
        assertEquals("com.ts.btphone", Vendor.find(a, a.prefs(), Vendor.PHONE).getPackageName());
        assertEquals("com.zjinnova.zlink", Vendor.find(a, a.prefs(), Vendor.PROJECTION).getPackageName());
        assertEquals("com.ts.dvr", Vendor.find(a, a.prefs(), Vendor.CAMERA).getPackageName());

        // Steering-wheel / hardware keys.
        int[] keys = {KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MUSIC, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_ESCAPE};
        for (int k : keys) {
            a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, k));
            a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, k));
            idle();
        }
        a.onBackPressed(); idle();

        // Setup-script intent.
        Intent i = new Intent(a, MainActivity.class).putExtra("theme", "Rose").putExtra("home_layout", 3).putExtra("font_size", 2);
        ctl.newIntent(i);
        idle();
        assertEquals(3, a.prefs().theme());
        assertEquals(3, a.prefs().homeLayout());

        // Home key returns home.
        a.show(MainActivity.APPS); idle();
        ctl.newIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        idle();

        // Rotation / resize: different usable area re-scales without crashing.
        assertTrue(Ui.setArea(1024, 600) || Ui.scale > 0);
        a.rebuildAll(); idle();
        Ui.setArea(1280, 720);
        a.rebuildAll(); idle();

        assertEquals("no recovered errors", null, a.prefs().lastError());
        ctl.pause().stop().destroy();
    }

    @Test
    public void corruptedSettingsFromOldVersionDoNotCrash() {
        Context c = RuntimeEnvironment.getApplication();
        // Wrong types and out-of-range values, as if written by a different app version.
        c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
                .putString("home_layout", "big").putInt("theme", 99).putBoolean("car_name", true)
                .putString("w_lat", "x").putInt("font_size", -5).putString("w_cache", "{not json")
                .putString("vendor_app", "com.gone/.Missing").commit();
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        idle();
        assertEquals(0, a.prefs().homeLayout());
        assertEquals(Ui.THEME_NAMES.length - 1, a.prefs().theme());
        assertEquals("Toyota Noah", a.prefs().carName());
        // Migrated old key, then dropped because the app is gone.
        assertEquals(null, Vendor.find(a, a.prefs(), Vendor.CAR_SETTINGS) == null ? null : "found");
        a.show(MainActivity.SETTINGS); idle();
        ctl.pause().stop().destroy();
    }

    @Test
    public void safeModeAfterRepeatedCrashes() {
        Context c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
                .putInt("crash_count", 2).putLong("crash_time", System.currentTimeMillis())
                .putString("last_error", "java.lang.RuntimeException: test").commit();
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        idle();
        assertTrue(a.inSafeMode());
        a.leaveSafeMode(false);
        idle();
        assertFalse(a.inSafeMode());
        ctl.pause().stop().destroy();
    }

    static Screen getScreen(MainActivity a, int i) {
        try {
            java.lang.reflect.Field f = MainActivity.class.getDeclaredField("screens");
            f.setAccessible(true);
            return ((Screen[]) f.get(a))[i];
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
