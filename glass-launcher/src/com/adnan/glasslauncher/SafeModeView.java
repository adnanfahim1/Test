package com.adnan.glasslauncher;

import android.content.Intent;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Shown after the launcher crashed twice in a row. Built only from the simplest framework
 * pieces so it works even if the normal screens can't: every app stays reachable and the
 * user can reset, retry or switch to another home app.
 */
final class SafeModeView extends LinearLayout {
    SafeModeView(final MainActivity a) {
        super(a);
        setOrientation(HORIZONTAL);
        setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(24));

        LinearLayout info = Ui.col(a);
        info.addView(Ui.text(a, "Safe mode", 36, Ui.TEXT, Ui.display(600)));
        TextView msg = Ui.multiline(a, "Glass Launcher closed unexpectedly twice, so it started in safe mode. "
                + "All your apps are on the right. Try the launcher again, or reset its settings if the problem repeats.",
                16, Ui.TEXT_2, Ui.body(400));
        info.addView(msg, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 20));
        info.addView(Parts.accentButton(a, "Start normally", 16, 22, 52, 16, new OnClickListener() {
            @Override
            public void onClick(View v) { a.leaveSafeMode(false); }
        }), Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), 0, 0, 0, 10));
        info.addView(Parts.outlineButton(a, "Reset launcher settings and start", 16, 22, 52, 16, new OnClickListener() {
            @Override
            public void onClick(View v) { a.leaveSafeMode(true); }
        }), Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), 0, 0, 0, 10));
        info.addView(Parts.outlineButton(a, "Choose another home app", 16, 22, 52, 16, new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!a.startSafe(new Intent(Settings.ACTION_HOME_SETTINGS), null)) a.startSafe(new Intent(Settings.ACTION_SETTINGS), null);
            }
        }), Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), 0, 0, 0, 10));
        info.addView(Parts.outlineButton(a, "Android settings", 16, 22, 52, 16, new OnClickListener() {
            @Override
            public void onClick(View v) { a.startSafe(new Intent(Settings.ACTION_SETTINGS), null); }
        }), Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), 0, 0, 0, 10));
        String err = a.prefs().lastError();
        if (err != null) {
            TextView e = Ui.multiline(a, "Last error: " + firstLine(err), 12, Ui.FAINT, Ui.body(400));
            e.setMaxLines(3);
            info.addView(e, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 10, 0, 0));
        }
        addView(info, Ui.lpw(0, Ui.MATCH, 1));

        ScrollView sv = new ScrollView(a);
        LinearLayout list = Ui.col(a);
        List<AppsRepo.App> apps;
        try {
            apps = AppsRepo.load(a, a.prefs());
        } catch (Throwable t) {
            apps = new java.util.ArrayList<AppsRepo.App>();
        }
        for (final AppsRepo.App app : apps) {
            TextView t = Ui.text(a, app.label, 18, Ui.TEXT, Ui.body(500));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.u(20), 0, Ui.u(20), 0);
            t.setBackground(Ui.tile(14));
            t.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { AppsRepo.launch(a, app.component); }
            });
            Ui.pressable(t);
            list.addView(t, Ui.margins(Ui.lp(Ui.MATCH, Ui.u(56)), 0, 0, 0, 8));
        }
        sv.addView(list);
        addView(sv, Ui.margins(Ui.lp(Ui.u(420), Ui.MATCH), 32, 0, 0, 0));
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }
}
