package com.adnan.glasslauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
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
 * Two ways to connect, whichever works first:
 *  - Bluetooth: the head unit listens; the paired phone connects (works with no internet).
 *  - Phone hotspot: when the head unit is on the phone's hotspot, it connects to the phone.
 *
 * Messages are single lines of JSON in both directions.
 */
final class PhoneBridge {
    static final UUID SERVICE_UUID = UUID.fromString("6b1c2f5e-3c55-4d7e-9a4f-6f1e2a9b7c41");
    static final int TCP_PORT = 47821;
    private static final int MAX_LINE = 512 * 1024;

    interface Listener {
        void onPhoneWeather(Weather.Data d);
        void onPhoneNav(Nav n);
        void onPhoneLink();
    }

    static final class Nav {
        boolean active;
        String title;   // usually the distance, "300 m"
        String text;    // "Turn left onto Main St"
        String sub;     // ETA / remaining time
        String app;     // "Google Maps"
        Bitmap icon;    // maneuver arrow from the maps app
        long at;
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

    PhoneBridge(Context c) { ctx = c.getApplicationContext(); }

    void setListener(Listener l) { listener = l; }

    boolean connected() { return linked; }

    /** Phone name while connected. */
    String phoneName() { return linked ? phone : null; }

    /** "Bluetooth" or "Hotspot" while connected. */
    String via() { return linked ? via : null; }

    void start() {
        if (running) return;
        running = true;
        Thread bt = new Thread(new Runnable() {
            @Override
            public void run() { bluetoothLoop(); }
        }, "phone-link-bt");
        bt.setDaemon(true);
        bt.start();
        Thread tcp = new Thread(new Runnable() {
            @Override
            public void run() { hotspotLoop(); }
        }, "phone-link-wifi");
        tcp.setDaemon(true);
        tcp.start();
    }

    void stop() {
        running = false;
        close(server);
        close(conn);
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

    // ---- Bluetooth: the phone connects to us -------------------------------------------------
    private void bluetoothLoop() {
        while (running) {
            BluetoothSocket s = null;
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                if (linked || ad == null || !ad.isEnabled() || !NewApi.granted(ctx, PhoneLink.PERM_CONNECT)) {
                    pause(10000);
                    continue;
                }
                server = ad.listenUsingRfcommWithServiceRecord("Glass Link", SERVICE_UUID);
                s = server.accept();
                close(server);
                server = null;
                if (linked) { close(s); continue; }
                String name = null;
                try { name = s.getRemoteDevice().getName(); } catch (Throwable ignored) {}
                serve(s, s.getInputStream(), s.getOutputStream(), name, "Bluetooth");
            } catch (Throwable t) {
                close(server);
                server = null;
                close(s);
                pause(8000);
            }
        }
    }

    // ---- Phone hotspot: we connect to the phone (the hotspot's gateway) -----------------------
    private void hotspotLoop() {
        while (running) {
            Socket s = null;
            try {
                String gw = linked ? null : gateway();
                if (gw == null) {
                    pause(15000);
                    continue;
                }
                s = new Socket();
                s.connect(new InetSocketAddress(gw, TCP_PORT), 2500);
                s.setKeepAlive(true);
                serve(s, s.getInputStream(), s.getOutputStream(), null, "Hotspot");
            } catch (Throwable t) {
                close(s);
                pause(20000);
            }
        }
    }

    private String gateway() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm != null && wm.isWifiEnabled() ? wm.getDhcpInfo() : null;
            if (d == null || d.gateway == 0) return null;
            int g = d.gateway;
            return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
        } catch (Throwable t) {
            return null;
        }
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
        send("{\"t\":\"hello\",\"app\":\"Glass Launcher\",\"v\":1}");
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

    private void handle(String line) {
        try {
            JSONObject o = new JSONObject(line);
            String t = o.optString("t");
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
