package com.adnan.glasslauncher;

import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;

/**
 * Carries the launcher theme into the rest of the system where Android allows a normal app
 * to do so, and reports honestly what was skipped:
 * - system (and lock-screen) wallpaper: allowed (SET_WALLPAPER permission);
 * - dark mode: only some firmwares let an app switch it; otherwise the setup script does it
 *   over ADB, or it stays as it is.
 * - pull-down panel colour: Android 12+ only, after the setup script grants one permission
 *   over USB (WRITE_SECURE_SETTINGS); only the panel's colour seed is changed, and it can be
 *   restored exactly.
 * Fonts and icons of other apps can't be changed without root, so those are left untouched.
 */
final class SystemTheme {
    interface Done { void done(String summary); }

    /** Android 12+ builds the pull-down panel's colours from this seed (Material You). */
    static final String OVERLAY_KEY = "theme_customization_overlay_packages";
    static final String PERM_SECURE = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String K_PALETTE = "android.theme.customization.system_palette";
    private static final String K_ACCENT = "android.theme.customization.accent_color";
    private static final String K_SOURCE = "android.theme.customization.color_source";
    private static final String K_STYLE = "android.theme.customization.theme_style";

    /** Result of trying to colour the pull-down panel. */
    enum Shade { APPLIED, RESTORED, NEEDS_ANDROID_12, NEEDS_PERMISSION, BLOCKED }

    static boolean shadeSupported() { return Build.VERSION.SDK_INT >= 31; }

    static boolean shadeAllowed(Context c) { return shadeSupported() && NewApi.granted(c, PERM_SECURE); }

    static String hex(int color) { return String.format(java.util.Locale.US, "%06X", color & 0xFFFFFF); }

    /**
     * Sets the pull-down panel (notification shade / quick settings) colour seed to the theme
     * accent. Only this one system setting is touched; its original value is saved first so
     * {@link #restoreShade} can put everything back exactly as it was.
     */
    static Shade applyShade(Context c, int themeIndex) {
        if (!shadeSupported()) return Shade.NEEDS_ANDROID_12;
        if (!NewApi.granted(c, PERM_SECURE)) return Shade.NEEDS_PERMISSION;
        try {
            SharedPreferences sp = c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
            String current = Settings.Secure.getString(c.getContentResolver(), OVERLAY_KEY);
            if (!sp.getBoolean("shade_saved", false)) {
                sp.edit().putBoolean("shade_saved", true).putString("shade_original", current).commit();
            }
            JSONObject o;
            try { o = current != null && current.length() > 0 ? new JSONObject(current) : new JSONObject(); }
            catch (Exception e) { o = new JSONObject(); }
            String color = hex(Ui.THEME_ACCENTS[themeIndex]);
            o.put(K_SOURCE, "preset");
            o.put(K_PALETTE, color);
            o.put(K_ACCENT, color);
            if (!o.has(K_STYLE)) o.put(K_STYLE, "TONAL_SPOT");
            return Settings.Secure.putString(c.getContentResolver(), OVERLAY_KEY, o.toString()) ? Shade.APPLIED : Shade.BLOCKED;
        } catch (Throwable t) {
            return Shade.BLOCKED;
        }
    }

    /** Puts the pull-down panel colours back to what they were before the launcher changed them. */
    static Shade restoreShade(Context c) {
        SharedPreferences sp = c.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        if (!sp.getBoolean("shade_saved", false)) return Shade.RESTORED;
        if (!shadeAllowed(c)) return Shade.NEEDS_PERMISSION;
        try {
            String original = sp.getString("shade_original", null);
            Settings.Secure.putString(c.getContentResolver(), OVERLAY_KEY, original);
            sp.edit().remove("shade_saved").remove("shade_original").commit();
            return Shade.RESTORED;
        } catch (Throwable t) {
            return Shade.BLOCKED;
        }
    }

    static String describe(Shade r) {
        switch (r) {
            case APPLIED: return "Pull-down panel now matches the theme";
            case RESTORED: return "Pull-down panel colours restored";
            case NEEDS_ANDROID_12: return "Pull-down panel left as it is (needs Android 12 or newer)";
            case NEEDS_PERMISSION: return "Pull-down panel: run the setup script once over USB to allow this";
            default: return "Pull-down panel left as it is (this firmware blocks it)";
        }
    }

    private SystemTheme() {}

    static void apply(final Context ctx, final int themeIndex, final Done cb) {
        final Context c = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append(applyWallpaper(c, themeIndex) ? "Wallpaper set" : "Wallpaper left as it is");
                sb.append(" · ");
                sb.append(darkMode(c) ? "dark mode on" : "dark mode left as it is");
                final String s = sb.toString();
                main.post(new Runnable() {
                    @Override
                    public void run() { if (cb != null) cb.done(s); }
                });
            }
        }, "system-theme").start();
    }

    static boolean applyWallpaper(Context c, int themeIndex) {
        InputStream in = null;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(c);
            if (wm == null) return false;
            in = c.getAssets().open("wallpapers/wall-" + Ui.THEME_NAMES[themeIndex] + ".webp");
            wm.setStream(in);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    static boolean darkMode(Context c) {
        try {
            UiModeManager um = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            if (um == null) return false;
            if (um.getNightMode() == UiModeManager.MODE_NIGHT_YES) return true;
            um.setNightMode(UiModeManager.MODE_NIGHT_YES);
            return um.getNightMode() == UiModeManager.MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }
}
