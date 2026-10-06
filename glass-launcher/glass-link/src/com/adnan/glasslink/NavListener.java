package com.adnan.glasslink;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

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
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) onNotificationPosted(s);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            int app = mapsIndex(sbn.getPackageName());
            if (app < 0) return;
            Notification n = sbn.getNotification();
            // Turn-by-turn is an ongoing notification; skip one-off ones (traffic alerts etc.).
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
            Bundle x = n.extras;
            String title = text(x, Notification.EXTRA_TITLE);
            String body = text(x, Notification.EXTRA_TEXT);
            String big = text(x, Notification.EXTRA_BIG_TEXT);
            String sub = text(x, Notification.EXTRA_SUB_TEXT);
            if (body == null) body = big;
            if (title == null && body == null) return;
            JSONObject o = new JSONObject().put("t", "nav").put("active", true).put("app", NAMES[app]);
            if (title != null) o.put("title", title);
            if (body != null) o.put("text", body);
            if (sub != null) o.put("sub", sub);
            String icon = icon(n);
            if (icon != null) o.put("icon", icon);
            navKey = sbn.getKey();
            lastTurn = (title != null ? title : "") + (body != null ? " · " + body : "");
            LinkService.nav(o.toString());
            MainActivity.refreshSoon();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            if (mapsIndex(sbn.getPackageName()) < 0) return;
            if (navKey != null && !navKey.equals(sbn.getKey())) return;
            navKey = null;
            lastTurn = null;
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
    private String icon(Notification n) {
        try {
            Drawable d = null;
            Icon ic = n.getLargeIcon();
            if (ic != null) d = ic.loadDrawable(this);
            if (d == null) return null;
            Bitmap b;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                b = ((BitmapDrawable) d).getBitmap();
            } else {
                b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, 96, 96);
                d.draw(c);
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
