package com.adnan.glasslauncher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

/** Renders key screens at the screen sizes/densities Nakamichi units ship with. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class ResolutionTest {
    static final String OUT = System.getProperty("shots", "/tmp/shots");

    private void run(String qualifiers, String tag, boolean all) throws Exception {
        RuntimeEnvironment.setQualifiers(qualifiers);
        SmokeTest.installVendorApps(RuntimeEnvironment.getApplication());
        RuntimeEnvironment.getApplication().getSharedPreferences(Prefs.FILE, 0).edit()
                .putString("w_cache", "{\"t\":31.2,\"c\":1,\"d\":true,\"r\":20,\"v\":10,\"p\":\"Dhaka\",\"at\":" + System.currentTimeMillis() + "}")
                .commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shot(a, tag + "-dashboard");
        if (all) {
            a.show(MainActivity.APPS);
            shot(a, tag + "-apps");
            a.show(MainActivity.CAR);
            shot(a, tag + "-car");
            a.openSettingsSection(SettingsScreen.CONNECTIONS);
            shot(a, tag + "-connections");
            a.show(MainActivity.MUSIC);
            shot(a, tag + "-music");
        }
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

    @Test public void s800x480() throws Exception { run("w800dp-h480dp-land-mdpi", "800x480", true); }
    @Test public void s1024x600() throws Exception { run("w1024dp-h600dp-land-mdpi", "1024x600", true); }
    @Test public void s1280x720() throws Exception { run("w1280dp-h720dp-land-mdpi", "1280x720", true); }
    @Test public void s1280x720hdpi() throws Exception { run("w853dp-h480dp-land-hdpi", "1280x720-hdpi", false); }
    @Test public void s1360x800() throws Exception { run("w1360dp-h800dp-land-mdpi", "1360x800", false); }
    @Test public void s1920x720() throws Exception { run("w1920dp-h720dp-land-mdpi", "1920x720", false); }
}
