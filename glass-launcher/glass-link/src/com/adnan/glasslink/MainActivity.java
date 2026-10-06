package com.adnan.glasslink;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Set-up screen: permissions, navigation access, which car to use, on/off. */
public final class MainActivity extends Activity {
    private static final int REQ = 1;
    private static MainActivity shown;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SharedPreferences sp;
    private TextView status, permState, navState, carState, detail;
    private Button permBtn, navBtn, startBtn;
    private Switch hotspot;

    static void refreshSoon() {
        MAIN.post(new Runnable() {
            @Override
            public void run() { if (shown != null) shown.refresh(); }
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(LinkService.PREFS, MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        TextView title = text("Glass Link", 28, true);
        col.addView(title);
        col.addView(text("Sends your phone's weather and the next turn from Google Maps or Waze to the Glass Launcher "
                + "on your car's head unit.", 15, false), margins(0, dp(6)));

        status = text("", 17, true);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x223F7FFF);
        status.setBackground(bg);
        col.addView(status, margins(0, dp(18)));
        detail = text("", 14, false);
        col.addView(detail, margins(0, dp(8)));

        col.addView(text("1. Permissions", 18, true), margins(0, dp(24)));
        permState = text("", 14, false);
        col.addView(permState);
        permBtn = button("Allow", new View.OnClickListener() {
            @Override
            public void onClick(View v) { askPermissions(); }
        });
        col.addView(permBtn, margins(0, dp(6)));

        col.addView(text("2. Navigation", 18, true), margins(0, dp(24)));
        navState = text("", 14, false);
        col.addView(navState);
        navBtn = button("Allow navigation access", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                } catch (Throwable t) {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                }
            }
        });
        col.addView(navBtn, margins(0, dp(6)));

