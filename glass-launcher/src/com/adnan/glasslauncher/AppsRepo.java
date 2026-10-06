package com.adnan.glasslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Installed launchable apps, sorted A–Z, each assigned to a drawer section. */
final class AppsRepo {
    static final String[] SECTIONS = {"All", "Drive", "Media", "Connect", "Tools", "System"};

    static final class App {
        final String label;
        final String pkg;
        final ComponentName component;
        final ResolveInfo info;
        String section;
        private Drawable icon;

        App(String label, ResolveInfo ri) {
            this.label = label;
            this.info = ri;
            this.pkg = ri.activityInfo.packageName;
            this.component = new ComponentName(pkg, ri.activityInfo.name);
        }

        String key() { return component.flattenToShortString(); }

        Drawable icon(PackageManager pm) {
            if (icon == null) {
                try { icon = info.loadIcon(pm); } catch (Exception e) { icon = pm.getDefaultActivityIcon(); }
            }
            return icon;
        }
    }

    private AppsRepo() {}

    static List<App> load(Context c, Prefs prefs) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        List<App> out = new ArrayList<App>();
        String self = c.getPackageName();
        for (ResolveInfo ri : list) {
            if (self.equals(ri.activityInfo.packageName)) continue;
            CharSequence l = ri.loadLabel(pm);
            App a = new App(l != null ? l.toString() : ri.activityInfo.packageName, ri);
            String saved = prefs.section(a.key());
            a.section = saved != null ? saved : guessSection(a);
            out.add(a);
        }
        final Collator col = Collator.getInstance(Locale.getDefault());
        Collections.sort(out, new Comparator<App>() {
            @Override
            public int compare(App x, App y) { return col.compare(x.label, y.label); }
        });
        return out;
    }

    /** First guess from the app's declared category and well-known names; the user can override. */
    static String guessSection(App a) {
        String p = a.pkg.toLowerCase(Locale.US);
        String l = a.label.toLowerCase(Locale.US);
        // ApplicationInfo.CATEGORY_* values (API 26), as literals so older units compile/run.
        int cat = NewApi.appCategory(a.info.activityInfo.applicationInfo);
        if (cat == 6) return "Drive";                       // MAPS
        if (cat == 1 || cat == 2 || cat == 3) return "Media"; // AUDIO, VIDEO, IMAGE
        if (cat == 4) return "Connect";                     // SOCIAL
        if (cat == 7) return "Tools";                       // PRODUCTIVITY
        if (has(p, l, "map", "navi", "waze", "gps", "weather", "sygic", "here.", "osmand", "barikoi", "pathao")) return "Drive";
        if (has(p, l, "music", "spotify", "radio", "fm", "video", "youtube", "player", "gallery", "photo",
                "camera", "dvr", "media", "audio", "podcast", "netflix", "vlc", "mx", "tv", "avin", "aux")) return "Media";
        if (has(p, l, "phone", "dialer", "contacts", "bluetooth", "bt", "carplay", "android auto", "zlink",
                "carlink", "autokit", "projection", "messag", "whatsapp", "telegram", "imo", "messenger")) return "Connect";
        if (has(p, l, "setting", "eq", "equalizer", "system", "update", "factory", "canbus", "car setting")) return "System";
        return "Tools";
    }

    private static boolean has(String p, String l, String... keys) {
        for (String k : keys) if (p.contains(k) || l.contains(k)) return true;
        return false;
    }

    static boolean launch(Context c, ComponentName cn) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean launchPackage(Context c, String pkg) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String labelFor(Context c, String flatComponent) {
        if (flatComponent == null) return null;
        ComponentName cn = ComponentName.unflattenFromString(flatComponent);
        if (cn == null) return null;
        PackageManager pm = c.getPackageManager();
        try {
            return pm.getActivityInfo(cn, 0).loadLabel(pm).toString();
        } catch (Exception e) {
            try {
                return pm.getApplicationInfo(cn.getPackageName(), 0).loadLabel(pm).toString();
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
