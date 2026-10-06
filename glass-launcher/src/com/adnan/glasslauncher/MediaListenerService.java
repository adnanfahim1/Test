package com.adnan.glasslauncher;

import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification access lets the launcher read other apps' media sessions. It also reads one
 * kind of notification: the ongoing turn-by-turn notification of a maps app running on the
 * head unit itself, so the Navigation card works without the phone. Nothing else is read.
 */
public class MediaListenerService extends NotificationListenerService {
    static final String[] MAPS = {"com.google.android.apps.maps", "com.waze", "com.here.app.maps", "com.sygic.aura",
            "net.osmand", "net.osmand.plus", "app.organicmaps", "ru.yandex.yandexnavi", "com.tomtom.gplay.navapp",
            "com.google.android.apps.mapslite", "com.mapswithme.maps.pro"};
    static final String[] NAMES = {"Google Maps", "Waze", "HERE WeGo", "Sygic", "OsmAnd", "OsmAnd+", "Organic Maps",
            "Yandex Navigator", "TomTom GO", "Google Maps Go", "MAPS.ME"};

    interface NavSink { void onLocalNav(PhoneBridge.Nav n); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static volatile NavSink sink;
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
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return;
            Bundle x = n.extras;
            PhoneBridge.Nav nav = new PhoneBridge.Nav();
            nav.active = true;
            nav.app = NAMES[app];
            nav.title = text(x, Notification.EXTRA_TITLE);
            nav.text = text(x, Notification.EXTRA_TEXT);
            if (nav.text == null) nav.text = text(x, Notification.EXTRA_BIG_TEXT);
            nav.sub = text(x, Notification.EXTRA_SUB_TEXT);
            if (nav.title == null && nav.text == null) return;
            Object big = x != null ? x.get(Notification.EXTRA_LARGE_ICON) : null;
            if (big instanceof Bitmap) nav.icon = (Bitmap) big;
            else if (n.largeIcon != null) nav.icon = n.largeIcon;
            nav.at = System.currentTimeMillis();
            nav.local = true;
            navKey = sbn.getKey();
            deliver(nav);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        try {
            if (mapsIndex(sbn.getPackageName()) < 0 || navKey == null || !navKey.equals(sbn.getKey())) return;
            navKey = null;
            PhoneBridge.Nav ended = new PhoneBridge.Nav();
            ended.local = true;
            deliver(ended);
        } catch (Throwable ignored) {
        }
    }

    private static void deliver(final PhoneBridge.Nav n) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                NavSink s = sink;
                if (s != null) s.onLocalNav(n);
            }
        });
    }

    private static String text(Bundle x, String key) {
        if (x == null) return null;
        CharSequence c = x.getCharSequence(key);
        if (c == null) return null;
        String s = c.toString().trim();
        return s.length() == 0 ? null : s;
    }
}
