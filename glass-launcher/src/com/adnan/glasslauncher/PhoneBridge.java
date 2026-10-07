package com.adnan.glasslauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Link to the "Glass Link" companion app on the driver's phone. The phone sends its
 * weather (from its own GPS and internet) and the next turn of Google Maps / Waze.
 *
 * Ways to connect, whichever works first:
 *  - Bluetooth: the head unit listens; the paired phone connects (secure, then insecure RFCOMM).
 *  - Wi-Fi: the head unit announces itself on the local network (UDP beacon) and the phone
 *    connects to it. Covers the phone's hotspot, the head unit's hotspot and shared Wi-Fi.
 *
 * Messages are single lines of JSON in both directions.
 */
final class PhoneBridge {
    static final UUID SERVICE_UUID = UUID.fromString("6b1c2f5e-3c55-4d7e-9a4f-6f1e2a9b7c41");
    /** The car listens here for the phone (Wi-Fi). */
    static final int TCP_PORT = 47823;
    /** Glass Link listens here on its hotspot (also what car app 1.5 dials). Kept apart from
     *  TCP_PORT so the two apps can never fight over one port. */
    static final int PHONE_PORT = 47821;
    private static final int MAX_LINE = 512 * 1024;

    interface Listener {
        void onPhoneWeather(Weather.Data d);
        void onPhoneNav(Nav n);
        void onPhoneLink();
        /** Update from the phone: progress 0-100, or ready to install, or failed. */
        void onUpdateProgress(int percent);
        void onUpdateReady(Updater.Ready r);
        void onUpdateFailed(String message);
    }

    static final class Nav {
        boolean active;
        String title;   // usually the distance, "300 m"
        String text;    // "Turn left onto Main St"
        String sub;     // ETA / remaining time
        String app;     // "Google Maps"
        Bitmap icon;    // maneuver arrow from the maps app
        long at;
        boolean local;  // from a maps app on the head unit itself (not the phone)
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Listener listener;
    private volatile boolean running;
    private volatile boolean linked;
    private volatile String phone;
    private volatile OutputStream out;
    private volatile Closeable conn;
    private volatile BluetoothServerSocket server;
    private volatile String via;
    private final Updater updater;
    private int lastPct = -1;

    PhoneBridge(Context c) {
        ctx = c.getApplicationContext();
        updater = new Updater(ctx);
    }

    void setListener(Listener l) { listener = l; }

    boolean connected() { return linked; }

    /** Phone name while connected. */
    String phoneName() { return linked ? phone : null; }

    /** "Bluetooth" or "Wi-Fi" while connected. */
    String via() { return linked ? via : null; }

    private static PhoneBridge current;

    void start() {
        if (running) return;
        // Only one listener per process (an older screen instance gives way to the new one).
        synchronized (PhoneBridge.class) {
            if (current != null && current != this) current.stop();
            current = this;
        }
        running = true;
        thread("phone-link-bt", new Runnable() { @Override public void run() { bluetoothLoop(true); } });
        thread("phone-link-bt2", new Runnable() { @Override public void run() { bluetoothLoop(false); } });
        thread("phone-link-lan", new Runnable() { @Override public void run() { lanServerLoop(); } });
        thread("phone-link-beacon", new Runnable() { @Override public void run() { beaconLoop(); } });
        thread("phone-link-gateway", new Runnable() { @Override public void run() { gatewayLoop(); } });
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        close(server);
        close(server2);
        close(lanServer);
        close(conn);
    }

    /** What the link is doing, for Settings › Weather › Phone link. */
    String state() {
        if (linked) return "Connected over " + via;
        StringBuilder b = new StringBuilder("Waiting for the phone");
        String ips = lanAddresses();
        if (ips.length() > 0) b.append(" · Wi-Fi address ").append(ips);
        if (btState != null) b.append(" · ").append(btState);
        return b.toString();
    }

