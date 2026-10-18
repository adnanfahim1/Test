package com.adnan.glasslauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 1.8: car-play style phone music (queue, app buttons, where the sound plays), messages, battery, Home on power-on. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w1280dp-h720dp-land-mdpi")
public class CarPlayTest {
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

    private static ActivityController<MainActivity> controller() {
        Context c = RuntimeEnvironment.getApplication();
        c.getSharedPreferences("glass_launcher", Context.MODE_PRIVATE).edit()
                .putString("w_cache", "{\"t\":28.4,\"c\":2,\"d\":true,\"r\":40,\"v\":8.2,\"p\":\"Dhaka\",\"at\":" + System.currentTimeMillis() + "}")
                .commit();
        SmokeTest.installVendorApps(c);
        return Robolectric.buildActivity(MainActivity.class).setup();
    }

    /** First visible TextView whose text matches. */
    static TextView text(View v, String regex) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v instanceof TextView && Pattern.compile(regex).matcher(((TextView) v).getText()).find()) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView f = text(g.getChildAt(i), regex);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static View root(MainActivity a) { return a.getWindow().getDecorView(); }

    /** Pretends Glass Link is connected; returns what the car sends to the phone. */
    private static ByteArrayOutputStream link(MainActivity a) throws Exception {
        PhoneBridge b = a.bridge();
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        set(b, "out", sent);
        set(b, "linked", true);
        set(b, "phone", "Pixel 10");
        set(b, "via", "Wi-Fi");
        Field l = PhoneBridge.class.getDeclaredField("listener");
        l.setAccessible(true);
        ((PhoneBridge.Listener) l.get(b)).onPhoneLink();
        return sent;
    }

    private static void set(Object o, String f, Object v) throws Exception {
        Field fl = o.getClass().getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(o, v);
    }

    private static void phoneSays(MainActivity a, JSONObject o) throws Exception {
        Method handle = PhoneBridge.class.getDeclaredMethod("handle", String.class);
        handle.setAccessible(true);
        handle.invoke(a.bridge(), o.toString());
        idle(300);
    }

    private static String sent(ByteArrayOutputStream s) throws Exception {
        Thread.sleep(300); // the writer thread
        return s.toString("UTF-8");
    }

    private static JSONObject song(boolean playing, String route) throws Exception {
        JSONObject o = new JSONObject().put("t", "media").put("active", true).put("playing", playing).put("app", "Spotify")
                .put("pkg", "com.spotify.music").put("title", "Blinding Lights").put("artist", "The Weeknd")
                .put("dur", 200000).put("pos", 64000).put("route", route).put("qid", 102)
                .put("actions", new JSONArray().put(new JSONObject().put("id", "like").put("name", "Like"))
                        .put(new JSONObject().put("id", "shuffle").put("name", "Shuffle")));
        if ("bt".equals(route)) o.put("routeName", "Amader Gari");
        return o;
    }

    @Test
    public void phoneQueueButtonsAndWhereTheSoundPlays() throws Exception {
        MainActivity a = controller().get();
        idle(500);
        ByteArrayOutputStream out = link(a);
        a.prefs().setResumeOnConnect(false);
        phoneSays(a, song(true, "phone"));
        JSONArray items = new JSONArray();
        for (int i = 1; i <= 3; i++) items.put(new JSONObject().put("id", 100 + i).put("title", "Song " + i).put("sub", "Artist " + i));
        phoneSays(a, new JSONObject().put("t", "media_queue").put("items", items));
        MediaHub m = a.media();
        assertTrue(m.isPhone());
        assertEquals(3, m.queue().size());
        assertEquals(102, m.activeQueueId());
        assertEquals(2, m.customActions().size());

        // Home: the music bar says the sound is on the phone.
        a.show(MainActivity.HOME);
        idle(500);
        assertNotNull(text(root(a), "sound on the phone"));
        shot(a, "40-home-phone-speaker");

        a.show(MainActivity.MUSIC);
        idle(800);
        TextView route = text(root(a), "Sound is on the phone");
        assertNotNull("route shown", route);
        assertNotNull("app's own buttons", text(root(a), "^Like$"));
        shot(a, "41-music-phone-speaker");
        text(root(a), "^Like$").performClick();
        m.skipToQueueItem(103);
        route.performClick();
        idle(300);
        String s = sent(out);
        assertTrue(s, s.contains("\"cmd\":\"custom\"") && s.contains("\"action\":\"like\""));
        assertTrue(s, s.contains("\"cmd\":\"queue\"") && s.contains("\"id\":103"));
        assertTrue(s, s.contains("audio_help"));
        AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("help dialog", d);
        assertTrue(d.isShowing());
        shot(a, "42-car-audio-help");
        d.dismiss();

        // Over the car's Bluetooth: says so, no button.
        phoneSays(a, song(true, "bt"));
        idle(500);
        assertNotNull(text(root(a), "Playing through Amader Gari \\(Bluetooth\\)"));
        assertNull(text(root(a), "Sound is on the phone"));
        shot(a, "43-music-phone-bluetooth");
    }

    @Test
    public void resumesPhoneMusicOnceWhenItsSoundReachesTheCar() throws Exception {
        MainActivity a = controller().get();
        idle(500);
        ByteArrayOutputStream out = link(a);
        phoneSays(a, song(false, "phone")); // not yet on the car's Bluetooth: don't play from the phone speaker
        assertFalse(sent(out).contains("\"cmd\":\"play\""));
        phoneSays(a, song(false, "bt"));
        String s = sent(out);
        assertTrue(s, s.contains("\"cmd\":\"play\""));
        int first = s.indexOf("\"cmd\":\"play\"");
        phoneSays(a, song(false, "bt")); // paused again by the driver: not resumed a second time
        assertEquals(first, sent(out).lastIndexOf("\"cmd\":\"play\""));
    }

    @Test
    public void messageBannerReadsAloudAndRepliesWithoutTyping() throws Exception {
        MainActivity a = controller().get();
        idle(500);
        ByteArrayOutputStream out = link(a);
        phoneSays(a, new JSONObject().put("t", "msg").put("key", "0|com.whatsapp|7").put("app", "WhatsApp").put("from", "Alex")
                .put("text", "Where are you?").put("reply", true).put("read", true));
        assertNotNull(text(root(a), "Message from Alex"));
        assertNotNull(text(root(a), "Where are you\\?"));
        assertNotNull(text(root(a), "^Read aloud$"));
        shot(a, "44-message-banner");
        text(root(a), "^Reply$").performClick();
        idle(300);
        assertNotNull(text(root(a), "^On my way$"));
        shot(a, "45-message-quick-replies");
        text(root(a), "^On my way$").performClick();
        idle(300);
        String s = sent(out);
        assertTrue(s, s.contains("\"t\":\"msg_reply\"") && s.contains("\"text\":\"On my way\"") && s.contains("0|com.whatsapp|7"));
        assertNull("banner closed", text(root(a), "Message from Alex"));

        // Mark read.
        phoneSays(a, new JSONObject().put("t", "msg").put("key", "k2").put("app", "Messages").put("from", "Sam")
                .put("text", "Call me").put("reply", false).put("read", true));
        assertNull("no reply button when the app can't take one", text(root(a), "^Reply$"));
        text(root(a), "^Mark read$").performClick();
        assertTrue(sent(out).contains("\"t\":\"msg_read\""));

        // Sender only, and off.
        a.prefs().setMessageMode(1);
        phoneSays(a, new JSONObject().put("t", "msg").put("key", "k3").put("from", "Kim").put("text", "Secret"));
        assertNotNull(text(root(a), "Message from Kim"));
        assertNull(text(root(a), "Secret"));
        text(root(a), "^Close$").performClick();
        a.prefs().setMessageMode(0);
        phoneSays(a, new JSONObject().put("t", "msg").put("key", "k4").put("from", "Lee").put("text", "Hi"));
        assertNull(text(root(a), "Message from Lee"));
    }

    @Test
    public void phoneBatteryInTheStatusChip() throws Exception {
        MainActivity a = controller().get();
        idle(500);
        link(a);
        phoneSays(a, new JSONObject().put("t", "phone").put("battery", 82).put("charging", true));
        a.show(MainActivity.HOME);
        idle(1200);
        assertNotNull(text(root(a), "Pixel 10 · 82%"));
    }

    @Test
    public void opensOnHomeAfterTheCarIsSwitchedOn() throws Exception {
        ActivityController<MainActivity> c = controller();
        MainActivity a = c.get();
        Application app = RuntimeEnvironment.getApplication();
        idle(500);
        a.show(MainActivity.SETTINGS);
        idle(500);
        c.pause().stop();
        // Car off: screen off for two minutes, then on.
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        idle(100);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        idle(100);
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull("launcher brought to the front", started);
        assertTrue(started.getBooleanExtra(HomeKeeper.EXTRA, false));
        c.start().resume();
        idle(500);
        assertEquals(MainActivity.HOME, currentScreen(a));

        // A short screen-off (display button while driving) doesn't send you home.
        a.show(MainActivity.MUSIC);
        idle(300);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));
        idle(100);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
        app.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));
        idle(100);
        assertNull(shadowOf(app).getNextStartedActivity());

        // Turned off in Settings: nothing happens.
        a.prefs().setAlwaysHome(false);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
        HomeKeeper.carOn(app, "test");
        assertNull(shadowOf(app).getNextStartedActivity());
        a.prefs().setAlwaysHome(true);
    }

    @Test
    public void quickBootPowerOnSignalOpensHome() throws Exception {
        ActivityController<MainActivity> c = controller();
        Application app = RuntimeEnvironment.getApplication();
        idle(500);
        c.pause().stop();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
        shadowOf(app).clearNextStartedActivities();
        Intent boot = new Intent("android.intent.action.QUICKBOOT_POWERON").setClassName(app, HomeKeeper.Boot.class.getName());
        app.sendBroadcast(boot);
        idle(7000); // the receiver waits 6 s for the firmware to settle
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull("launcher opened after the power-on signal", started);
        assertTrue(started.getBooleanExtra(HomeKeeper.EXTRA, false));
    }

    @Test
    public void settingsExplainTheBluetoothLinkAndNewOptions() throws Exception {
        MainActivity a = controller().get();
        idle(500);
        a.openSettingsSection(SettingsScreen.BLUETOOTH);
        idle(800);
        assertNotNull(text(root(a), "Phone link over Bluetooth"));
        assertNotNull(text(root(a), "Messages from your phone"));
        shot(a, "46-settings-bluetooth-link");
        a.openSettingsSection(SettingsScreen.SOUND);
        idle(800);
        assertNotNull(text(root(a), "Resume music when the phone connects"));
        shot(a, "47-settings-phone-music");
        a.openSettingsSection(SettingsScreen.HOME);
        idle(800);
        assertNotNull(text(root(a), "Always open on Home"));
        shot(a, "48-settings-always-home");
    }

    private static int currentScreen(MainActivity a) throws Exception {
        Field f = MainActivity.class.getDeclaredField("current");
        f.setAccessible(true);
        return f.getInt(a);
    }
}
