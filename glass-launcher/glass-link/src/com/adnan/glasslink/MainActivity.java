package com.adnan.glasslink;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** One screen: connection status, set-up checklist, car settings, updates and a log. */
public final class MainActivity extends Activity {
    private static final int REQ = 1, PICK = 2;
    private static final int BG = 0xFF0E0F12, CARD = 0xFF1A1D24, LINE = 0xFF2A2E37, TEXT = 0xFFF2F4F7, MUTED = 0xFF9AA3AE,
            ACCENT = 0xFF22C3E6, GREEN = 0xFF3DDC84, AMBER = 0xFFF5B942;
    private static MainActivity shown;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SharedPreferences sp;
    private LinearLayout setupList, tipsBox;
    private TextView statusTitle, statusSub, statusDetail, startBtn, setupHeader, carInfo, hostInfo, updState, logView, logToggle;
    private View statusDot;
    private TextView sendBundled;
    private File bundled;
    private String bundledVersion, pickedNote;
    private int bundledCode;
    private boolean showLog;

    static void refreshSoon() {
        MAIN.post(new Runnable() {
            @Override
            public void run() { if (shown != null) shown.refresh(); }
        });
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            MAIN.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinkService.installCrashLog(this);
        sp = getSharedPreferences(LinkService.PREFS, MODE_PRIVATE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(22), dp(18), dp(28));
        sv.addView(col);

        // Header
        LinearLayout head = row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        head.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout ht = column();
        ht.addView(text("Glass Link", 24, TEXT, true));
        ht.addView(text("Weather and directions from this phone to your car", 13, MUTED, false));
        head.addView(ht, weightLp(dp(12)));
        col.addView(head);

        // Status card
        LinearLayout status = card();
        LinearLayout st = row();
        statusDot = new View(this);
        st.addView(statusDot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        statusTitle = text("", 20, TEXT, true);
        st.addView(statusTitle, weightLp(dp(12)));
        status.addView(st);
        statusSub = text("", 14, MUTED, false);
        status.addView(statusSub, topLp(dp(6)));
        statusDetail = text("", 14, TEXT, false);
        status.addView(statusDetail, topLp(dp(10)));
        startBtn = button("Start", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleLink(); }
        });
        status.addView(startBtn, topLp(dp(16)));
        tipsBox = column();
        status.addView(tipsBox, topLp(dp(14)));
        col.addView(status, topLp(dp(20)));

        // Set-up checklist
        LinearLayout setup = card();
        setupHeader = text("Set up", 17, TEXT, true);
        setup.addView(setupHeader);
        setupList = column();
        setup.addView(setupList, topLp(dp(6)));
        col.addView(setup, topLp(dp(14)));

