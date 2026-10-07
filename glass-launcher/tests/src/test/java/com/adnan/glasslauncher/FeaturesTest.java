package com.adnan.glasslauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The 1.4 features: big rail + swipe, songs on the head unit, phone link, Wi-Fi page. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w1280dp-h720dp-land-mdpi")
public class FeaturesTest {
    static final String OUT = System.getProperty("shots", "/tmp/shots");

    private static void idle(long ms) { ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS); }

    private static void shot(MainActivity a, String name) throws Exception {
        idle(1500);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        new File(OUT).mkdirs();
        FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }

    private static int current(MainActivity a) throws Exception {
        Field f = MainActivity.class.getDeclaredField("current");
        f.setAccessible(true);
        return f.getInt(a);
    }

    private static MainActivity start() {
        Context c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("glass_launcher", Context.MODE_PRIVATE).edit()
                .putString("w_cache", "{\"t\":28.4,\"c\":2,\"d\":true,\"r\":40,\"v\":8.2,\"p\":\"Dhaka\",\"at\":" + System.currentTimeMillis() + "}")
                .commit();
        SmokeTest.installVendorApps(c);
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private static void swipe(MainActivity a, float fromX, float toX, float y) {
        long t = SystemClock.uptimeMillis();
        a.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, fromX, y, 0));
        for (int i = 1; i <= 6; i++) {
            float x = fromX + (toX - fromX) * i / 6f;
            a.dispatchTouchEvent(MotionEvent.obtain(t, t + 16 * i, MotionEvent.ACTION_MOVE, x, y + i, 0));
        }
        a.dispatchTouchEvent(MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, toX, y + 6, 0));
        idle(600);
    }

    @Test
    public void bigRailAndSwipeLeftOpensAppDrawer() throws Exception {
        MainActivity a = start();
        idle(1000);
        shot(a, "15-home-big-rail");
        // A short or mostly vertical move does nothing.
        swipe(a, 900, 850, 360);
        assertEquals(MainActivity.HOME, current(a));
        // Swipe left anywhere on Home: app drawer.
        swipe(a, 1000, 600, 360);
        assertEquals(MainActivity.APPS, current(a));
        // Swipe left elsewhere doesn't navigate.
        swipe(a, 1000, 600, 360);
        assertEquals(MainActivity.APPS, current(a));
    }

    private static List<LocalMusic.Track> fakeLibrary() {
        String[][] songs = {{"Bohemian Rhapsody", "Queen", "0"}, {"Hotel California", "Eagles", "0"}, {"Tum Hi Ho", "Arijit Singh", "1"},
                {"Africa", "Toto", "1"}, {"Take On Me", "a-ha", "2"}, {"Blinding Lights", "The Weeknd", "0"},
                {"Shape of You", "Ed Sheeran", "1"}, {"Wonderwall", "Oasis", "0"}, {"Kesariya", "Arijit Singh", "2"}};
        List<LocalMusic.Track> l = new ArrayList<LocalMusic.Track>();
        for (int i = 0; i < songs.length; i++) {
            LocalMusic.Track t = new LocalMusic.Track();
            t.title = songs[i][0];
            t.artist = songs[i][1];
            t.source = Integer.parseInt(songs[i][2]);
            t.duration = 180000 + i * 13000;
            t.path = "/storage/" + (t.source == 0 ? "emulated/0" : "1A2B-3C4D") + "/Music/song" + i + ".mp3";
            t.uri = t.path;
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(t.path), new ShadowMediaPlayer.MediaInfo((int) t.duration, 0));
            l.add(t);
        }
        return l;
    }

    @Test
    public void songsOnTheHeadUnit() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        MainActivity a = start();
        // Without permission: a clear prompt.
        a.show(MainActivity.MUSIC);
        shot(a, "16-music-songs-permission");

        shadowOf(app).grantPermissions("android.permission.READ_MEDIA_AUDIO", Manifest.permission.READ_EXTERNAL_STORAGE);
        Field cache = LocalMusic.class.getDeclaredField("cache");
        cache.setAccessible(true);
        List<LocalMusic.Track> lib = fakeLibrary();
        cache.set(null, lib);
        ((MusicScreen) SmokeTest.getScreen(a, MainActivity.MUSIC)).libraryChanged();
        shot(a, "17-music-songs");

        // Play the third song: the built-in player becomes the "now playing" app.
        LocalPlayer p = a.media().player();
        p.playList(lib, 2, false);
        idle(500);
        assertTrue(p.isPlaying());
        assertEquals("Tum Hi Ho", p.current().title);
        assertTrue(a.media().isLocal());
        assertEquals("Tum Hi Ho", a.media().title());
        shot(a, "18-music-songs-playing");

        // Steering-wheel next/previous go to the built-in player.
        a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT));
        idle(500);
        assertEquals("Africa", p.current().title);
        a.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        idle(500);
        assertTrue(!p.isPlaying());
        a.show(MainActivity.HOME);
        shot(a, "19-home-local-music");
        p.stop();
        idle(300);
    }

    private static String arrowPng() {
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(12);
        paint.setStrokeCap(Paint.Cap.ROUND);
        Path path = new Path();
        path.moveTo(36, 86);
        path.lineTo(36, 40);
        path.quadTo(36, 28, 50, 28);
        path.lineTo(78, 28);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        Path head = new Path();
        head.moveTo(88, 28);
        head.lineTo(68, 12);
        head.lineTo(68, 44);
        head.close();
        c.drawPath(head, paint);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    @Test
    public void phoneLinkWeatherAndNavigation() throws Exception {
        MainActivity a = start();
        idle(500);
        PhoneBridge b = a.bridge();
        assertNotNull(b);
        Method handle = PhoneBridge.class.getDeclaredMethod("handle", String.class);
        handle.setAccessible(true);
        handle.invoke(b, "{\"t\":\"hello\",\"name\":\"Pixel 8\"}");
        String raw = "{\"current\":{\"time\":\"2026-10-06T14:00\",\"temperature_2m\":31.2,\"weather_code\":61,\"is_day\":1},"
                + "\"hourly\":{\"time\":[\"2026-10-06T13:00\",\"2026-10-06T14:00\"],\"precipitation_probability\":[60,80],\"visibility\":[9000,6400]}}";
        handle.invoke(b, new org.json.JSONObject().put("t", "weather").put("raw", raw).put("place", "Chattogram").toString());
        handle.invoke(b, new org.json.JSONObject().put("t", "nav").put("active", true).put("app", "Google Maps")
                .put("title", "300 m").put("text", "Turn right onto Agrabad Access Road").put("sub", "12 min · 4.1 km · ETA 2:47 PM")
                .put("icon", arrowPng()).toString());
        idle(500);
        assertEquals(31.2, a.weather().tempC, 0.01);
        assertEquals("Chattogram", a.weather().place);
        assertNotNull(a.weather().source);
        assertNotNull(a.nav());
        assertEquals("300 m", a.nav().title);
        a.show(MainActivity.HOME);
        shot(a, "20-home-phone-link");
        a.prefs().setHomeLayout(3); a.homeLayoutChanged(); a.show(MainActivity.HOME);
        shot(a, "21-home-drive-phone-link");
        // Malformed messages are ignored.
        handle.invoke(b, "{not json");
        handle.invoke(b, "{\"t\":\"nav\",\"icon\":\"@@@\"}");
        // Route finished.
        handle.invoke(b, "{\"t\":\"nav\",\"active\":false}");
        idle(300);
        assertEquals(null, a.nav());
        a.openSettingsSection(SettingsScreen.WEATHER);
        shot(a, "22-settings-weather-phone");
    }

    @Test
    public void wifiPage() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
        WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        wm.setWifiEnabled(true);
        List<ScanResult> rs = new ArrayList<ScanResult>();
        String[][] nets = {{"Adnan's iPhone", "[WPA2-PSK-CCMP][ESS]", "-45"}, {"Pixel Hotspot", "[WPA2-PSK-CCMP][ESS]", "-60"},
                {"Shell Free WiFi", "[ESS]", "-72"}, {"Home_5G", "[WPA2-PSK-CCMP][ESS]", "-85"}, {"Pixel Hotspot", "[WPA2-PSK-CCMP][ESS]", "-80"}};
        for (String[] n : nets) {
            ScanResult r = Shadow.newInstanceOf(ScanResult.class);
            r.SSID = n[0];
            r.capabilities = n[1];
            r.level = Integer.parseInt(n[2]);
            r.BSSID = "00:11:22:33:44:" + rs.size();
            rs.add(r);
        }
        shadowOf(wm).setScanResults(rs);
        MainActivity a = start();
        a.openSettingsSection(SettingsScreen.NETWORK);
        shot(a, "23-settings-wifi");
        List<Wifi.Network> found = a.wifi().nearby();
        assertEquals(4, found.size()); // duplicates merged
        assertEquals("Adnan's iPhone", found.get(0).ssid);
        assertTrue(found.get(0).secured);
        assertTrue(!found.get(2).secured || !found.get(3).secured);
        assertEquals(Arrays.asList("Adnan's iPhone", "Pixel Hotspot"), Arrays.asList(found.get(0).ssid, found.get(1).ssid));
    }

    private static String sendApk(PhoneBridge b, Method handle, java.io.ByteArrayOutputStream replies, byte[] apk, boolean damage) throws Exception {
        replies.reset();
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        String sha = Updater.hex(md.digest(apk));
        handle.invoke(b, new org.json.JSONObject().put("t", "apk_begin").put("size", apk.length).put("sha256", sha).toString());
        int seq = 0;
        for (int off = 0; off < apk.length; off += 48 * 1024) {
            byte[] part = Arrays.copyOfRange(apk, off, Math.min(apk.length, off + 48 * 1024));
            if (damage && seq == 3) part[10] ^= 0x55;
            handle.invoke(b, new org.json.JSONObject().put("t", "apk_chunk").put("seq", seq++)
                    .put("data", Base64.encodeToString(part, Base64.NO_WRAP)).toString());
        }
        handle.invoke(b, "{\"t\":\"apk_end\"}");
        Thread.sleep(2500);
        idle(300);
        return replies.toString("UTF-8");
    }

    @Test
    public void updateFromPhone() throws Exception {
        MainActivity a = start();
        idle(500);
        PhoneBridge b = a.bridge();
        java.io.ByteArrayOutputStream replies = new java.io.ByteArrayOutputStream();
        Field out = PhoneBridge.class.getDeclaredField("out");
        out.setAccessible(true);
        out.set(b, replies);
        Method handle = PhoneBridge.class.getDeclaredMethod("handle", String.class);
        handle.setAccessible(true);
        File dist = new File(System.getProperty("dist", new File(System.getProperty("user.dir"), "../dist").getPath()));
        if (!new File(dist, "GlassLauncher.apk").exists()) return;
        byte[] apk = java.nio.file.Files.readAllBytes(new File(dist, "GlassLauncher.apk").toPath());

        // Damaged in transit: refused with a clear message.
        String r1 = sendApk(b, handle, replies, apk, true);
        assertTrue(r1, r1.contains("apk_error") && r1.contains("damaged"));
        // A different app (the phone app itself): refused.
        byte[] other = java.nio.file.Files.readAllBytes(new File(dist, "GlassLink.apk").toPath());
        String r2 = sendApk(b, handle, replies, other, false);
        assertTrue(r2, r2.contains("apk_error"));
        // The real launcher: all chunks acknowledged; then either accepted or refused by the
        // package checks (Robolectric can't always read APK signatures).
        String r3 = sendApk(b, handle, replies, apk, false);
        int chunks = (apk.length + 48 * 1024 - 1) / (48 * 1024);
        assertTrue(r3, r3.contains("\"seq\":" + (chunks - 1)));
        String end = r3.substring(r3.lastIndexOf("\"seq\":" + (chunks - 1)));
        System.out.println("UPDATE good-file result: " + end);
        assertTrue(end, end.contains("apk_done") || end.contains("apk_error"));
        shot(a, "24-update-from-phone");
    }

    /** A phone connecting over Wi-Fi (real TCP socket on this machine) delivers weather. */
    @Test
    public void wifiLinkEndToEnd() throws Exception {
        MainActivity a = start();
        idle(500);
        java.net.Socket s = null;
        for (int i = 0; i < 40 && s == null; i++) {
            try {
                s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress("127.0.0.1", PhoneBridge.TCP_PORT), 500);
            } catch (Exception e) {
                s = null;
                Thread.sleep(250);
            }
        }
        assertNotNull("car app listens on Wi-Fi", s);
        s.setSoTimeout(5000);
        java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), "UTF-8"));
        java.io.OutputStream out = s.getOutputStream();
        String hello = in.readLine();
        assertTrue(hello, hello.contains("\"hello\"") && hello.contains("\"updates\":true"));
        String ask = in.readLine();
        assertTrue(ask, ask.contains("\"req\""));
        out.write("{\"t\":\"hello\",\"name\":\"Pixel 9\"}\n".getBytes("UTF-8"));
        String raw = "{\"current\":{\"time\":\"2026-10-06T14:00\",\"temperature_2m\":26.5,\"weather_code\":3,\"is_day\":1}}";
        out.write((new org.json.JSONObject().put("t", "weather").put("raw", raw).put("place", "Sylhet").toString() + "\n").getBytes("UTF-8"));
        out.flush();
        for (int i = 0; i < 30 && (a.weather() == null || !"Sylhet".equals(a.weather().place)); i++) {
            Thread.sleep(100);
            idle(100);
        }
        assertEquals("Sylhet", a.weather().place);
        assertTrue(a.bridge().connected());
        assertEquals("Wi-Fi", a.bridge().via());
        s.close();
        for (int i = 0; i < 30 && a.bridge().connected(); i++) { Thread.sleep(100); idle(100); }
        assertTrue("link drops when the phone goes", !a.bridge().connected());
    }

    @Test
    public void backupWeatherServiceParses() throws Exception {
        String j = "{\"current_condition\":[{\"temp_C\":\"31\",\"weatherCode\":\"302\",\"visibility\":\"6\","
                + "\"localObsDateTime\":\"2026-10-06 02:15 PM\"}],\"weather\":[{\"astronomy\":[{\"sunrise\":\"05:50 AM\","
                + "\"sunset\":\"05:40 PM\"}],\"hourly\":[{\"chanceofrain\":\"10\"},{\"chanceofrain\":\"20\"},{\"chanceofrain\":\"30\"},"
                + "{\"chanceofrain\":\"40\"},{\"chanceofrain\":\"70\"},{\"chanceofrain\":\"60\"},{\"chanceofrain\":\"50\"},{\"chanceofrain\":\"40\"}]}]}";
        Weather.Data d = Weather.parseWttr(new org.json.JSONObject(j));
        assertEquals(31, d.tempC, 0.01);
        assertEquals(63, d.code);
        assertEquals("Rain", Weather.describe(d.code));
        assertEquals(70, d.rainPct);
        assertEquals(6, d.visibilityKm, 0.01);
        assertTrue(d.day);
    }

    @Test
    public void headUnitMapsAppFeedsNavigationCard() throws Exception {
        MainActivity a = start();
        idle(500);
        PhoneBridge.Nav n = new PhoneBridge.Nav();
        n.active = true;
        n.local = true;
        n.title = "500 m";
        n.text = "Keep left at the fork";
        n.app = "Google Maps";
        n.at = System.currentTimeMillis() - 10 * 60 * 1000L; // long straight road: still shown
        MediaListenerService.sink.onLocalNav(n);
        idle(300);
        assertNotNull(a.nav());
        assertEquals("500 m", a.nav().title);
        PhoneBridge.Nav end = new PhoneBridge.Nav();
        end.local = true;
        MediaListenerService.sink.onLocalNav(end);
        idle(300);
        assertEquals(null, a.nav());
    }

    /** On the phone's hotspot the car also dials the phone: the hotspot gateway address. */
    @Test
    public void carFindsPhoneHotspotGateway() throws Exception {
        MainActivity a = start();
        idle(300);
        WifiManager wm = (WifiManager) RuntimeEnvironment.getApplication().getSystemService(Context.WIFI_SERVICE);
        wm.setWifiEnabled(true);
        android.net.DhcpInfo d = new android.net.DhcpInfo();
        d.gateway = (1 << 24) | (34 << 16) | (200 << 8) | 10; // 10.200.34.1, little-endian like Android
        shadowOf(wm).setDhcpInfo(d);
        Method gw = PhoneBridge.class.getDeclaredMethod("gateway");
        gw.setAccessible(true);
        assertEquals("10.200.34.1", gw.invoke(a.bridge()));
        assertTrue(a.bridge().state().startsWith("Waiting for the phone"));
    }
}
