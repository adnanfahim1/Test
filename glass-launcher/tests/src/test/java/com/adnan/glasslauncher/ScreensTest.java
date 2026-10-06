package com.adnan.glasslauncher;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w1280dp-h720dp-land-mdpi")
public class ScreensTest {
    static final String OUT = System.getProperty("shots", "/tmp/shots");

    private void addApp(ShadowPackageManager spm, String pkg, String label, String category) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = pkg;
        ai.name = label;
        ai.nonLocalizedLabel = label;
        ActivityInfo act = new ActivityInfo();
        act.packageName = pkg;
        act.name = pkg + ".Main";
        act.nonLocalizedLabel = label;
        act.applicationInfo = ai;
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = act;
        ri.nonLocalizedLabel = label;
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        spm.addResolveInfoForIntent(main, ri);
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = ai;
        spm.installPackage(pi);
    }

    private void shot(MainActivity a, String name) throws Exception {
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        new File(OUT).mkdirs();
        FileOutputStream out = new FileOutputStream(new File(OUT, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
        System.out.println("shot " + name + " " + root.getWidth() + "x" + root.getHeight());
    }

    private MainActivity start(boolean weather) {
        Context c = RuntimeEnvironment.getApplication();
        if (weather) {
            c.getSharedPreferences("glass_launcher", Context.MODE_PRIVATE).edit()
                    .putString("w_cache", "{\"t\":28.4,\"c\":2,\"d\":true,\"r\":40,\"v\":8.2,\"p\":\"Dhaka\",\"at\":" + System.currentTimeMillis() + "}")
                    .commit();
        }
        ShadowPackageManager spm = shadowOf(c.getPackageManager());
        String[][] apps = {{"com.google.android.apps.maps", "Maps"}, {"com.spotify.music", "Spotify"},
                {"com.google.android.youtube", "YouTube"}, {"com.android.settings", "Settings"},
                {"com.android.chrome", "Chrome"}, {"com.zjinnova.zlink", "ZLink"}, {"com.ts.car.setting", "Car Setting"},
                {"com.android.camera2", "Camera"}, {"com.waze", "Waze"}, {"com.android.music", "Music"},
                {"com.android.bluetooth.phone", "Bluetooth Phone"}, {"com.android.calculator2", "Calculator"},
                {"com.android.deskclock", "Clock"}, {"com.android.documentsui", "Files"}, {"com.android.vending", "Play Store"},
                {"com.ts.radio", "Radio"}, {"com.android.gallery3d", "Gallery"}, {"com.ts.eq", "Equalizer"},
                {"com.whatsapp", "WhatsApp"}, {"com.ts.avin", "AV In"}, {"org.videolan.vlc", "VLC"}};
        for (String[] a : apps) addApp(spm, a[0], a[1], null);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        return ctl.get();
    }

    @Test
    public void screens() throws Exception {
        MainActivity a = start(true);
        shot(a, "01-home-dashboard");
        a.prefs().setHomeLayout(1); a.homeLayoutChanged(); a.show(MainActivity.HOME);
        shot(a, "02-home-showcase");
        a.prefs().setHomeLayout(2); a.homeLayoutChanged(); a.show(MainActivity.HOME);
        shot(a, "03-home-minimal");
        a.prefs().setHomeLayout(3); a.homeLayoutChanged(); a.show(MainActivity.HOME);
        shot(a, "04-home-drive");
        a.show(MainActivity.MUSIC);
        shot(a, "05-music");
        a.show(MainActivity.APPS);
        shot(a, "06-apps");
        a.show(MainActivity.SETTINGS);
        shot(a, "07-settings-home");
        for (int i = 1; i < 8; i++) {
            a.openSettingsSection(i);
            shot(a, "08-settings-" + i);
        }
        a.show(MainActivity.CAR);
        shot(a, "09-car");
        a.applyTheme(2);
        a.prefs().setHomeLayout(0); a.homeLayoutChanged(); a.show(MainActivity.HOME);
        shot(a, "10-home-emerald");
        a.toast("Home screen set to Dashboard");
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        FileOutputStream out = new FileOutputStream(new File(OUT, "11-toast.png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }

    @Test
    public void playing() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        android.provider.Settings.Secure.putString(c.getContentResolver(), "enabled_notification_listeners",
                new android.content.ComponentName(c, MediaListenerService.class).flattenToString());
        android.media.session.MediaSession s = new android.media.session.MediaSession(c, "test");
        android.graphics.Bitmap art = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
        art.eraseColor(0xFFE0784A);
        s.setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Blinding Lights")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "The Weeknd")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 200000)
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
        s.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 64000, 1f).setActiveQueueItemId(2).build());
        java.util.List<android.media.session.MediaSession.QueueItem> q = new java.util.ArrayList<android.media.session.MediaSession.QueueItem>();
        String[][] t = {{"Save Your Tears", "The Weeknd"}, {"Levitating", "Dua Lipa"}, {"Blinding Lights", "The Weeknd"}, {"Shape of You", "Ed Sheeran"}};
        for (int i = 0; i < t.length; i++) q.add(new android.media.session.MediaSession.QueueItem(new android.media.MediaDescription.Builder().setMediaId("m" + i).setTitle(t[i][0]).setSubtitle(t[i][1]).build(), i));
        s.setQueue(q);
        s.setActive(true);
        android.media.session.MediaSessionManager msm = (android.media.session.MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        android.media.session.MediaController mc = s.getController();
        shadowOf(mc).setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Blinding Lights")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "The Weeknd")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 200000)
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
        shadowOf(mc).setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 64000, 1f).setActiveQueueItemId(2).build());
        shadowOf(mc).setPackageName("com.spotify.music");
        shadowOf(msm).addController(mc);
        MainActivity a = start(true);
        System.out.println("ART=" + a.media().art() + " meta=" + mc.getMetadata().getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART));
        shot(a, "13-home-playing");
        a.show(MainActivity.MUSIC);
        shot(a, "14-music-playing");
    }

    @Test
    public void noWeatherCache() throws Exception {
        MainActivity a = start(false);
        shot(a, "12-home-dashboard-noweather");
    }
}
