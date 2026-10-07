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
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.DateFormat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the link to Glass Launcher on the head unit and sends it the phone's weather, the
 * maps app's next turn and (on request) app updates.
 *
 * It tries every way at the same time and uses the first that works:
 *  - Bluetooth to the paired head unit (secure, then insecure RFCOMM);
 *  - Wi-Fi: the head unit announces itself on the local network (phone hotspot, car hotspot
 *    or shared Wi-Fi) and the phone connects to it;
 *  - Wi-Fi to an address typed in by the user (when the network blocks announcements).
 * Every background loop catches everything, so one odd error can't stop the app.
 */
public final class LinkService extends Service {
    static final UUID SERVICE_UUID = UUID.fromString("6b1c2f5e-3c55-4d7e-9a4f-6f1e2a9b7c41");
    static final UUID SERVICE_UUID_INSECURE = UUID.fromString("6b1c2f5e-3c55-4d7e-9a4f-6f1e2a9b7c42");
    /** CAR_PORT: the car app (1.6.2+) listens there. PHONE_PORT: this app listens there on its hotspot
     *  (the car dials it; car app 1.5 only knows this one). Separate ports so they never collide. */
    static final int CAR_PORT = 47823, PHONE_PORT = 47821, BEACON_PORT = 47822;
    static final String PREFS = "glass_link";
    static final String K_DEVICE = "device", K_ENABLED = "enabled", K_LAT = "lat", K_LON = "lon", K_PLACE = "place",
            K_HOST = "host", K_LAST_HOST = "last_host", K_FIXED = "device_fixed", K_CRASH = "crash";
    private static final int NOTE_ID = 7;
    private static final long WEATHER_EVERY_MS = 15 * 60 * 1000L;

    // ---- State shown by the app screen -----------------------------------------------------------
    static final int ST_STOPPED = 0, ST_SEARCHING = 1, ST_CONNECTED = 2;
    static volatile int state = ST_STOPPED;
    static volatile String linkedTo, via, problem;
    static volatile long searchingSince, lastWeatherAt, lastNavAt;
    static volatile LinkService running;
    static volatile String carVersion;
    static volatile int carCode;
    static volatile boolean carUpdates;
    static volatile String updateStatus;
    static volatile boolean updating;

    private static final LinkedList<String> LOG = new LinkedList<String>();

    /** Connection log shown (and copyable) in the app. */
    static void log(String s) {
        String line = DateFormat.format("HH:mm:ss", new Date()) + "  " + s;
        synchronized (LOG) {
            if (!LOG.isEmpty() && LOG.getLast().endsWith("  " + s)) return; // no repeats
            LOG.add(line);
            while (LOG.size() > 60) LOG.removeFirst();
        }
        MainActivity.refreshSoon();
    }

