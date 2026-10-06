package com.adnan.glasslauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * "Glass Launcher Help": a deliberately plain screen that runs in its own process, built only
 * from basic Android widgets, so it opens even when the launcher itself can't start. It shows
 * what happened, lets the user restart in normal / compatibility / safe mode, reset, and share
 * a report.
 */
public class HelpActivity extends Activity {
    private TextView report;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Read fresh values written by the launcher process.
        SharedPreferences sp = getSharedPreferences(Prefs.FILE, Context.MODE_MULTI_PROCESS);
        boolean crashed = getIntent().getBooleanExtra("crashed", false);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0E0F12);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col);

        TextView title = text(crashed ? "Glass Launcher stopped" : "Glass Launcher Help", 26, 0xFFF2F3F5);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(text(crashed
                ? "It ran into a problem and closed. The next start will automatically use compatibility mode. "
                + "Please tap “Share report” and send it so the problem can be fixed."
                : "Use these buttons if the launcher doesn't open or closes by itself.", 16, 0xFFC8CAD1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(button("Start launcher", new View.OnClickListener() {
            @Override public void onClick(View v) { start(0); }
        }));
        row.addView(button("Compatibility mode", new View.OnClickListener() {
            @Override public void onClick(View v) { start(1); }
        }));
        row.addView(button("Safe mode", new View.OnClickListener() {
            @Override public void onClick(View v) { start(2); }
        }));
        col.addView(row);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("Share report", new View.OnClickListener() {
            @Override public void onClick(View v) { share(); }
        }));
        row2.addView(button("Reset launcher", new View.OnClickListener() {
            @Override public void onClick(View v) { reset(); }
        }));
        row2.addView(button("Default home app", new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
                catch (Throwable t) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            }
        }));
        col.addView(row2);

        report = text(buildReport(sp), 13, 0xFFB3B5BD);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
        col.addView(report);
        setContentView(sv);
    }

    private String buildReport(SharedPreferences sp) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        String version = "?";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        StringBuilder r = new StringBuilder();
        r.append("Glass Launcher ").append(version).append('\n');
        r.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.BRAND).append(" / ").append(Build.HARDWARE).append(")\n");
        r.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        r.append("Build: ").append(Build.DISPLAY).append('\n');
        r.append("CPU: ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
        r.append("Screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels).append(" @ ").append(dm.densityDpi).append(" dpi\n");
        r.append("Mode: ").append(sp.getBoolean(CrashGuard.K_LITE, false) ? "compatibility" : "normal")
                .append(" · failed starts: ").append(sp.getInt(CrashGuard.K_FAILED_BOOTS, 0))
                .append(" · crashes: ").append(sp.getInt("crash_count", 0)).append('\n');
        r.append("Last stage: ").append(sp.getString(CrashGuard.K_STAGE, "-")).append("\n\n");
        String err = sp.getString("last_error", null);
        r.append(err != null ? err : "No error recorded.");
        return r.toString();
    }

    private void start(int mode) {
        SharedPreferences sp = getSharedPreferences(Prefs.FILE, Context.MODE_MULTI_PROCESS);
        SharedPreferences.Editor e = sp.edit().putBoolean(CrashGuard.K_BOOT_PENDING, false);
        if (mode == 0) e.putInt(CrashGuard.K_FAILED_BOOTS, 0).putBoolean(CrashGuard.K_LITE, false);
        else if (mode == 1) e.putInt(CrashGuard.K_FAILED_BOOTS, 0).putBoolean(CrashGuard.K_LITE, true);
        else e.putInt(CrashGuard.K_FAILED_BOOTS, 2);
        e.commit();
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void reset() {
        SharedPreferences sp = getSharedPreferences(Prefs.FILE, Context.MODE_MULTI_PROCESS);
        sp.edit().clear().putInt("schema", Prefs.SCHEMA).commit();
        report.setText(buildReport(sp));
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Glass Launcher report")
                .putExtra(Intent.EXTRA_TEXT, report.getText().toString());
        try { startActivity(Intent.createChooser(i, "Share report")); } catch (Throwable ignored) {}
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(p);
        return b;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