    private volatile String weatherAsk = "{\"t\":\"req\",\"what\":\"weather\"}";

    /**
     * Asks the phone for fresh weather: for the phone's own location, or for a city chosen
     * in Settings › Weather when {@code place} is given.
     */
    void requestWeather(String place, double lat, double lon) {
        try {
            JSONObject o = new JSONObject().put("t", "req").put("what", "weather");
            if (place != null) o.put("place", place).put("lat", lat).put("lon", lon);
            weatherAsk = o.toString();
        } catch (Throwable ignored) {}
        send(weatherAsk);
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

    // ---- Bluetooth: the phone connects to us (secure and, for odd stacks, insecure RFCOMM) ----
    static final UUID SERVICE_UUID_INSECURE = UUID.fromString("6b1c2f5e-3c55-4d7e-9a4f-6f1e2a9b7c42");
    private volatile BluetoothServerSocket server2;
    private volatile String btState;

    private void bluetoothLoop(boolean secure) {
        while (running) {
            BluetoothSocket s = null;
            BluetoothServerSocket ss = null;
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (ad == null) { btState = "this head unit's Android has no Bluetooth (use Wi-Fi)"; pause(60000); continue; }
                if (!NewApi.granted(ctx, PhoneLink.PERM_CONNECT)) { btState = "allow “Nearby devices” for Bluetooth"; pause(8000); continue; }
                if (!ad.isEnabled()) { btState = "Android Bluetooth is off"; pause(10000); continue; }
                if (linked) { pause(5000); continue; }
                btState = "Bluetooth ready";
                ss = secure ? ad.listenUsingRfcommWithServiceRecord("Glass Link", SERVICE_UUID)
                        : ad.listenUsingInsecureRfcommWithServiceRecord("Glass Link (insecure)", SERVICE_UUID_INSECURE);
                if (secure) server = ss; else server2 = ss;
                s = ss.accept();
                close(ss);
                if (linked) { close(s); continue; }
                String name = null;
                try { name = s.getRemoteDevice().getName(); } catch (Throwable ignored) {}
                serve(s, s.getInputStream(), s.getOutputStream(), name, "Bluetooth");
            } catch (Throwable t) {
                close(ss);
                close(s);
                pause(6000);
            }
        }
    }

    // ---- Wi-Fi: the phone finds us by a broadcast "beacon" and connects to our address ------------
    // Works when the head unit is on the phone's hotspot, the phone is on the head unit's hotspot,
    // or both are on the same Wi-Fi. Only devices on the local network can connect.
    static final int BEACON_PORT = 47822;
    private volatile java.net.ServerSocket lanServer;
    private final String id = java.util.UUID.randomUUID().toString().substring(0, 8);

    private void lanServerLoop() {
        while (running) {
            Socket s = null;
            try {
                if (lanServer == null || lanServer.isClosed()) {
                    java.net.ServerSocket ss = new java.net.ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(TCP_PORT));
                    lanServer = ss;
                }
                s = lanServer.accept();
                java.net.InetAddress from = s.getInetAddress();
                if (linked || !(from.isSiteLocalAddress() || from.isLoopbackAddress() || from.isLinkLocalAddress())) {
                    close(s);
                    continue;
                }
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.setSoTimeout(45000); // the phone pings every 15 s; silence means it's gone
                serve(s, s.getInputStream(), s.getOutputStream(), null, "Wi-Fi");
            } catch (Throwable t) {
                close(s);
                if (!running) break;
                close(lanServer);
                lanServer = null;
                pause(5000);
            }
        }
    }

