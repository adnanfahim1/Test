package com.adnan.glasslink;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * New chat messages (SMS, WhatsApp, Telegram, Signal, ...) go to the car as a banner the
 * car can read aloud, answer with a quick reply, or mark as read. Uses the notification
 * access Glass Link already has; off with "Share messages with the car".
 */
final class Messages {
    static final String K_SHARE = "share_messages";
    private static final long MAX_AGE_MS = 2 * 60 * 1000;

    /** Recent messages by key, so a reply from the car finds its notification. */
    private static final Map<String, StatusBarNotification> recent = new LinkedHashMap<String, StatusBarNotification>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, StatusBarNotification> e) { return size() > 30; }
    };
    private static final Map<String, String> lastText = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 60; }
    };

    private Messages() {}

    static boolean sharing(Context c) {
        return c.getSharedPreferences(LinkService.PREFS, Context.MODE_PRIVATE).getBoolean(K_SHARE, true);
    }

    /** A chat-style notification: message category, MessagingStyle, or a reply action. */
    static boolean isMessage(Notification n) {
        if (Notification.CATEGORY_MESSAGE.equals(n.category)) return true;
        Bundle x = n.extras;
        if (x == null) return false;
        if (x.containsKey("android.messages")) return true;
        String tpl = x.getString("android.template");
        return tpl != null && tpl.endsWith("MessagingStyle");
    }

    static void posted(Context c, StatusBarNotification sbn) {
        try {
            if (!sharing(c)) return;
            String pkg = sbn.getPackageName();
            if (pkg.equals(c.getPackageName()) || "com.adnan.glasslauncher".equals(pkg) || "android".equals(pkg)
                    || "com.android.systemui".equals(pkg)) return;
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            if (!isMessage(n)) return;
            if (System.currentTimeMillis() - sbn.getPostTime() > MAX_AGE_MS) return; // old, re-posted on reconnect
            String[] fromText = fromAndText(n);
            if (fromText[1] == null) return;
            String key = sbn.getKey();
            String sig = fromText[0] + "|" + fromText[1];
            synchronized (recent) {
                if (sig.equals(lastText.get(key))) return; // same message re-posted (read receipts, edits)
                lastText.put(key, sig);
                recent.put(key, sbn);
            }
            JSONObject o = new JSONObject().put("t", "msg").put("key", key).put("app", appName(c, pkg))
                    .put("text", cut(fromText[1], 600)).put("reply", replyAction(n) != null)
                    .put("read", Build.VERSION.SDK_INT >= 28 && readAction(n) != null);
            if (fromText[0] != null) o.put("from", cut(fromText[0], 80));
            if (LinkService.media(o.toString())) LinkService.log("Messages: sent one from " + appName(c, pkg) + " to the car");
        } catch (Throwable ignored) {
        }
    }

    /** Sender and text of the newest message. */
    static String[] fromAndText(Notification n) {
        Bundle x = n.extras;
        String from = null, text = null;
        try {
            Parcelable[] msgs = x.getParcelableArray("android.messages");
            if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
                Bundle m = (Bundle) msgs[msgs.length - 1];
                text = str(m.getCharSequence("text"));
                from = str(m.getCharSequence("sender"));
                if (from == null && Build.VERSION.SDK_INT >= 28) {
                    Object p = m.get("sender_person");
                    if (p instanceof android.app.Person) from = str(((android.app.Person) p).getName());
                }
            }
        } catch (Throwable ignored) {}
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String conv = str(x.getCharSequence("android.conversationTitle"));
        if (from == null) from = title;
        else if (conv != null && !conv.equals(from)) from = from + " · " + conv; // group chats
        if (text == null) text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        if (text == null) text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        return new String[]{from, text};
    }

    static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] in = a.getRemoteInputs();
            if (a.actionIntent == null || in == null) continue;
            for (RemoteInput r : in) if (r.getAllowFreeFormInput()) return a;
        }
        return null;
    }

    static Notification.Action readAction(Notification n) {
        if (n.actions == null || Build.VERSION.SDK_INT < 28) return null;
        for (Notification.Action a : n.actions) {
            if (a.actionIntent != null && a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) return a;
        }
        return null;
    }

    /** A quick reply chosen on the car screen. */
    static void reply(Context c, String key, String text) {
        StatusBarNotification sbn;
        synchronized (recent) { sbn = recent.get(key); }
        Notification.Action a = sbn != null ? replyAction(sbn.getNotification()) : null;
        if (a == null || text == null || text.length() == 0) {
            LinkService.log("Messages: that message can't be answered any more");
            return;
        }
        try {
            Intent fill = new Intent();
            Bundle results = new Bundle();
            for (RemoteInput r : a.getRemoteInputs()) {
                if (r.getAllowFreeFormInput()) results.putCharSequence(r.getResultKey(), text);
            }
            RemoteInput.addResultsToIntent(a.getRemoteInputs(), fill, results);
            a.actionIntent.send(c, 0, fill);
            LinkService.log("Messages: replied “" + text + "” from the car");
        } catch (PendingIntent.CanceledException e) {
            LinkService.log("Messages: the app no longer accepts that reply");
        } catch (Throwable e) {
            LinkService.log("Messages: reply failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    static void markRead(Context c, String key) {
        StatusBarNotification sbn;
        synchronized (recent) { sbn = recent.get(key); }
        Notification.Action a = sbn != null ? readAction(sbn.getNotification()) : null;
        if (a == null) return;
        try {
            a.actionIntent.send(c, 0, new Intent());
            LinkService.log("Messages: marked as read from the car");
        } catch (Throwable ignored) {}
    }

    private static String appName(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private static String str(CharSequence c) {
        if (c == null) return null;
        String s = c.toString().trim();
        return s.length() == 0 ? null : s;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