        // Car
        LinearLayout car = card();
        car.addView(text("Your car", 17, TEXT, true));
        carInfo = text("", 14, MUTED, false);
        car.addView(carInfo, topLp(dp(6)));
        car.addView(button("Choose the car's Bluetooth", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) { chooseCar(); }
        }), topLp(dp(12)));
        hostInfo = text("", 14, MUTED, false);
        car.addView(hostInfo, topLp(dp(14)));
        car.addView(button("Enter the car's Wi-Fi address", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) { askHost(); }
        }), topLp(dp(10)));
        col.addView(car, topLp(dp(14)));

        // Update
        LinearLayout upd = card();
        upd.addView(text("Update the car app", 17, TEXT, true));
        updState = text("", 14, MUTED, false);
        upd.addView(updState, topLp(dp(6)));
        sendBundled = button("Send update to car", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) { if (bundled != null) confirmSend(bundled, bundledVersion); }
        });
        upd.addView(sendBundled, topLp(dp(12)));
        upd.addView(button("Choose an APK file…", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try { startActivityForResult(i, PICK); } catch (Throwable t) { updateMsg("No file picker on this phone"); }
            }
        }), topLp(dp(10)));
        upd.addView(text("The car asks you to confirm the install. Only Glass Launcher signed with the same key, and not older "
                + "than the installed one, is accepted, so your settings are kept.", 12, MUTED, false), topLp(dp(10)));
        col.addView(upd, topLp(dp(14)));

        // Log
        LinearLayout lg = card();
        LinearLayout lh = row();
        lh.addView(text("Connection log", 17, TEXT, true), weightLp(0));
        logToggle = text("Show", 14, ACCENT, true);
        logToggle.setPadding(dp(10), dp(6), dp(4), dp(6));
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showLog = !showLog; refresh(); }
        });
        lh.addView(logToggle);
        TextView copy = text("Copy", 14, ACCENT, true);
        copy.setPadding(dp(14), dp(6), dp(4), dp(6));
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { copyLog(); }
        });
        lh.addView(copy);
        lg.addView(lh);
        logView = text("", 12, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        lg.addView(logView, topLp(dp(8)));
        col.addView(lg, topLp(dp(14)));

        setContentView(sv);
        prepareBundled();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shown = this;
        // Opening the app (re)starts the link if it was on, with location while in use.
        if (sp.getBoolean(LinkService.K_ENABLED, false) && LinkService.running == null) {
            try { LinkService.start(this); } catch (Throwable t) { LinkService.log("Couldn't start: " + t); }
        }
        MAIN.removeCallbacks(ticker);
        MAIN.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        MAIN.removeCallbacks(ticker);
        if (shown == this) shown = null;
    }

    private void toggleLink() {
        boolean on = LinkService.running == null;
        sp.edit().putBoolean(LinkService.K_ENABLED, on).apply();
        if (on) {
            if (!LinkService.hasBluetoothPermission(this) || !has(Manifest.permission.ACCESS_COARSE_LOCATION)) askPermissions();
            try { LinkService.start(this); } catch (Throwable t) { LinkService.log("Couldn't start: " + t); }
        } else {
            LinkService.stop(this);
        }
        MAIN.postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 300);
    }

    // ---- Refresh ---------------------------------------------------------------------------------
    private void refresh() {
        boolean running = LinkService.running != null;
        int state = running ? LinkService.state : LinkService.ST_STOPPED;
        long now = System.currentTimeMillis();
        int dotColor;
        if (state == LinkService.ST_CONNECTED) {
            dotColor = GREEN;
            statusTitle.setText("Connected to " + (LinkService.linkedTo != null ? LinkService.linkedTo : "your car"));
            statusSub.setText("Over " + LinkService.via + (LinkService.carVersion != null ? " · car app " + LinkService.carVersion : "")
                    + (bundled != null && LinkService.carUpdates && bundledCode > LinkService.carCode ? " · update available below" : ""));
        } else if (state == LinkService.ST_SEARCHING) {
            dotColor = AMBER;
            statusTitle.setText("Looking for your car…");
            long secs = (now - LinkService.searchingSince) / 1000;
            statusSub.setText(LinkService.problem != null ? LinkService.problem
                    : "Trying Bluetooth and Wi-Fi" + (secs > 5 ? " · " + secs + " s" : ""));
        } else {
            dotColor = MUTED;
            statusTitle.setText("Not running");
            statusSub.setText("Tap Start. Glass Link keeps running in the background and reconnects by itself.");
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(dotColor);
        statusDot.setBackground(dot);
        StringBuilder d = new StringBuilder();
        if (LinkService.lastWeatherAt > 0) d.append("☀  Weather sent at ").append(DateFormat.format("h:mm a", new Date(LinkService.lastWeatherAt)));
        String turn = NavListener.lastTurn;
        if (turn != null) d.append(d.length() > 0 ? "\n" : "").append("➜  ").append(turn);
        statusDetail.setText(d.toString());
        statusDetail.setVisibility(d.length() > 0 && state == LinkService.ST_CONNECTED ? View.VISIBLE : View.GONE);
        startBtn.setText(running ? "Stop" : "Start");
        startBtn.setBackground(pill(running ? LINE : ACCENT));
        startBtn.setTextColor(running ? TEXT : 0xFF001018);

        // Tips after a while without a connection.
        tipsBox.removeAllViews();
        if (state == LinkService.ST_SEARCHING && now - LinkService.searchingSince > 40000) {
            tipsBox.addView(text("Can't find your car?", 15, TEXT, true));
            tipsBox.addView(text("1. Turn the car on and check it runs Glass Launcher 1.6.2 or newer (car: Settings › About device › "
                    + "Launcher). Older versions only connect over this phone's hotspot.\n"
                    + "2. Easiest: turn on this phone's hotspot and connect the car to it (car: Settings › Wi-Fi & internet). "
                    + "Glass Link finds it within seconds.\n"
                    + "3. Bluetooth works only if the car's Android Bluetooth is the one paired with this phone. On many head "
                    + "units it's a separate module, so use Wi-Fi.\n"
                    + "4. Still nothing? On the car open Settings › Weather › Phone link, note the Wi-Fi address, and enter "
                    + "it below under Your car.", 13, MUTED, false), topLp(dp(6)));
        }

        // Set-up checklist
        setupList.removeAllViews();
        int missing = 0;
        missing += check("Nearby devices (Bluetooth)", LinkService.hasBluetoothPermission(this), new Runnable() { public void run() { askPermissions(); } });
        missing += check("Location (weather where you are)", has(Manifest.permission.ACCESS_COARSE_LOCATION), new Runnable() { public void run() { askPermissions(); } });
        missing += check("Notifications", Build.VERSION.SDK_INT < 33 || has("android.permission.POST_NOTIFICATIONS"), new Runnable() { public void run() { askPermissions(); } });
        missing += check("Navigation access (next turn in the car)", navAccess(), new Runnable() {
            public void run() {
                try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
                catch (Throwable t) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            }
        });
        missing += check("Keep running in the background", batteryOk(), new Runnable() { public void run() { askBattery(); } });
        setupHeader.setText(missing == 0 ? "Set up  ✓ all done" : "Set up  ·  " + missing + " to do");

        // Car
        String dev = sp.getString(LinkService.K_DEVICE, null);
        carInfo.setText("Bluetooth: " + (dev != null ? deviceName(dev) + (sp.getBoolean(LinkService.K_FIXED, false) ? "" : " (automatic)")
                : "automatic (paired car stereos)"));
        String host = sp.getString(LinkService.K_HOST, null), last = sp.getString(LinkService.K_LAST_HOST, null);
        hostInfo.setText("Wi-Fi: " + (host != null && host.length() > 0 ? host : "found automatically")
                + (last != null && (host == null || host.length() == 0) ? " · last seen at " + last : ""));

        // Update
        StringBuilder u = new StringBuilder();
        u.append("Car has: ").append(LinkService.carVersion != null ? "Glass Launcher " + LinkService.carVersion
                : LinkService.linkedTo != null ? "an older Glass Launcher" : "not connected");
        if (bundledVersion != null) u.append("\nIncluded here: Glass Launcher ").append(bundledVersion);
        String stx = pickedNote != null ? pickedNote : LinkService.updateStatus;
        if (stx != null) u.append("\n").append(stx);
        updState.setText(u.toString());
        boolean newer = bundled != null && (LinkService.carVersion == null || bundledCode > LinkService.carCode);
        sendBundled.setVisibility(bundled != null ? View.VISIBLE : View.GONE);
        boolean canSend = !LinkService.updating && LinkService.linkedTo != null;
        sendBundled.setEnabled(canSend);
        sendBundled.setAlpha(canSend ? 1f : 0.45f);
        sendBundled.setText(newer ? "Send update to car (" + bundledVersion + ")" : "Send " + bundledVersion + " to car again");

        // Log
        logToggle.setText(showLog ? "Hide" : "Show");
        String log = LinkService.logText();
        logView.setText(log.length() > 0 ? log : "Nothing yet.");
        logView.setVisibility(showLog ? View.VISIBLE : View.GONE);
    }

    private int check(String label, boolean ok, final Runnable fix) {
        LinearLayout r = row();
        r.setPadding(0, dp(9), 0, dp(9));
        TextView mark = text(ok ? "✓" : "!", 16, ok ? GREEN : AMBER, true);
        mark.setGravity(Gravity.CENTER);
        r.addView(mark, new LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(text(label, 15, ok ? MUTED : TEXT, false), weightLp(dp(6)));
        if (!ok) {
            TextView go = text("Allow", 14, ACCENT, true);
            r.addView(go);
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try { fix.run(); } catch (Throwable t) { toast("Open Android Settings › Apps › Glass Link"); }
                }
            });
        }
        setupList.addView(r);
        return ok ? 0 : 1;
    }

    // ---- Permissions -----------------------------------------------------------------------------
    private boolean has(String p) { return LinkService.granted(this, p); }

    private boolean navAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(this, NavListener.class).flattenToString());
    }

    private boolean batteryOk() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
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
            // Restart so the service picks up Bluetooth and location.
            LinkService.stop(this);
            try { LinkService.start(this); } catch (Throwable ignored) {}
        }
    }

    // ---- Car -------------------------------------------------------------------------------------
    private String deviceName(String address) {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return address;
            for (BluetoothDevice d : ad.getBondedDevices()) if (d.getAddress().equals(address)) return LinkService.name(d);
        } catch (Throwable ignored) {}
        return address;
    }

    private void chooseCar() {
        if (!LinkService.hasBluetoothPermission(this)) { askPermissions(); return; }
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
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Which device is your car?")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int which) {
                        SharedPreferences.Editor e = sp.edit();
                        if (which == 0) e.remove(LinkService.K_DEVICE).putBoolean(LinkService.K_FIXED, false);
                        else e.putString(LinkService.K_DEVICE, devs.get(which - 1).getAddress()).putBoolean(LinkService.K_FIXED, true);
                        e.apply();
                        refresh();
                    }
                })
                .show();
    }

    private void askHost() {
        final EditText e = new EditText(this);
        e.setHint("e.g. 192.168.43.120  (empty = automatic)");
        e.setInputType(InputType.TYPE_CLASS_PHONE);
        e.setText(sp.getString(LinkService.K_HOST, ""));
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(e);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Car's Wi-Fi address")
                .setMessage("Shown on the car in Settings › Weather › Phone link. Phone and car must be on the same Wi-Fi or hotspot.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String h = e.getText().toString().trim();
                        if (h.length() > 0 && !h.matches("[0-9a-zA-Z.:\\-]{3,64}")) { toast("That doesn't look like an address"); return; }
                        sp.edit().putString(LinkService.K_HOST, h).apply();
                        LinkService.log(h.length() > 0 ? "Car address set to " + h : "Car address: automatic");
                        refresh();
                    }
                })
                .show();
    }

    // ---- Update the car app ----------------------------------------------------------------------
    private void updateMsg(String m) {
        pickedNote = m;
        refresh();
    }

    /** Copies the car app shipped inside Glass Link (if any) to a file and reads its version. */
    private void prepareBundled() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(getCacheDir(), "car-bundled.apk");
                    java.io.InputStream in = getAssets().open("car/GlassLauncher.apk");
                    java.io.OutputStream o = new java.io.FileOutputStream(f);
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    in.close();
                    o.close();
                    android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                    if (pi != null) {
                        bundled = f;
                        bundledVersion = pi.versionName;
                        bundledCode = pi.versionCode;
                    }
                } catch (Throwable ignored) {}
                refreshSoon();
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        updateMsg("Reading the file…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(getCacheDir(), "car-picked.apk");
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    java.io.OutputStream o = new java.io.FileOutputStream(f);
                    byte[] b = new byte[65536];
                    int n;
                    long total = 0;
                    while ((n = in.read(b)) > 0) {
                        o.write(b, 0, n);
                        total += n;
                        if (total > 100L * 1024 * 1024) throw new Exception("too large");
                    }
                    in.close();
                    o.close();
                    final android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                    if (pi == null || !"com.adnan.glasslauncher".equals(pi.packageName)) {
                        MAIN.post(new Runnable() { @Override public void run() { updateMsg("That file isn't a Glass Launcher APK."); } });
                        return;
                    }
                    final File ff = f;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            pickedNote = null;
                            confirmSend(ff, pi.versionName);
                        }
                    });
                } catch (Throwable t) {
                    MAIN.post(new Runnable() { @Override public void run() { updateMsg("Couldn't read that file."); } });
                }
            }
        }).start();
    }

    private void confirmSend(final File apk, String version) {
        if (LinkService.linkedTo == null) {
            updateMsg("Connect to the car first (see the status at the top).");
            return;
        }
        if (!LinkService.carUpdates) {
            updateMsg("The car's Glass Launcher is too old to receive updates this way. Install the new version once from a "
                    + "USB stick; after that, updates can come from the phone.");
            return;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Send Glass Launcher " + version + " to the car?")
                .setMessage("The car has " + (LinkService.carVersion != null ? LinkService.carVersion : "an unknown version")
                        + ". Keep the phone near the car until it's sent.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pickedNote = null;
                        LinkService.sendUpdate(apk);
                        refresh();
                    }
                })
                .show();
    }

    private void copyLog() {
        try {
            String text = "Glass Link " + versionName() + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android "
                    + Build.VERSION.RELEASE + "\n" + LinkService.logText();
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Glass Link log", text));
            toast("Log copied");
        } catch (Throwable t) {
            toast("Couldn't copy");
        }
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; }
    }

    // ---- Views ---------------------------------------------------------------------------------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView button(String s, boolean primary, View.OnClickListener l) {
        TextView b = text(s, 16, primary ? 0xFF001018 : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(52));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setBackground(pill(primary ? ACCENT : LINE));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(26));
        g.setColor(color);
        return g;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(CARD);
        g.setStroke(dp(1), LINE);
        c.setBackground(g);
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout.LayoutParams topLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private LinearLayout.LayoutParams weightLp(int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = left;
        return lp;
    }
}
