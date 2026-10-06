package com.adnan.glasslink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the link again after the phone restarts or Glass Link is updated (if it was on). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!c.getSharedPreferences(LinkService.PREFS, Context.MODE_PRIVATE).getBoolean(LinkService.K_ENABLED, false)) return;
        try {
            LinkService.start(c);
        } catch (Throwable ignored) {
            // Android may refuse; opening the app starts it.
        }
    }
}
