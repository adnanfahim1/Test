package com.adnan.glasslink;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the link to the head unit: connects over Bluetooth to the paired head unit (and,
 * if turned on, accepts the head unit over the phone's hotspot). Sends weather from the
 * phone's location and internet, and the maps app's next turn.
 */
public final class LinkService extends Service {
    static final UUID SERVICE_UUID = UUID.fromString("6b1c2f5e-3c55-4d7e-9a4f-6f1e2a9b7c41");
    static final int TCP_PORT = 47821;
    static final String PREFS = "glass_link";
    static final String K_DEVICE = "device", K_HOTSPOT = "hotspot", K_ENABLED = "enabled", K_LAT = "lat", K_LON = "lon", K_PLACE = "place";
    private static final int NOTE_ID = 7;
    private static final long WEATHER_EVERY_MS = 15 * 60 * 1000L;

    // State shown by the app screen.
    static volatile String status = "Stopped";
    static volatile String linkedTo;
    static volatile long lastWeatherAt;
    static volatile LinkService running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile boolean active;
    private volatile OutputStream out;
    private volatile Closeable conn;
    private volatile ServerSocket server;
    private volatile String lastNav;
    private SharedPreferences sp;

    static void start(Context c) {
        Intent i = new Intent(c, LinkService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, LinkService.class));
    }

    static boolean granted(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        running = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            goForeground();
        } catch (Throwable t) {
            // Android refused (for example started from the background without permission).
            status = "Open Glass Link once to start";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!active) {
            active = true;
            status = "Looking for the head unit…";
            new Thread(new Runnable() { @Override public void run() { bluetoothLoop(); } }, "link-bt").start();
            if (sp.getBoolean(K_HOTSPOT, false)) {
                new Thread(new Runnable() { @Override public void run() { hotspotLoop(); } }, "link-hotspot").start();
            }
            main.postDelayed(weatherTimer, WEATHER_EVERY_MS);
            MainActivity.refreshSoon();
        }
        return START_STICKY;
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel("link") == null) {
                NotificationChannel ch = new NotificationChannel("link", "Connection to the car", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Notification n = note();
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (granted(this, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            try {
                startForeground(NOTE_ID, n, types);
            } catch (RuntimeException e) {
                // Location can't be used from the background start (after reboot): link only.
                startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            }
        } else {
            startForeground(NOTE_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification note() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "link") : new Notification.Builder(this);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(linkedTo != null ? "Connected to " + linkedTo : "Glass Link")
                .setContentText(linkedTo != null ? "Sharing weather and navigation with the car" : "Waiting for the car")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags))
                .build();
    }

    private void updateNote() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTE_ID, note());
        } catch (Throwable ignored) {}
        MainActivity.refreshSoon();
    }

    @Override
    public void onDestroy() {
        active = false;
        main.removeCallbacks(weatherTimer);
        close(conn);
        close(server);
        status = "Stopped";
        linkedTo = null;
        if (running == this) running = null;
        MainActivity.refreshSoon();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- Bluetooth: connect to the head unit ---------------------------------------------------
    private void bluetoothLoop() {
        int idle = 0;
        while (active) {
            if (out != null || (Build.VERSION.SDK_INT >= 31 && !granted(this, Manifest.permission.BLUETOOTH_CONNECT))) {
                if (out == null) status = "Allow “Nearby devices” in Glass Link";
                pause(5000);
                continue;
            }
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) {
                status = "Turn on Bluetooth";
                MainActivity.refreshSoon();
                pause(10000);
                continue;
            }
            boolean linked = false;
            for (BluetoothDevice d : candidates(ad)) {
                if (!active || out != null) break;
                BluetoothSocket s = null;
                try {
                    s = d.createRfcommSocketToServiceRecord(SERVICE_UUID);
                    s.connect();
                    linked = true;
                    sp.edit().putString(K_DEVICE, d.getAddress()).apply();
                    serve(s, s.getInputStream(), s.getOutputStream(), name(d), "Bluetooth");
                    idle = 0;
                } catch (Throwable t) {
                    close(s);
                }
            }
            if (!linked && out == null) {
                status = "Looking for the head unit…";
                MainActivity.refreshSoon();
            }
            // Retry often at first, then every minute to save battery.
            pause(idle++ < 8 ? 15000 : 60000);
        }
    }

    /** The chosen head unit, else paired car/audio devices (a car stereo reports as audio). */
    private List<BluetoothDevice> candidates(BluetoothAdapter ad) {
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        String chosen = sp.getString(K_DEVICE, null);
        try {
            for (BluetoothDevice d : ad.getBondedDevices()) {
                if (d.getAddress().equals(chosen)) out.add(0, d);
            }
            if (sp.getBoolean("device_fixed", false) && !out.isEmpty()) return out;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                if (d.getAddress().equals(chosen)) continue;
                BluetoothClass bc = d.getBluetoothClass();
                int major = bc != null ? bc.getMajorDeviceClass() : BluetoothClass.Device.Major.UNCATEGORIZED;
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.UNCATEGORIZED
                        || major == BluetoothClass.Device.Major.COMPUTER) {
                    out.add(d);
                }
            }
        } catch (SecurityException ignored) {}
        return out;
    }

    static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (SecurityException e) {
            return "head unit";
        }
    }

    // ---- Phone hotspot: the head unit connects to us ---------------------------------------------
    private void hotspotLoop() {
        while (active && sp.getBoolean(K_HOTSPOT, false)) {
            try {
                server = new ServerSocket(TCP_PORT);
                while (active) {
                    Socket s = server.accept();
                    if (out != null) { close(s); continue; }
                    s.setKeepAlive(true);
                    final Socket fs = s;
                    serve(fs, fs.getInputStream(), fs.getOutputStream(), "car (hotspot)", "hotspot");
                }
            } catch (Throwable t) {
                close(server);
                pause(10000);
            }
        }
    }

    // ---- Session ---------------------------------------------------------------------------------
    private void serve(Closeable c, InputStream in, OutputStream o, String who, String via) throws Exception {
        synchronized (this) {
            if (out != null) { close(c); return; }
            out = o;
            conn = c;
        }
        linkedTo = who;
        status = "Connected to " + who + " over " + via;
        updateNote();
        try {
            send(new JSONObject().put("t", "hello").put("name", Build.MODEL).put("app", "Glass Link").put("v", 1).toString());
            String nav = lastNav;
            if (nav != null) send(nav);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while (active && (line = r.readLine()) != null) {
                if (line.length() > 64 * 1024) continue;
                try {
                    JSONObject m = new JSONObject(line);
                    if ("req".equals(m.optString("t")) && "weather".equals(m.optString("what"))) {
                        final String place = m.has("place") ? m.optString("place") : null;
                        final double lat = m.optDouble("lat", Double.NaN), lon = m.optDouble("lon", Double.NaN);
                        new Thread(new Runnable() {
                            @Override
                            public void run() { sendWeather(place, lat, lon); }
                        }, "weather").start();
                    }
                } catch (Throwable ignored) {}
            }
        } finally {
            synchronized (this) {
                out = null;
                conn = null;
            }
            close(c);
            linkedTo = null;
            status = active ? "Looking for the head unit…" : "Stopped";
            updateNote();
        }
    }

    private void send(final String line) {
        final OutputStream o = out;
        if (o == null) return;
        writer.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    o.write((line + "\n").getBytes("UTF-8"));
                    o.flush();
                } catch (Throwable t) {
                    close(conn);
                }
            }
        });
    }

    /** Called by NavListener for every change of the maps app's navigation notification. */
    static void nav(String json) {
        LinkService s = running;
        if (s == null) return;
        s.lastNav = json;
        s.send(json);
        s.main.removeCallbacks(s.navRepeat);
        s.main.postDelayed(s.navRepeat, 45000);
    }

    /** Re-sends the current turn so the car knows the route is still running. */
    private final Runnable navRepeat = new Runnable() {
        @Override
        public void run() {
            String n = lastNav;
            if (n == null || n.contains("\"active\":false")) return;
            send(n);
            main.postDelayed(this, 45000);
        }
    };

    private final Runnable weatherTimer = new Runnable() {
        @Override
        public void run() {
            if (out != null) {
                new Thread(new Runnable() {
                    @Override
                    public void run() { sendWeather(null, Double.NaN, Double.NaN); }
                }, "weather").start();
            }
            main.postDelayed(this, WEATHER_EVERY_MS);
        }
    };

    // ---- Weather ---------------------------------------------------------------------------------
    private void sendWeather(String place, double lat, double lon) {
        try {
            if (place == null || Double.isNaN(lat) || Double.isNaN(lon)) {
                Location l = location();
                if (l != null) {
                    lat = l.getLatitude();
                    lon = l.getLongitude();
                    place = placeName(lat, lon);
                    sp.edit().putFloat(K_LAT, (float) lat).putFloat(K_LON, (float) lon).putString(K_PLACE, place).apply();
                } else if (sp.contains(K_LAT)) {
                    lat = sp.getFloat(K_LAT, 0);
                    lon = sp.getFloat(K_LON, 0);
                    place = sp.getString(K_PLACE, "");
                } else {
                    return; // no location yet; the car falls back to its own internet
                }
            }
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day"
                            + "&hourly=precipitation_probability,visibility&forecast_days=1&timezone=auto", lat, lon);
            String raw = get(url);
            send(new JSONObject().put("t", "weather").put("raw", raw).put("place", place != null ? place : "").toString());
            lastWeatherAt = System.currentTimeMillis();
            MainActivity.refreshSoon();
        } catch (Throwable ignored) {
            // No internet on the phone right now; asked again on the next request.
        }
    }

    @SuppressWarnings("MissingPermission")
    private Location location() {
        if (!granted(this, Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(this, Manifest.permission.ACCESS_FINE_LOCATION)) return null;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private String placeName(double lat, double lon) {
        try {
            if (!Geocoder.isPresent()) return "";
            List<Address> a = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
            if (a != null && !a.isEmpty()) {
                Address ad = a.get(0);
                String n = ad.getLocality() != null ? ad.getLocality() : ad.getSubAdminArea() != null ? ad.getSubAdminArea() : ad.getAdminArea();
                return n != null ? n : "";
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "GlassLink/1.0 (Android phone)");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    static void close(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}
