package com.adnan.glasslauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Pull-down panel colour: applied per theme, and restored exactly when turned off. */
@RunWith(RobolectricTestRunner.class)
public class ShadeTest {
    static final String ORIGINAL = "{\"android.theme.customization.theme_style\":\"VIBRANT\",\"android.theme.customization.system_palette\":\"1B6EF3\"}";

    @Test
    @Config(sdk = {31, 33, 34, 35})
    public void appliesAndRestores() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(SystemTheme.PERM_SECURE);
        Settings.Secure.putString(app.getContentResolver(), SystemTheme.OVERLAY_KEY, ORIGINAL);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        a.setMatchShade(true);
        String v = Settings.Secure.getString(app.getContentResolver(), SystemTheme.OVERLAY_KEY);
        assertTrue(v, v.contains("\"android.theme.customization.system_palette\":\"7C5CFF\""));
        assertTrue("keeps user's style", v.contains("VIBRANT"));

        a.applyTheme(2); // Emerald follows automatically
        v = Settings.Secure.getString(app.getContentResolver(), SystemTheme.OVERLAY_KEY);
        assertTrue(v, v.contains("047857"));

        a.setMatchShade(false);
        assertEquals(ORIGINAL, Settings.Secure.getString(app.getContentResolver(), SystemTheme.OVERLAY_KEY));
    }

    @Test
    @Config(sdk = {28, 30})
    public void leftAsIsBeforeAndroid12() {
        Application app = RuntimeEnvironment.getApplication();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.setMatchShade(true);
        assertEquals(false, a.prefs().matchShade());
        assertEquals(SystemTheme.Shade.NEEDS_ANDROID_12, SystemTheme.applyShade(app, 0));
    }

    @Test
    @Config(sdk = 34)
    public void withoutPermissionNothingChanges() {
        Application app = RuntimeEnvironment.getApplication();
        Settings.Secure.putString(app.getContentResolver(), SystemTheme.OVERLAY_KEY, ORIGINAL);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        a.setMatchShade(true);
        assertEquals(ORIGINAL, Settings.Secure.getString(app.getContentResolver(), SystemTheme.OVERLAY_KEY));
        assertEquals(false, a.prefs().matchShade());
    }
}
