package com.adnan.glasslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;
import java.util.Locale;

/**
 * Connects the launcher to the head unit's own apps (car settings, radio, Bluetooth phone,
 * reverse camera, EQ, AV-in, CarPlay/Android Auto link...). Nakamichi units are built on
 * several vendor platforms whose package names differ by model and firmware, so apps are
 * found by name instead of hard-coded: each role scores installed apps by keywords. The
 * user can override any role in Settings › Connections, and an override that disappears
 * after a firmware update falls back to auto-detection instead of breaking.
 */
final class Vendor {
    static final String CAR_SETTINGS = "car_settings", RADIO = "radio", PHONE = "phone", CAMERA = "camera",
            EQ = "eq", AV_IN = "av_in", PROJECTION = "projection", VIDEO = "video", MUSIC = "music", FILES = "files",
            BROWSER = "browser";

    static final String[] ROLES = {CAR_SETTINGS, RADIO, PHONE, PROJECTION, CAMERA, EQ, AV_IN, MUSIC, VIDEO, FILES, BROWSER};

    static String label(String role) {
        if (CAR_SETTINGS.equals(role)) return "Head unit settings";
        if (RADIO.equals(role)) return "Radio";
        if (PHONE.equals(role)) return "Bluetooth phone";
        if (PROJECTION.equals(role)) return "CarPlay / Android Auto";
        if (CAMERA.equals(role)) return "Reverse camera / DVR";
        if (EQ.equals(role)) return "Equalizer";
        if (AV_IN.equals(role)) return "AV-in / AUX";
        if (MUSIC.equals(role)) return "Music player";
        if (VIDEO.equals(role)) return "Video player";
        if (FILES.equals(role)) return "Files";
        return "Browser";
    }

    static String glassIcon(String role) {
        if (CAR_SETTINGS.equals(role)) return "car";
        if (RADIO.equals(role)) return "radio";
        if (PHONE.equals(role)) return "phone";
        if (PROJECTION.equals(role)) return "link";
        if (CAMERA.equals(role)) return "camera";
        if (EQ.equals(role)) return "eq";
        if (AV_IN.equals(role)) return "video";
        if (MUSIC.equals(role)) return "music";
        if (VIDEO.equals(role)) return "video";
        if (FILES.equals(role)) return "files";
        return "browser";
    }

    /** Keywords matched against "package label" (lower case). Strong ones score higher. */
    private static String[][] keys(String role) {
        if (CAR_SETTINGS.equals(role)) return new String[][]{
                {"carsetting", "car_setting", "car setting", "car settings", "vehicle setting", "carinfo", "canbus", "factory"},
                {"setting", "vehicle", "car"}};
        if (RADIO.equals(role)) return new String[][]{{"radio", ".fm", " fm", "tuner", "dab"}, {"am/fm"}};
        if (PHONE.equals(role)) return new String[][]{
                {"btphone", "bt.phone", "bluetooth phone", "bt phone", "btmusic", "carbt", "bluetooth"},
                {"phone", "dialer", ".bt"}};
        if (PROJECTION.equals(role)) return new String[][]{
                {"zlink", "carlink", "carplay", "autokit", "android auto", "androidauto", "projection", "easyconn", "tlink"},
                {"phonelink", "mirror", "link"}};
        if (CAMERA.equals(role)) return new String[][]{{"dvr", "reverse", "avm", "360", "backcar", "rearview"}, {"camera", "cam"}};
        if (EQ.equals(role)) return new String[][]{{"equalizer", ".eq", " eq", "dsp", "soundeffect"}, {"sound", "audio"}};
        if (AV_IN.equals(role)) return new String[][]{{"avin", "av_in", "av in", "aux", "cvbs"}, {"video in", "tv"}};
        if (MUSIC.equals(role)) return new String[][]{{"music", "spotify", "audio player"}, {"player", "mp3"}};
        if (VIDEO.equals(role)) return new String[][]{{"video", "vlc", "mxtech", "movie"}, {"player", "youtube"}};
        if (FILES.equals(role)) return new String[][]{{"filemanager", "file manager", "documentsui", "files"}, {"explorer", "file"}};
        return new String[][]{{"browser", "chrome", "firefox"}, {"web", "internet"}};
    }

    /** Words that disqualify an app for a role (avoids e.g. Android Settings as "car settings"). */
    private static boolean excluded(String role, String hay, String pkg) {
        if (CAR_SETTINGS.equals(role)) return "com.android.settings".equals(pkg) || hay.contains("launcher");
        if (PHONE.equals(role)) return hay.contains("settings") && !hay.contains("phone");
        if (MUSIC.equals(role)) return hay.contains("video");
        return false;
    }

    /** Best installed launchable app for a role, honouring the user's choice when still installed. */
    static ComponentName find(Context c, Prefs prefs, String role) {
        String chosen = prefs.roleApp(role);
        PackageManager pm = c.getPackageManager();
        if (chosen != null) {
            ComponentName cn = ComponentName.unflattenFromString(chosen);
            if (cn != null && exists(pm, cn)) return cn;
            prefs.setRoleApp(role, null); // uninstalled or renamed by an update: fall back to auto
        }
        return detect(c, role);
    }

    static ComponentName detect(Context c, String role) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> all;
        try {
            all = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        } catch (Exception e) {
            return null;
        }
        String[][] k = keys(role);
        ResolveInfo best = null;
        int bestScore = 0;
        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            if (c.getPackageName().equals(pkg)) continue;
            String label;
            try { label = String.valueOf(ri.loadLabel(pm)); } catch (Exception e) { label = ""; }
            String hay = (pkg + " " + ri.activityInfo.name + " " + label).toLowerCase(Locale.US);
            if (excluded(role, hay, pkg)) continue;
            int score = 0;
            for (String s : k[0]) if (hay.contains(s)) score += 10;
            for (String s : k[1]) if (hay.contains(s)) score += 3;
            if (score > 0 && !pkg.startsWith("com.google.") && !pkg.startsWith("com.android.")) score += 2;
            if (score > bestScore) { bestScore = score; best = ri; }
        }
        return best != null ? new ComponentName(best.activityInfo.packageName, best.activityInfo.name) : null;
    }

    static boolean exists(PackageManager pm, ComponentName cn) {
        try {
            pm.getActivityInfo(cn, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