    static String logText() {
        StringBuilder b = new StringBuilder();
        synchronized (LOG) {
            for (String l : LOG) b.append(l).append('\n');
        }
        return b.toString();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<JSONObject> updateReplies = new LinkedBlockingQueue<JSONObject>();
    private volatile boolean active;
    private volatile OutputStream out;
    private volatile Closeable conn;
    private volatile long lastRx;
    private volatile String lastNav;
    private SharedPreferences sp;
    private WifiManager.MulticastLock multicast;

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

    static boolean hasBluetoothPermission(Context c) {
        return Build.VERSION.SDK_INT < 31 || granted(c, "android.permission.BLUETOOTH_CONNECT");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        running = this;
        installCrashLog(this);
    }

    /** Records any crash so the next start can show it in the log instead of failing silently. */
    static void installCrashLog(final Context c) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof CrashLog) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashLog(c.getApplicationContext(), prev));
    }

    static final class CrashLog implements Thread.UncaughtExceptionHandler {
        private final Context c;
        private final Thread.UncaughtExceptionHandler prev;

        CrashLog(Context c, Thread.UncaughtExceptionHandler prev) {
            this.c = c;
            this.prev = prev;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try {
                c.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(K_CRASH, DateFormat.format("d MMM HH:mm", new Date()) + " " + e + " (in " + t.getName() + ")").commit();
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            goForeground();
        } catch (Throwable t) {
            log("Android didn't allow the background service: " + t.getClass().getSimpleName() + ". Open Glass Link to start it.");
            problem = "Open Glass Link to start the connection";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!active) {
            active = true;
            state = ST_SEARCHING;
            searchingSince = System.currentTimeMillis();
            String crash = sp.getString(K_CRASH, null);
            if (crash != null) {
                log("Recovered after a crash: " + crash);
                sp.edit().remove(K_CRASH).apply();
            }
            log("Started. Looking for the car over Bluetooth and Wi-Fi…");
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    multicast = wm.createMulticastLock("glass-link");
                    multicast.setReferenceCounted(false);
                    multicast.acquire();
                }
            } catch (Throwable ignored) {}
            thread("link-bt", new Runnable() { @Override public void run() { bluetoothLoop(); } });
            thread("link-discovery", new Runnable() { @Override public void run() { discoveryLoop(); } });
            thread("link-direct", new Runnable() { @Override public void run() { directLoop(); } });
            thread("link-hotspot", new Runnable() { @Override public void run() { hotspotServerLoop(); } });
            thread("link-wifi-watch", new Runnable() { @Override public void run() { wifiWatch(); } });
            main.postDelayed(weatherTimer, WEATHER_EVERY_MS);
            main.postDelayed(pinger, 15000);
        }
        return START_STICKY;
    }

    private void thread(String name, final Runnable r) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable e) {
                    log("Internal error in " + Thread.currentThread().getName() + ": " + e);
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
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
                // Location can't be used from a background start (after a reboot): link only.
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
                .setContentText(linkedTo != null ? "Sharing weather and navigation with the car" : "Looking for the car")
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
        main.removeCallbacks(pinger);
        main.removeCallbacks(navRepeat);
        close(conn);
        try { if (multicast != null) multicast.release(); } catch (Throwable ignored) {}
        state = ST_STOPPED;
        linkedTo = null;
        if (running == this) running = null;
        log("Stopped.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- Bluetooth -----------------------------------------------------------------------------------
    private void bluetoothLoop() {
        int rounds = 0;
        while (active) {
            try {
                if (out != null) { pause(5000); continue; }
                if (!hasBluetoothPermission(this)) {
                    problem = "Allow “Nearby devices” so Glass Link can use Bluetooth";
                    pause(5000);
                    continue;
                }
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null || !ad.isEnabled()) {
                    problem = ad == null ? null : "Bluetooth is off (Wi-Fi still works)";
                    pause(10000);
                    continue;
                }
                problem = null;
                List<BluetoothDevice> devs = candidates(ad);
                if (devs.isEmpty() && rounds == 0) log("Bluetooth: no paired car stereo found. Pair the phone with the car, or use Wi-Fi.");
                for (BluetoothDevice d : devs) {
                    if (!active || out != null) break;
                    tryBluetooth(d, true);
                    if (!active || out != null) break;
                    tryBluetooth(d, false);
                }
                rounds++;
                pause(rounds < 8 ? 15000 : 45000); // retry often at first, then save battery
            } catch (Throwable t) {
                log("Bluetooth: " + t.getClass().getSimpleName());
                pause(15000);
            }
        }
    }

    private void tryBluetooth(BluetoothDevice d, boolean secure) {
        BluetoothSocket s = null;
        String name = name(d);
        try {
            s = secure ? d.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    : d.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID_INSECURE);
            s.connect();
            log("Bluetooth: connected to " + name + (secure ? "" : " (insecure)"));
            sp.edit().putString(K_DEVICE, d.getAddress()).apply();
            serve(s, s.getInputStream(), s.getOutputStream(), name, "Bluetooth");
        } catch (Throwable t) {
            close(s);
            if (secure) log("Bluetooth: " + name + " didn't answer (Glass Launcher not running there, or its Bluetooth is a separate module)");
        }
    }

    /** The chosen head unit, else paired car/audio devices (a car stereo reports as audio). */
    private List<BluetoothDevice> candidates(BluetoothAdapter ad) {
        List<BluetoothDevice> list = new ArrayList<BluetoothDevice>();
        String chosen = sp.getString(K_DEVICE, null);
        try {
            for (BluetoothDevice d : ad.getBondedDevices()) {
                if (d.getAddress().equals(chosen)) list.add(0, d);
            }
            if (sp.getBoolean(K_FIXED, false) && !list.isEmpty()) return list;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                if (d.getAddress().equals(chosen)) continue;
                BluetoothClass bc = d.getBluetoothClass();
                int major = bc != null ? bc.getMajorDeviceClass() : BluetoothClass.Device.Major.UNCATEGORIZED;
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.UNCATEGORIZED
                        || major == BluetoothClass.Device.Major.COMPUTER) {
                    list.add(d);
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null ? n : d.getAddress();
        } catch (Throwable e) {
            return "head unit";
        }
    }

    // ---- Wi-Fi: hear the head unit's announcement, then connect to it --------------------------------
    private final java.util.Map<String, Long> lastTry = new java.util.HashMap<String, Long>();

    private void discoveryLoop() {
        DatagramSocket ds = null;
        boolean logged = false;
        while (active) {
            try {
                if (ds == null) {
                    ds = new DatagramSocket(null);
                    ds.setReuseAddress(true);
                    ds.setBroadcast(true);
                    ds.bind(new InetSocketAddress(BEACON_PORT));
                    ds.setSoTimeout(5000);
                }
                if (out != null) { pause(3000); continue; }
                byte[] buf = new byte[1024];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(p);
                } catch (java.net.SocketTimeoutException timeout) {
                    continue;
                }
                JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                if (o.optInt("glass", 0) != 1 || out != null) continue;
                String host = p.getAddress().getHostAddress();
                Long tried = lastTry.get(host);
                if (tried != null && System.currentTimeMillis() - tried < 5000) continue; // one try per 5 s
                lastTry.put(host, System.currentTimeMillis());
                if (!logged) {
                    log("Wi-Fi: found " + o.optString("name", "the car") + " at " + host);
                    logged = true;
                }
                connectTcp(host, o.optInt("port", CAR_PORT), o.optString("name", null));
            } catch (Throwable t) {
                if (ds != null) ds.close();
                ds = null;
                logged = false;
                pause(5000);
            }
        }
        if (ds != null) ds.close();
    }

    // ---- Phone hotspot: the car dials the phone (the hotspot's gateway) ---------------------------------
    // Only devices connected to THIS phone's hotspot (or USB/Bluetooth tethering) are accepted, never
    // devices on a Wi-Fi network the phone itself has joined.
    private void hotspotServerLoop() {
        java.net.ServerSocket ss = null;
        while (active) {
            Socket s = null;
            try {
                if (ss == null) {
                    ss = new java.net.ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(PHONE_PORT));
                }
                s = ss.accept();
                java.net.InetAddress peer = s.getInetAddress();
                if (out != null || !onPhoneHotspot(peer)) {
                    close(s);
                    continue;
                }
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.setSoTimeout(45000);
                log("Wi-Fi: the car connected over this phone's hotspot (" + peer.getHostAddress() + ")");
                serve(s, s.getInputStream(), s.getOutputStream(), "the car", "this phone's hotspot");
            } catch (Throwable t) {
                close(s);
                if (ss != null) try { ss.close(); } catch (Throwable ignored) {}
                ss = null;
                pause(5000);
            }
        }
        if (ss != null) try { ss.close(); } catch (Throwable ignored) {}
    }

    /** Names of interfaces Android uses as networks for this phone (Wi-Fi client, mobile, VPN). */
    private java.util.Set<String> ownNetworkInterfaces() {
        java.util.Set<String> names = new java.util.HashSet<String>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null && lp.getInterfaceName() != null) names.add(lp.getInterfaceName());
            }
        } catch (Throwable ignored) {}
        return names;
    }

    /** Local IPv4 addresses of this phone's hotspot / tethering interfaces. */
    private List<java.net.InterfaceAddress> hotspotAddresses() {
        List<java.net.InterfaceAddress> out = new ArrayList<java.net.InterfaceAddress>();
        java.util.Set<String> own = ownNetworkInterfaces();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || own.contains(ni.getName())) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof java.net.Inet4Address && ia.getAddress().isSiteLocalAddress()) out.add(ia);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private boolean onPhoneHotspot(java.net.InetAddress peer) {
        if (!(peer instanceof java.net.Inet4Address) || !peer.isSiteLocalAddress()) return false;
        for (java.net.InterfaceAddress ia : hotspotAddresses()) {
            if (sameSubnet(peer, ia.getAddress(), ia.getNetworkPrefixLength())) return true;
        }
        return false;
    }

    static boolean sameSubnet(java.net.InetAddress a, java.net.InetAddress b, int prefix) {
        byte[] x = a.getAddress(), y = b.getAddress();
        if (x.length != y.length) return false;
        prefix = Math.max(8, Math.min(32, prefix));
        for (int i = 0; i < x.length && prefix > 0; i++, prefix -= 8) {
            int mask = prefix >= 8 ? 0xFF : (0xFF << (8 - prefix)) & 0xFF;
            if ((x[i] & mask) != (y[i] & mask)) return false;
        }
        return true;
    }

    /** Explains in the log what Wi-Fi situation the phone is in, whenever it changes. */
    private void wifiWatch() {
        String last = null;
        long since = System.currentTimeMillis();
        boolean hinted = false;
        while (active) {
            try {
                if (out == null) {
                    StringBuilder b = new StringBuilder();
                    for (java.net.InterfaceAddress ia : hotspotAddresses()) {
                        if (b.length() > 0) b.append(", ");
                        b.append(ia.getAddress().getHostAddress());
                    }
                    String now = b.length() > 0 ? "Wi-Fi: this phone's hotspot is on (" + b + "); waiting for the car to join and connect"
                            : "Wi-Fi: this phone's hotspot is off; listening for the car on the current Wi-Fi";
                    if (!now.equals(last)) {
                        log(now);
                        last = now;
                        since = System.currentTimeMillis();
                        hinted = false;
                    } else if (!hinted && System.currentTimeMillis() - since > 60000) {
                        log("Wi-Fi: nothing from the car yet. Check the car is on this hotspot and shows Glass Launcher 1.6.2 or newer "
                                + "(car: Settings › About device › Launcher).");
                        hinted = true;
                    }
                } else {
                    last = null;
                }
                pause(5000);
            } catch (Throwable t) {
                pause(10000);
            }
        }
    }

    /** The address typed in by the user, then the last one that worked. */
    private void directLoop() {
        while (active) {
            try {
                if (out == null) {
                    String manual = sp.getString(K_HOST, null), last = sp.getString(K_LAST_HOST, null);
                    if (manual != null && manual.length() > 0) connectTcp(manual, CAR_PORT, null);
                    if (out == null && last != null && !last.equals(manual)) connectTcp(last, CAR_PORT, null);
                }
                pause(10000);
            } catch (Throwable t) {
                pause(10000);
            }
        }
    }

    private void connectTcp(String host, int port, String name) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);
            s.setSoTimeout(45000); // the car pings every 10 s
            sp.edit().putString(K_LAST_HOST, host).apply();
            log("Wi-Fi: connected to " + host);
            serve(s, s.getInputStream(), s.getOutputStream(), name != null ? name : "the car", "Wi-Fi");
        } catch (Throwable t) {
            close(s);
        }
    }

    // ---- Session ---------------------------------------------------------------------------------------
    private void serve(Closeable c, InputStream in, OutputStream o, String who, String how) throws Exception {
        synchronized (this) {
            if (out != null) { close(c); return; }
            out = o;
            conn = c;
        }
        linkedTo = who;
        via = how;
        state = ST_CONNECTED;
        problem = null;
        lastRx = System.currentTimeMillis();
        updateNote();
        try {
            send(new JSONObject().put("t", "hello").put("name", Build.MODEL).put("app", "Glass Link").put("v", 2).toString());
            String nav = lastNav;
            if (nav != null) send(nav);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while (active && (line = r.readLine()) != null) {
                lastRx = System.currentTimeMillis();
                if (line.length() > 64 * 1024) continue;
                try {
                    handle(new JSONObject(line));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            // connection dropped
        } finally {
            synchronized (this) {
                out = null;
                conn = null;
            }
            close(c);
            log("Disconnected from " + who + ".");
            linkedTo = null;
            via = null;
            carVersion = null;
            carUpdates = false;
            state = active ? ST_SEARCHING : ST_STOPPED;
            searchingSince = System.currentTimeMillis();
            updateNote();
        }
    }

    private void handle(JSONObject m) {
        String type = m.optString("t");
        if ("hello".equals(type)) {
            carVersion = m.optString("version", null);
            carCode = m.optInt("code", 0);
            carUpdates = m.optBoolean("updates", false);
            log("Car app: Glass Launcher " + (carVersion != null ? carVersion : "(old version)"));
            MainActivity.refreshSoon();
        } else if (type.startsWith("apk_")) {
            updateReplies.offer(m);
            if ("apk_error".equals(type) && !updating) {
                updateStatus = "Car: " + m.optString("msg", "update failed");
                MainActivity.refreshSoon();
            }
        } else if ("req".equals(type) && "weather".equals(m.optString("what"))) {
            final String place = m.has("place") ? m.optString("place") : null;
            final double lat = m.optDouble("lat", Double.NaN), lon = m.optDouble("lon", Double.NaN);
            thread("weather", new Runnable() { @Override public void run() { sendWeather(place, lat, lon); } });
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

    /** Keeps the link alive and drops a dead one (car switched off, out of range). */
    private final Runnable pinger = new Runnable() {
        @Override
        public void run() {
            if (out != null) {
                if (System.currentTimeMillis() - lastRx > 45000) {
                    log("The car stopped answering.");
                    close(conn);
                } else {
                    send("{\"t\":\"ping\"}");
                }
            }
            main.postDelayed(this, 15000);
        }
    };

    // ---- Update the car app over the link -------------------------------------------------------
    private static final int CHUNK = 48 * 1024;

    /** Sends {@code apk} to the car in acknowledged chunks. Runs on its own thread. */
    static void sendUpdate(final java.io.File apk) {
        final LinkService s = running;
        if (s == null || s.out == null) {
            updateStatus = "Not connected to the car";
            MainActivity.refreshSoon();
            return;
        }
        if (updating) return;
        updating = true;
        s.thread("send-update", new Runnable() {
            @Override
            public void run() {
                try {
                    s.transfer(apk);
                } catch (Throwable t) {
                    updateStatus = t.getMessage() != null ? t.getMessage() : "Update failed";
                    s.send("{\"t\":\"apk_cancel\"}");
                } finally {
                    updating = false;
                    MainActivity.refreshSoon();
                }
            }
        });
    }

    private void transfer(java.io.File apk) throws Exception {
        long size = apk.length();
        status("Checking the file…");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        java.io.InputStream in = new java.io.FileInputStream(apk);
        byte[] buf = new byte[CHUNK];
        int n;
        try {
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        StringBuilder sha = new StringBuilder();
        for (byte b : md.digest()) sha.append(String.format("%02x", b & 0xFF));
        updateReplies.clear();
        send(new JSONObject().put("t", "apk_begin").put("size", size).put("sha256", sha.toString()).toString());
        expect(-1);
        in = new java.io.FileInputStream(apk);
        long sent = 0;
        int seq = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                String data = android.util.Base64.encodeToString(buf, 0, n, android.util.Base64.NO_WRAP);
                send(new JSONObject().put("t", "apk_chunk").put("seq", seq).put("data", data).toString());
                expect(seq);
                seq++;
                sent += n;
                status("Sending to the car… " + (sent * 100 / size) + "%");
            }
        } finally {
            in.close();
        }
        send("{\"t\":\"apk_end\"}");
        JSONObject r = next(60000);
        if ("apk_done".equals(r.optString("t"))) {
            status("Sent. Tap Install on the car screen to finish.");
            log("Update sent; waiting for Install on the car screen.");
        } else {
            throw new Exception("Car: " + r.optString("msg", "the update was refused"));
        }
    }

    private void status(String s) {
        updateStatus = s;
        MainActivity.refreshSoon();
    }

    /** Waits for the car to confirm chunk {@code seq} (-1 for the start). */
    private void expect(int seq) throws Exception {
        JSONObject r = next(30000);
        String t = r.optString("t");
        if ("apk_error".equals(t)) throw new Exception("Car: " + r.optString("msg", "error"));
        if (!"apk_ack".equals(t) || r.optInt("seq", -1) != seq) throw new Exception("The car didn't answer as expected; try again");
    }

    private JSONObject next(long timeoutMs) throws Exception {
        JSONObject r = updateReplies.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (r == null) {
            if (out == null) throw new Exception("Connection to the car lost");
            throw new Exception("The car stopped answering; try again");
        }
        return r;
    }

    // ---- Navigation ------------------------------------------------------------------------------
    /** Called by NavListener for every change of the maps app's navigation notification. */
    static void nav(String json) {
        LinkService s = running;
        lastNavAt = System.currentTimeMillis();
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

    // ---- Weather ---------------------------------------------------------------------------------
    private final Runnable weatherTimer = new Runnable() {
        @Override
        public void run() {
            if (out != null) {
                thread("weather", new Runnable() {
                    @Override
                    public void run() { sendWeather(null, Double.NaN, Double.NaN); }
                });
            }
            main.postDelayed(this, WEATHER_EVERY_MS);
        }
    };

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
                    log("Weather: no location on the phone yet; the car uses its own internet.");
                    return;
                }
            }
            String raw = null;
            try {
                raw = get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,is_day"
                                + "&hourly=precipitation_probability,visibility&forecast_days=1&timezone=auto", lat, lon));
            } catch (Throwable primary) {
                log("Weather: Open-Meteo didn't answer; the car will try its backup.");
            }
            if (raw != null) {
                send(new JSONObject().put("t", "weather").put("raw", raw).put("place", place != null ? place : "").toString());
                lastWeatherAt = System.currentTimeMillis();
                log("Weather sent" + (place != null && place.length() > 0 ? " for " + place : "") + ".");
            }
            MainActivity.refreshSoon();
        } catch (Throwable t) {
            log("Weather: the phone has no internet right now.");
        }
    }

    /** The phone's position; asks for a fresh fix when the last one is older than 20 minutes. */
    @SuppressWarnings({"MissingPermission", "deprecation"})
    private Location location() {
        if (!granted(this, Manifest.permission.ACCESS_COARSE_LOCATION) && !granted(this, Manifest.permission.ACCESS_FINE_LOCATION)) return null;
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Throwable ignored) {}
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 20 * 60 * 1000L) return best;
        final Location[] fresh = new Location[1];
        final CountDownLatch got = new CountDownLatch(1);
        final LocationListener ll = new LocationListener() {
            @Override public void onLocationChanged(Location l) { fresh[0] = l; got.countDown(); }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        main.post(new Runnable() {
            @Override
            public void run() {
                for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                    try {
                        if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 0, 0, ll, Looper.getMainLooper());
                    } catch (Throwable ignored) {}
                }
            }
        });
        try { got.await(12, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        main.post(new Runnable() {
            @Override
            public void run() { try { lm.removeUpdates(ll); } catch (Throwable ignored) {} }
        });
        return fresh[0] != null ? fresh[0] : best;
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
        c.setRequestProperty("User-Agent", "GlassLink/1.2 (Android phone)");
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
