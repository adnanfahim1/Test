package com.adnan.glasslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A real map in the Navigation card: the launcher hosts an Android home-screen widget the
 * driver picks (for example Google Maps' traffic widget). Live turn-by-turn from the phone
 * still shows on top of it. Without a widget the card keeps its drawn map.
 *
 * Android asks once whether Glass Launcher may add widgets (the setup script can allow it).
 */
final class MapWidget {
    static final int HOST_ID = 0x474C; // "GL"
    static final int REQ_BIND = 21, REQ_CONFIG = 22;
    private static final String[] MAP_APPS = {"com.google.android.apps.maps", "com.waze", "com.here.app.maps",
            "com.sygic.aura", "net.osmand", "net.osmand.plus", "app.organicmaps", "com.tomtom.gplay.navapp"};
    private static AppWidgetHost host;
    private static int pendingId = -1;
    private static AppWidgetProviderInfo pendingInfo;

    private MapWidget() {}

    static synchronized AppWidgetHost host(Context c) {
        if (host == null) host = new AppWidgetHost(c.getApplicationContext(), HOST_ID);
        return host;
    }

    static void startListening(Context c) {
        try { host(c).startListening(); } catch (Throwable t) { Log.w("GlassMap", "startListening: " + t); }
    }

    static void stopListening(Context c) {
        try { host(c).stopListening(); } catch (Throwable ignored) {}
    }

    /** The chosen widget's view, or null (none chosen, or its app was removed). */
    static View view(MainActivity a) {
        int id = a.prefs().mapWidget();
        if (id <= 0) return null;
        try {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(a).getAppWidgetInfo(id);
            if (info == null) {
                a.prefs().setMapWidget(-1); // app uninstalled: back to the drawn map
                return null;
            }
            AppWidgetHostView v = host(a).createView(a.getApplicationContext(), id, info);
            v.setPadding(0, 0, 0, 0);
            return v;
        } catch (Throwable t) {
            Log.w("GlassMap", "Couldn't show the map widget: " + t);
            return null;
        }
    }

    /** Tells the widget how big it is (dp), so maps fill the card. */
    static void size(View v, int wPx, int hPx) {
        if (!(v instanceof AppWidgetHostView) || wPx <= 0 || hPx <= 0) return;
        try {
            float d = v.getResources().getDisplayMetrics().density;
            int w = Math.round(wPx / d), h = Math.round(hPx / d);
            ((AppWidgetHostView) v).updateAppWidgetSize(new Bundle(), w, h, w, h);
        } catch (Throwable ignored) {}
    }

    static String chosenLabel(MainActivity a) {
        int id = a.prefs().mapWidget();
        if (id <= 0) return null;
        try {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(a).getAppWidgetInfo(id);
            return info != null ? label(a, info) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String label(Context c, AppWidgetProviderInfo info) {
        PackageManager pm = c.getPackageManager();
        String app;
        try { app = pm.getApplicationLabel(pm.getApplicationInfo(info.provider.getPackageName(), 0)).toString(); }
        catch (Throwable t) { app = info.provider.getPackageName(); }
        String w;
        try { w = info.loadLabel(pm); } catch (Throwable t) { w = null; }
        return w != null && !w.equals(app) ? app + " · " + w : app;
    }

    /** Installed widgets, map apps first, then anything that sounds like a map. */
    static List<AppWidgetProviderInfo> providers(final Context c) {
        List<AppWidgetProviderInfo> all = new ArrayList<AppWidgetProviderInfo>();
        try { all.addAll(AppWidgetManager.getInstance(c).getInstalledProviders()); } catch (Throwable ignored) {}
        Collections.sort(all, new Comparator<AppWidgetProviderInfo>() {
            @Override
            public int compare(AppWidgetProviderInfo x, AppWidgetProviderInfo y) {
                int d = rank(c, x) - rank(c, y);
                return d != 0 ? d : label(c, x).compareToIgnoreCase(label(c, y));
            }
        });
        return all;
    }

    private static int rank(Context c, AppWidgetProviderInfo i) {
        String pkg = i.provider.getPackageName();
        for (int k = 0; k < MAP_APPS.length; k++) if (MAP_APPS[k].equals(pkg)) return k;
        String l = label(c, i).toLowerCase(Locale.US);
        if (l.contains("map") || l.contains("traffic") || l.contains("nav")) return 50;
        return 100;
    }

    /** Lets the driver pick a widget for the card (or go back to the drawn map). */
    static void choose(final MainActivity a) {
        final List<AppWidgetProviderInfo> list = providers(a);
        final List<String> names = new ArrayList<String>();
        names.add("Drawn map (no widget)");
        for (AppWidgetProviderInfo i : list) names.add(label(a, i));
        try {
            new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(list.isEmpty() ? "No widgets installed" : "Map in the Navigation card")
                    .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            if (which == 0) { set(a, -1); return; }
                            pick(a, list.get(which - 1));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
            a.toast("Couldn't list widgets");
        }
    }

    static void pick(MainActivity a, AppWidgetProviderInfo info) {
        AppWidgetHost h = host(a);
        int id = h.allocateAppWidgetId();
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) id = h.allocateAppWidgetId(); // 0 means "none"
        pendingId = id;
        pendingInfo = info;
        boolean bound = false;
        try { bound = AppWidgetManager.getInstance(a).bindAppWidgetIdIfAllowed(id, info.provider); } catch (Throwable ignored) {}
        if (bound) {
            configure(a);
            return;
        }
        // Android asks the driver once: "Allow Glass Launcher to create widgets?"
        Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        try {
            a.startActivityForResult(i, REQ_BIND);
        } catch (Throwable t) {
            h.deleteAppWidgetId(id);
            a.toast("This head unit doesn't allow widgets in launchers");
        }
    }

    private static void configure(MainActivity a) {
        AppWidgetProviderInfo info = pendingInfo;
        if (info != null && info.configure != null) {
            try {
                host(a).startAppWidgetConfigureActivityForResult(a, pendingId, 0, REQ_CONFIG, null);
                return;
            } catch (Throwable t) {
                Log.w("GlassMap", "configure: " + t);
            }
        }
        set(a, pendingId);
    }

    /** Result of Android's "allow widgets" or the widget's own set-up screen. */
    static boolean onResult(MainActivity a, int req, int result) {
        if (req != REQ_BIND && req != REQ_CONFIG) return false;
        if (result != Activity.RESULT_OK) {
            if (pendingId > 0) try { host(a).deleteAppWidgetId(pendingId); } catch (Throwable ignored) {}
            pendingId = -1;
            a.toast("Map widget not added");
            return true;
        }
        if (req == REQ_BIND) configure(a);
        else set(a, pendingId);
        return true;
    }

    private static void set(MainActivity a, int id) {
        int old = a.prefs().mapWidget();
        if (old > 0 && old != id) try { host(a).deleteAppWidgetId(old); } catch (Throwable ignored) {}
        a.prefs().setMapWidget(id);
        pendingId = -1;
        pendingInfo = null;
        a.homeLayoutChanged();
        a.show(MainActivity.HOME);
        a.toast(id > 0 ? "Map widget added to the Navigation card" : "Navigation card shows the drawn map");
    }
}
