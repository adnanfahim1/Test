package com.adnan.glasslauncher;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted settings (survive reboots). */
final class Prefs {
    static final String[] HOME_LAYOUTS = {"Dashboard", "Car showcase", "Minimal", "Drive focus"};
    static final String[] FONT_LABELS = {"Small", "Default", "Large"};
    static final float[] FONT_SCALES = {0.9f, 1f, 1.12f};

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("glass_launcher", Context.MODE_PRIVATE); }

    int homeLayout() { return clamp(sp.getInt("home_layout", 0), 0, 3); }
    void setHomeLayout(int v) { sp.edit().putInt("home_layout", v).apply(); }

    int theme() { return clamp(sp.getInt("theme", 0), 0, Ui.THEME_NAMES.length - 1); }
    void setTheme(int v) { sp.edit().putInt("theme", v).apply(); }

    int fontSize() { return clamp(sp.getInt("font_size", 1), 0, 2); }
    void setFontSize(int v) { sp.edit().putInt("font_size", v).apply(); }

    String carName() { return sp.getString("car_name", "Toyota Noah"); }
    void setCarName(String v) { sp.edit().putString("car_name", v).apply(); }

    /** Component ("pkg/cls") of the maps app opened by Navigate, or null for the system chooser. */
    String mapsApp() { return sp.getString("maps_app", null); }
    void setMapsApp(String v) { sp.edit().putString("maps_app", v).apply(); }

    /** Component of the head unit's own car-settings app (vendor). */
    String vendorApp() { return sp.getString("vendor_app", null); }
    void setVendorApp(String v) { sp.edit().putString("vendor_app", v).apply(); }

    String musicApp() { return sp.getString("music_app", null); }
    void setMusicApp(String v) { sp.edit().putString("music_app", v).apply(); }

    String section(String component) { return sp.getString("sec:" + component, null); }
    void setSection(String component, String section) {
        if (section == null) sp.edit().remove("sec:" + component).apply();
        else sp.edit().putString("sec:" + component, section).apply();
    }

    // Weather location: either the device location or a city the user typed.
    boolean useCity() { return sp.getBoolean("w_use_city", false); }
    String cityName() { return sp.getString("w_city", null); }
    double cityLat() { return Double.longBitsToDouble(sp.getLong("w_lat", 0)); }
    double cityLon() { return Double.longBitsToDouble(sp.getLong("w_lon", 0)); }

    void setCity(String name, double lat, double lon) {
        sp.edit().putBoolean("w_use_city", true).putString("w_city", name)
                .putLong("w_lat", Double.doubleToLongBits(lat)).putLong("w_lon", Double.doubleToLongBits(lon)).apply();
    }

    void useDeviceLocation() { sp.edit().putBoolean("w_use_city", false).apply(); }

    String weatherCache() { return sp.getString("w_cache", null); }
    void setWeatherCache(String json) { sp.edit().putString("w_cache", json).apply(); }

    boolean tempFahrenheit() { return sp.getBoolean("w_f", false); }
    void setTempFahrenheit(boolean v) { sp.edit().putBoolean("w_f", v).apply(); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