        col.addView(text("3. Your car", 18, true), margins(0, dp(24)));
        carState = text("", 14, false);
        col.addView(carState);
        col.addView(button("Choose head unit", new View.OnClickListener() {
            @Override
            public void onClick(View v) { chooseCar(); }
        }), margins(0, dp(6)));
        hotspot = new Switch(this);
        hotspot.setText("Also connect over this phone's hotspot");
        hotspot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hotspot.setChecked(sp.getBoolean(LinkService.K_HOTSPOT, false));
        hotspot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                sp.edit().putBoolean(LinkService.K_HOTSPOT, on).apply();
                if (LinkService.running != null) {
                    LinkService.stop(MainActivity.this);
                    LinkService.start(MainActivity.this);
                }
            }
        });
        col.addView(hotspot, margins(0, dp(14)));
        col.addView(text("Use this when the head unit joins your phone's hotspot for internet. Only turn it on for the "
                + "car's hotspot: while on, any device on the same Wi-Fi network as this phone could connect.", 13, false));

        startBtn = button("Start", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = LinkService.running == null;
                if (on && !hasBluetooth()) askPermissions();
                sp.edit().putBoolean(LinkService.K_ENABLED, on).apply();
                if (on) LinkService.start(MainActivity.this);
                else LinkService.stop(MainActivity.this);
                MAIN.postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 400);
            }
        });
        col.addView(startBtn, margins(0, dp(28)));
        col.addView(text("On the head unit, Glass Launcher needs “Nearby devices” allowed (Settings › Bluetooth). "
                + "Weather appears on the Weather card with a “From phone” label; turn-by-turn appears on the Navigation card "
                + "while Google Maps or Waze is guiding on this phone.", 13, false), margins(0, dp(14)));
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = this;
        // Opening the app (re)starts the link if it was on, with location while in use.
        if (sp.getBoolean(LinkService.K_ENABLED, false) && LinkService.running == null) {
            try { LinkService.start(this); } catch (Throwable ignored) {}
        }
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (shown == this) shown = null;
    }

    private void refresh() {
        boolean loc = LinkService.granted(this, Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean bt = hasBluetooth();
        boolean notes = Build.VERSION.SDK_INT < 33 || LinkService.granted(this, "android.permission.POST_NOTIFICATIONS");
        permState.setText((bt ? "✓" : "✗") + " Nearby devices (Bluetooth to the car)\n"
                + (loc ? "✓" : "✗") + " Location (weather where you are)\n"
                + (notes ? "✓" : "✗") + " Notifications (shows when it's connected)");
        permBtn.setVisibility(bt && loc && notes ? View.GONE : View.VISIBLE);

        boolean nav = navAccess();
        navState.setText(nav ? "✓ Glass Link can read the navigation of Google Maps, Waze and other maps apps. "
                + "Other notifications are ignored and never leave the phone."
                : "✗ Allow notification access so the next turn can be shown in the car.");
        navBtn.setVisibility(nav ? View.GONE : View.VISIBLE);

        String dev = sp.getString(LinkService.K_DEVICE, null);
        String devName = dev != null ? deviceName(dev) : null;
        carState.setText(devName != null ? "Head unit: " + devName
                : "Automatic: tries your paired car stereos. Pair the phone with the head unit in Bluetooth first.");

        boolean running = LinkService.running != null;
        status.setText(running ? LinkService.status : "Stopped");
        StringBuilder d = new StringBuilder();
        if (LinkService.lastWeatherAt > 0) {
            d.append("Weather sent at ").append(DateFormat.format("h:mm a", new Date(LinkService.lastWeatherAt)));
        }
        String turn = NavListener.lastTurn;
        if (turn != null) d.append(d.length() > 0 ? "\n" : "").append("Next turn: ").append(turn);
        detail.setText(d.toString());
        detail.setVisibility(d.length() > 0 ? View.VISIBLE : View.GONE);
        startBtn.setText(running ? "Stop" : "Start");
    }

    private boolean hasBluetooth() {
        return Build.VERSION.SDK_INT < 31 || LinkService.granted(this, "android.permission.BLUETOOTH_CONNECT");
    }

    private boolean navAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, NavListener.class).flattenToString());
    }

    private void askPermissions() {
        List<String> p = new ArrayList<String>();
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) p.add("android.permission.BLUETOOTH_CONNECT");
        if (Build.VERSION.SDK_INT >= 33) p.add("android.permission.POST_NOTIFICATIONS");
        requestPermissions(p.toArray(new String[0]), REQ);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        refresh();
        if (sp.getBoolean(LinkService.K_ENABLED, false)) {
            // Restart so the service picks up location for weather.
            LinkService.stop(this);
            LinkService.start(this);
        }
    }

    private String deviceName(String address) {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return address;
            for (BluetoothDevice d : ad.getBondedDevices()) if (d.getAddress().equals(address)) return LinkService.name(d);
        } catch (Throwable ignored) {}
        return address;
    }

    private void chooseCar() {
        if (!hasBluetooth()) { askPermissions(); return; }
        final List<BluetoothDevice> devs = new ArrayList<BluetoothDevice>();
        List<String> names = new ArrayList<String>();
        names.add("Automatic (paired car stereos)");
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null) for (BluetoothDevice d : ad.getBondedDevices()) {
                devs.add(d);
                names.add(LinkService.name(d));
            }
        } catch (Throwable ignored) {}
        new AlertDialog.Builder(this)
                .setTitle("Which device is your head unit?")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        SharedPreferences.Editor e = sp.edit();
                        if (which == 0) e.remove(LinkService.K_DEVICE).putBoolean("device_fixed", false);
                        else e.putString(LinkService.K_DEVICE, devs.get(which - 1).getAddress()).putBoolean("device_fixed", true);
                        e.apply();
                        refresh();
                    }
                })
                .show();
    }

    // ---- Small view helpers ----------------------------------------------------------------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams margins(int left, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(left, top, 0, 0);
        return lp;
    }
}
