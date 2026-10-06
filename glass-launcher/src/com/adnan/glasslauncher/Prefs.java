package com.adnan.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted settings (survive reboots and app updates).
 *
 * Update safety: every read goes through a typed helper that falls back to the default
 * when a value is missing, out of range or was stored with a different type by another
 * version, so an update can never crash on old data. {@link #SCHEMA} is bumped whenever a
 * key changes meaning, and {@link #migrate()} converts older data in place.
 */
final class Prefs {
    static final int SCHEMA = 2;
    static final String FILE = "glass_launcher";
    static final String[] HOME_LAYOUTS = {"Dashboard", "Car showcase", "Minimal", "Drive focus"};
    static final String[] FONT_LABELS = {"Small", "Default", "Large"};
    static final float[] FONT_SCALES = {0.9f, 1f, 1.12f};

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        migrate();
    }

    SharedPreferences raw() { return sp; }

    private void migrate() {
        int from = getInt("schema", 1);
        if (from >= SCHEMA) return;
        SharedPreferences.Editor e = sp.edit();
        // 1 -> 2: the single "vendor_app" became the car-settings connection role.
        String vendor = getStr("vendor_app", null);
        if (vendor != null) {
            e.putString("role:" + Vendor.CAR_SETTINGS, vendor);
            e.remove("vendor_app");
        }
        e.remove("music_app");
        e.putInt("schema", SCHEMA);
        e.apply();
    }

    // ---- Typed, crash-proof access ------------------------------------------------------
    private int getInt(String k, int def) {
        try { return sp.getInt(k, def); } catch (Exception e) { return def; }
    }

    private boolean getBool(String k, boolean def) {
        try { return sp.getBoolean(k, def); } catch (Exception e) { return def; }
    }

    private String getStr(String k, String def) {
        try { return sp.getString(k, def); } catch (Exception e) { return def; }
    }

    private long getLong(String k, long def) {
        try { return sp.getLong(k, def); } catch (Exception e) { return def; }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---- Appearance -----------------------------------------------------------------------
    int homeLayout() { return clamp(getInt("home_layout", 0), 0, 3); }
    void setHomeLayout(int v) { sp.edit().putInt("home_layout", clamp(v, 0, 3)).apply(); }

    int theme() { return clamp(getInt("theme", 0), 0, Ui.THEME_NAMES.length - 1); }
    void setTheme(int v) { sp.edit().putInt("theme", clamp(v, 0, Ui.THEME_NAMES.length - 1)).apply(); }

    int fontSize() { return clamp(getInt("font_size", 1), 0, 2); }
    void setFontSize(int v) { sp.edit().putInt("font_size", clamp(v, 0, 2)).apply(); }

    String carName() {
        String s = getStr("car_name", null);
        return s == null || s.trim().length() == 0 ? "Toyota Noah" : s;
    }
    void setCarName(String v) { sp.edit().putString("car_name", v).apply(); }

    boolean matchSystemWallpaper() { return getBool("match_wallpaper", false); }
    void setMatchSystemWallpaper(boolean v) { sp.edit().putBoolean("match_wallpaper", v).apply(); }

    /** Colour the Android pull-down panel with the theme accent (Android 12+). */
    boolean matchShade() { return getBool("match_shade", false); }
    void setMatchShade(boolean v) { sp.edit().putBoolean("match_shade", v).apply(); }

    // ---- Connections ----------------------------------------------------------------------
    /** Component ("pkg/cls") of the maps app opened by Navigate, or null for auto/ask. */
    String mapsApp() { return getStr("maps_app", null); }
    void setMapsApp(String v) { sp.edit().putString("maps_app", v).apply(); }

    /** User's choice for a connection role (see {@link Vendor}); null = auto-detect. */
    String roleApp(String role) { return getStr("role:" + role, null); }
    void setRoleApp(String role, String component) {
        if (component == null) sp.edit().remove("role:" + role).apply();
        else sp.edit().putString("role:" + role, component).apply();
    }

    String section(String component) { return getStr("sec:" + component, null); }
    void setSection(String component, String section) {
        if (section == null) sp.edit().remove("sec:" + component).apply();
        else sp.edit().putString("sec:" + component, section).apply();
    }

    // ---- Weather --------------------------------------------------------------------------
    boolean useCity() { return getBool("w_use_city", false); }
    String cityName() { return getStr("w_city", null); }
    double cityLat() { return Double.longBitsToDouble(getLong("w_lat", 0)); }
    double cityLon() { return Double.longBitsToDouble(getLong("w_lon", 0)); }

    void setCity(String name, double lat, double lon) {
        sp.edit().putBoolean("w_use_city", true).putString("w_city", name)
                .putLong("w_lat", Double.doubleToLongBits(lat)).putLong("w_lon", Double.doubleToLongBits(lon)).apply();
    }

    void useDeviceLocation() { sp.edit().putBoolean("w_use_city", false).apply(); }

    String weatherCache() { return getStr("w_cache", null); }
    void setWeatherCache(String json) { sp.edit().putString("w_cache", json).apply(); }

    boolean tempFahrenheit() { return getBool("w_f", false); }
    void setTempFahrenheit(boolean v) { sp.edit().putBoolean("w_f", v).apply(); }

    // ---- Diagnostics ----------------------------------------------------------------------
    String lastError() { return getStr("last_error", null); }

    /** Clears everything the user configured (safe-mode "reset"), keeping nothing stale. */
    void resetAll() {
        // Keep the saved original pull-down panel colours so they can still be restored.
        boolean shadeSaved = getBool("shade_saved", false);
        String shadeOriginal = getStr("shade_original", null);
        SharedPreferences.Editor e = sp.edit().clear().putInt("schema", SCHEMA);
        if (shadeSaved) e.putBoolean("shade_saved", true).putString("shade_original", shadeOriginal);
        e.commit();
    }
}