    private void beaconLoop() {
        java.net.DatagramSocket ds = null;
        while (running) {
            try {
                if (ds == null) {
                    ds = new java.net.DatagramSocket();
                    ds.setBroadcast(true);
                }
                if (linked) send("{\"t\":\"ping\"}"); // keeps the link alive and detects a dead one
                else {
                    String name = android.os.Build.MODEL;
                    byte[] msg = new JSONObject().put("glass", 1).put("name", name).put("port", TCP_PORT).put("id", id)
                            .toString().getBytes("UTF-8");
                    for (java.net.InetAddress b : broadcastAddresses()) {
                        try { ds.send(new java.net.DatagramPacket(msg, msg.length, b, BEACON_PORT)); } catch (Throwable ignored) {}
                    }
                }
                pause(linked ? 10000 : 2500);
            } catch (Throwable t) {
                if (ds != null) ds.close();
                ds = null;
                pause(5000);
            }
        }
        if (ds != null) ds.close();
    }

    private static java.util.List<java.net.InetAddress> broadcastAddresses() {
        java.util.List<java.net.InetAddress> out = new java.util.ArrayList<java.net.InetAddress>();
        try {
            out.add(java.net.InetAddress.getByName("255.255.255.255"));
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // ---- Phone hotspot: also dial the phone (the hotspot's gateway), which Glass Link listens on.
    // Works even where the hotspot drops broadcast announcements.
    private void gatewayLoop() {
        while (running) {
            Socket s = null;
            try {
                String gw = linked ? null : gateway();
                if (gw == null) { pause(6000); continue; }
                s = new Socket();
                s.connect(new InetSocketAddress(gw, PHONE_PORT), 2500);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.setSoTimeout(45000);
                serve(s, s.getInputStream(), s.getOutputStream(), null, "Wi-Fi hotspot");
            } catch (Throwable t) {
                close(s);
                pause(8000);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private String gateway() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.DhcpInfo d = wm != null && wm.isWifiEnabled() ? wm.getDhcpInfo() : null;
            if (d == null || d.gateway == 0) return null;
            int g = d.gateway;
            return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
        } catch (Throwable t) {
            return null;
        }
    }

    /** This head unit's local IPv4 addresses, e.g. "192.168.43.120" (to type into Glass Link). */
    static String lanAddresses() {
        StringBuilder b = new StringBuilder();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        if (b.length() > 0) b.append(", ");
                        b.append(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return b.toString();
    }

    // ---- Session -------------------------------------------------------------------------------
    private void serve(Closeable c, InputStream in, OutputStream o, String name, String how) throws Exception {
        synchronized (this) {
            if (linked) { close(c); return; }
            linked = true;
            conn = c;
            out = o;
            phone = name;
            via = how;
        }
        post(new Runnable() { @Override public void run() { if (listener != null) listener.onPhoneLink(); } });
        send(hello());
        send(weatherAsk);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder line = new StringBuilder();
            int ch;
            while (running && (ch = r.read()) != -1) {
                if (ch == '\n') {
                    handle(line.toString());
                    line.setLength(0);
                } else if (line.length() < MAX_LINE) {
                    line.append((char) ch);
                }
            }
        } finally {
            synchronized (this) {
                linked = false;
                out = null;
                conn = null;
            }
            if (updater.receiving()) {
                updater.abort();
                post(new Runnable() { @Override public void run() { if (listener != null) listener.onUpdateFailed("Connection to the phone lost during the update"); } });
            }
            close(c);
            final Nav ended = new Nav();
            post(new Runnable() {
                @Override
                public void run() {
                    if (listener == null) return;
                    listener.onPhoneNav(ended);
                    listener.onPhoneLink();
                }
            });
        }
    }

    private String hello() {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return new JSONObject().put("t", "hello").put("app", "Glass Launcher").put("v", 2)
                    .put("version", pi.versionName).put("code", pi.versionCode)
                    .put("android", android.os.Build.VERSION.SDK_INT).put("updates", true).toString();
        } catch (Throwable t) {
            return "{\"t\":\"hello\",\"app\":\"Glass Launcher\",\"v\":2}";
        }
    }

    // ---- Update from the phone (runs on the reader thread; file work stays off the UI) ------------
    private void updateMessage(String t, final JSONObject o) {
        String err = null;
        if ("apk_begin".equals(t)) {
            lastPct = -1;
            err = updater.begin(o.optLong("size", -1), o.optString("sha256", null));
            if (err == null) {
                reply("apk_ack", -1, null);
                progress(0);
            }
        } else if ("apk_chunk".equals(t)) {
            int seq = o.optInt("seq", -1);
            err = updater.chunk(seq, o.optString("data", ""));
            if (err == null) {
                reply("apk_ack", seq, null);
                progress(updater.percent());
            }
        } else if ("apk_end".equals(t)) {
            try {
                final Updater.Ready r = updater.finish();
                reply("apk_done", -1, "Confirm the update on the car screen");
                post(new Runnable() { @Override public void run() { if (listener != null) listener.onUpdateReady(r); } });
            } catch (Exception e) {
                err = e.getMessage();
                updater.abort();
            }
        } else if ("apk_cancel".equals(t)) {
            updater.abort();
            err = "Update cancelled on the phone";
        }
        if (err != null) {
            final String msg = err;
            reply("apk_error", -1, msg);
            post(new Runnable() { @Override public void run() { if (listener != null) listener.onUpdateFailed(msg); } });
        }
    }

    private void reply(String type, int seq, String msg) {
        try {
            JSONObject r = new JSONObject().put("t", type);
            if (seq >= 0) r.put("seq", seq);
            if (msg != null) r.put("msg", msg);
            send(r.toString());
        } catch (Throwable ignored) {}
    }

    private void progress(final int pct) {
        if (pct == lastPct) return;
        lastPct = pct;
        post(new Runnable() { @Override public void run() { if (listener != null) listener.onUpdateProgress(pct); } });
    }

    private void handle(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String t = o.optString("t");
            if (t.startsWith("apk_")) {
                updateMessage(t, o);
                return;
            }
            if ("hello".equals(t)) {
                String n = o.optString("name", null);
                if (n != null && n.length() > 0) phone = n;
                post(new Runnable() { @Override public void run() { if (listener != null) listener.onPhoneLink(); } });
            } else if ("weather".equals(t)) {
                String raw = o.optString("raw", null);
                final Weather.Data d = raw != null && raw.length() < MAX_LINE ? Weather.fromOpenMeteo(raw, str(o, "place"))
                        : Weather.Data.fromJson(o.optString("data", null));
                if (d == null) return;
                d.source = phone != null ? phone : "Phone";
                if (d.fetchedAt <= 0) d.fetchedAt = System.currentTimeMillis();
                post(new Runnable() { @Override public void run() { if (listener != null) listener.onPhoneWeather(d); } });
            } else if ("nav".equals(t)) {
                final Nav n = new Nav();
                n.active = o.optBoolean("active", false);
                n.title = str(o, "title");
                n.text = str(o, "text");
                n.sub = str(o, "sub");
                n.app = str(o, "app");
                n.at = System.currentTimeMillis();
                String icon = str(o, "icon");
                if (icon != null && icon.length() < 200000) {
                    byte[] b = Base64.decode(icon, Base64.DEFAULT);
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(b, 0, b.length, opt);
                    if (opt.outWidth > 0 && opt.outWidth <= 512 && opt.outHeight <= 512) {
                        n.icon = BitmapFactory.decodeByteArray(b, 0, b.length);
                    }
                }
                post(new Runnable() { @Override public void run() { if (listener != null) listener.onPhoneNav(n); } });
            }
        } catch (Throwable ignored) {
            // A malformed message is skipped; the link stays up.
        }
    }

    private static String str(JSONObject o, String k) {
        String s = o.optString(k, null);
        if (s == null || "null".equals(s)) return null;
        s = s.trim();
        return s.length() == 0 ? null : s;
    }

    private void post(Runnable r) { main.post(r); }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static void close(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}
