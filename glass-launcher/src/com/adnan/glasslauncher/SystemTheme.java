package com.adnan.glasslauncher;

import android.app.UiModeManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;

/**
 * Carries the launcher theme into the rest of the system where Android allows a normal app
 * to do so, and reports honestly what was skipped:
 * - system (and lock-screen) wallpaper: allowed (SET_WALLPAPER permission);
 * - dark mode: only some firmwares let an app switch it; otherwise the setup script does it
 *   over ADB, or it stays as it is.
 * Accent colours, fonts and icons of other apps can't be changed without root, so those are
 * left untouched.
 */
final class SystemTheme {
    interface Done { void done(String summary); }

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
