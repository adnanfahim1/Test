package com.adnan.glasslink;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the ongoing navigation notification of maps apps and forwards the next turn to the
 * car. Other apps' notifications are ignored and never leave the phone.
 */
public final class NavListener extends NotificationListenerService {
    static final String[] MAPS = {
            "com.google.android.apps.maps", "com.waze", "com.here.app.maps", "com.sygic.aura",
            "net.osmand", "net.osmand.plus", "app.organicmaps", "com.mapswithme.maps.pro",
            "ru.yandex.yandexnavi", "com.tomtom.gplay.navapp", "com.google.android.apps.mapslite"};
    static final String[] NAMES = {
            "Google Maps", "Waze", "HERE WeGo", "Sygic", "OsmAnd", "OsmAnd+", "Organic Maps", "MAPS.ME",
            "Yandex Navigator", "TomTom GO", "Google Maps Go"};

    static volatile String lastTurn;
    private String navKey;

    static int mapsIndex(String pkg) {
        for (int i = 0; i < MAPS.length; i++) if (MAPS[i].equals(pkg)) return i;
        return -1;
    }

    @Override
    public void onListenerConnected() {
        PhoneMedia.ping(this);
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) onNotificationPosted(s);
        } catch (Throwable ignored) {}
    }

    private static final java.util.regex.Pattern DISTANCE = java.util.regex.Pattern.compile(
            "^\\s*[0-9]+([.,][0-9]+)?\\s*(m|km|ft|mi|yd|meters?|metres?|miles?|feet|kilometers?|kilometres?)\\b.*",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern ETA = java.util.regex.Pattern.compile(
            ".*(\\bmin\\b|\\bhr\\b|\\bh\\b|\\beta\\b|arrive|[0-9]{1,2}:[0-9]{2}).*", java.util.regex.Pattern.CASE_INSENSITIVE);
    private String loggedRoute;

    /** Turn-by-turn is a navigation / ongoing / foreground-service notification of a maps app. */
    static boolean isNavigation(Notification n) {
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return true;
        if ((n.flags & 0x40) != 0) return true; // FLAG_FOREGROUND_SERVICE: Android 14+ lets users dismiss these
        if (Notification.CATEGORY_NAVIGATION.equals(n.category)) return true;
        String ch = Build.VERSION.SDK_INT >= 26 ? n.getChannelId() : null;
        return ch != null && ch.toLowerCase(java.util.Locale.US).contains("nav");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            PhoneMedia.ping(this);
            int app = mapsIndex(sbn.getPackageName());
            if (app < 0) return;
            Notification n = sbn.getNotification();
            if (!isNavigation(n)) return; // traffic alerts, tips etc.
            Bundle x = n.extras;
            String title = text(x, Notification.EXTRA_TITLE);
            String body = text(x, Notification.EXTRA_TEXT);
            if (body == null) body = text(x, Notification.EXTRA_BIG_TEXT);
            String sub = text(x, Notification.EXTRA_SUB_TEXT);
            Bitmap arrow = null;
            // Newer Google Maps draws the turn in a custom layout; read its texts and arrow.
            List<String> texts = new ArrayList<String>();
            Bitmap[] img = new Bitmap[1];
            readCustom(n, texts, img);
            if (!texts.isEmpty()) {
                String dist = null, eta = null, instr = null;
                for (String t : texts) {
                    if (dist == null && DISTANCE.matcher(t).matches()) dist = t;
                    else if (eta == null && ETA.matcher(t).matches() && t.length() < 40) eta = t;
                }
                for (String t : texts) {
                    if (t.equals(dist) || t.equals(eta) || t.equalsIgnoreCase(NAMES[app])) continue;
                    if (instr == null || t.length() > instr.length()) instr = t;
                }
                if (title == null || title.equalsIgnoreCase(NAMES[app]) || (dist != null && !DISTANCE.matcher(title).matches())) {
                    if (dist != null) title = dist;
                }
                if (body == null || (instr != null && body.length() < 4)) body = instr;
                if (sub == null) sub = eta;
                arrow = img[0];
            }
            if (title != null && body != null && title.equals(body)) body = null;
            if (title == null && body == null) {
                logOnce(NAMES[app] + " is navigating, but its notification has no turn text Glass Link can read.");
                return;
            }
            JSONObject o = new JSONObject().put("t", "nav").put("active", true).put("app", NAMES[app]);
            if (title != null) o.put("title", title);
            if (body != null) o.put("text", body);
            if (sub != null) o.put("sub", sub);
            String icon = icon(n, arrow);
            if (icon != null) o.put("icon", icon);
            navKey = sbn.getKey();
            lastTurn = (title != null ? title : "") + (body != null ? " · " + body : "");
            logOnce("Navigation from " + NAMES[app] + ": " + lastTurn);
            LinkService.nav(o.toString());
            MainActivity.refreshSoon();
        } catch (Throwable t) {
            LinkService.log("Navigation: couldn't read the maps notification (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** Logs the first turn of a route (not every update). */
    private void logOnce(String s) {
        if (loggedRoute != null) return;
        loggedRoute = s;
        LinkService.log(s);
    }

    /** Texts and the first picture from the notification's custom layout (if it has one). */
    @SuppressWarnings("deprecation")
    private void readCustom(Notification n, List<String> texts, Bitmap[] img) {
        android.widget.RemoteViews[] views = {n.bigContentView, n.contentView};
        for (android.widget.RemoteViews rv : views) {
            if (rv == null) continue;
            try {
                android.widget.FrameLayout parent = new android.widget.FrameLayout(this);
                View v = rv.apply(this, parent);
                collect(v, texts, img);
                if (!texts.isEmpty()) return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collect(View v, List<String> texts, Bitmap[] img) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof android.widget.TextView) {
            CharSequence c = ((android.widget.TextView) v).getText();
            if (c != null) {
                String t = c.toString().trim();
                if (t.length() > 0 && !texts.contains(t)) texts.add(t);
            }
        } else if (v instanceof android.widget.ImageView && img[0] == null) {
            Drawable d = ((android.widget.ImageView) v).getDrawable();
            if (d != null && d.getIntrinsicWidth() > 8) img[0] = toBitmap(d);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), texts, img);
        }
    }

    private static Bitmap toBitmap(Drawable d) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, 96, 96);
        d.draw(c);
        return b;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            if (mapsIndex(sbn.getPackageName()) < 0) return;
            if (navKey != null && !navKey.equals(sbn.getKey())) return;
            navKey = null;
            lastTurn = null;
            loggedRoute = null;
            LinkService.nav("{\"t\":\"nav\",\"active\":false}");
            MainActivity.refreshSoon();
        } catch (Throwable ignored) {
        }
    }

    private static String text(Bundle x, String key) {
        if (x == null) return null;
        CharSequence c = x.getCharSequence(key);
        if (c == null) return null;
        String s = c.toString().trim();
        return s.length() == 0 ? null : s;
    }

    /** The maneuver arrow (the notification's large icon), as a small PNG in Base64. */
    private String icon(Notification n, Bitmap arrow) {
        try {
            Bitmap b = arrow;
            if (b == null) {
                Icon ic = n.getLargeIcon();
                Drawable d = ic != null ? ic.loadDrawable(this) : null;
                if (d == null) return null;
                b = toBitmap(d);
            }
            if (b.getWidth() > 96 || b.getHeight() > 96) b = Bitmap.createScaledBitmap(b, 96, 96, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }
}
