package com.adnan.glasslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The four home layouts: Dashboard, Car showcase, Minimal, Drive focus. */
final class HomeScreen extends Screen {
    private final int layout;
    private Parts.Header header;
    private Parts.MusicBar musicBar;
    private Parts.WeatherCard weather;
    private Parts.MapCard map;
    private Widgets.CarSpin spin;
    private TextView bigClock, bigAmPm, bigDate, bigGreeting;
    private static Bitmap carStill;

    HomeScreen(MainActivity a, int layout) {
        super(a);
        this.layout = layout;
    }

    int layout() { return layout; }

    @Override
    View build() {
        Context c = a;
        LinearLayout root = Ui.row(c);
        root.setGravity(Gravity.NO_GRAVITY);
        root.setPadding(Ui.u(24), Ui.u(24), Ui.u(24), Ui.u(24));

        LinearLayout content = Ui.col(c);
        header = new Parts.Header(a, layout == 2);
        content.addView(header, Ui.lp(Ui.MATCH, Ui.u(36)));

        View body;
        switch (layout) {
            case 1: body = showcase(c); break;
            case 2: body = minimal(c); break;
            case 3: body = drive(c); break;
            default: body = dashboard(c); break;
        }
        content.addView(body, Ui.margins(Ui.lpw(Ui.MATCH, 0, 1), 0, 16, 0, 0));
        root.addView(content, Ui.lpw(0, Ui.MATCH, 1));

        LinearLayout rail = Parts.rail(a);
        root.addView(rail, Ui.margins(Ui.lp(Ui.u(88), Ui.MATCH), 20, 0, 0, 0));
        Ui.rise(rail, 0);
        return root;
    }

    private Bitmap carStill() {
        if (carStill == null) carStill = Widgets.asset(a, "car/noah-still.webp", null);
        return carStill;
    }

    // ---- Dashboard: car still, weather, map, music bar ------------------------------------
    private View dashboard(Context c) {
        LinearLayout col = Ui.col(c);
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.NO_GRAVITY);

        LinearLayout car = Ui.col(c);
        car.setBackground(Ui.glass(28));
        car.setPadding(Ui.u(26), Ui.u(26), Ui.u(26), Ui.u(22));
        car.addView(Ui.text(c, a.prefs().carName(), 34, Ui.TEXT, Ui.display(600)));
        Widgets.Picture pic = new Widgets.Picture(c, carStill());
        pic.setContentDescription("Grey Toyota Noah");
        car.addView(pic, Ui.margins(Ui.lpw(Ui.MATCH, 0, 1), -14, 0, -14, 0));
        top.addView(car, Ui.lp(Ui.u(440), Ui.MATCH));

        weather = new Parts.WeatherCard(a);
        top.addView(weather, Ui.margins(Ui.lpw(0, Ui.MATCH, 1), 20, 0, 0, 0));

        map = new Parts.MapCard(a, false);
        top.addView(map, Ui.margins(Ui.lp(Ui.u(300), Ui.MATCH), 20, 0, 0, 0));

        col.addView(top, Ui.lpw(Ui.MATCH, 0, 1));
        musicBar = new Parts.MusicBar(a);
        col.addView(musicBar, Ui.margins(Ui.lp(Ui.MATCH, Ui.u(132)), 0, 20, 0, 0));
        Ui.rise(car, 80);
        Ui.rise(map, 160);
        Ui.rise(weather, 280);
        Ui.rise(musicBar, 340);
        return col;
    }

    // ---- Drive focus: weather + big map + music bar ---------------------------------------
    private View drive(Context c) {
        LinearLayout col = Ui.col(c);
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.NO_GRAVITY);
        weather = new Parts.WeatherCard(a);
        top.addView(weather, Ui.lp(Ui.u(330), Ui.MATCH));
        map = new Parts.MapCard(a, true);
        top.addView(map, Ui.margins(Ui.lpw(0, Ui.MATCH, 1), 20, 0, 0, 0));
        col.addView(top, Ui.lpw(Ui.MATCH, 0, 1));
        musicBar = new Parts.MusicBar(a);
        col.addView(musicBar, Ui.margins(Ui.lp(Ui.MATCH, Ui.u(132)), 0, 20, 0, 0));
        Ui.rise(map, 160);
        Ui.rise(weather, 280);
        Ui.rise(musicBar, 340);
        return col;
    }

    // ---- Car showcase: 360° spin ----------------------------------------------------------
    private View showcase(Context c) {
        FrameLayout panel = new FrameLayout(c);
        panel.setBackground(Ui.glass(32));
        panel.addView(new GlowEllipse(c), Ui.flp(Ui.u(820), Ui.u(160), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        ((FrameLayout.LayoutParams) panel.getChildAt(0).getLayoutParams()).bottomMargin = Ui.u(70);

        LinearLayout col = Ui.col(c);
        col.setGravity(Gravity.CENTER);
        TextView kicker = Ui.text(c, "MY CAR", 14, Ui.MUTED, Ui.body(500));
        kicker.setLetterSpacing(0.28f);
        col.addView(kicker, Ui.lp(Ui.WRAP, Ui.WRAP));
        col.addView(Ui.text(c, a.prefs().carName(), 52, Ui.TEXT, Ui.display(600)), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 0));

        FrameLayout stage = new FrameLayout(c);
        spin = new Widgets.CarSpin(c);
        spin.setContentDescription("Grey Toyota Noah rotating 360 degrees");
        stage.addView(spin, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        final LinearLayout chip = Ui.row(c);
        chip.setPadding(Ui.u(12), Ui.u(7), Ui.u(12), Ui.u(7));
        chip.setBackground(Ui.fill(0x990C0C10, Ui.white(0.16f), 999));
        final Icons.GlyphView chipGlyph = new Icons.GlyphView(c, Icons.PAUSE, Ui.TEXT_E6, 14);
        chip.addView(chipGlyph, Ui.lp(Ui.u(14), Ui.u(14)));
        final TextView chipText = Ui.text(c, "Tap to pause", 13, Ui.TEXT_E6, Ui.body(400));
        chip.addView(chipText, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 6, 0, 0, 0));
        FrameLayout.LayoutParams cp = Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.RIGHT);
        cp.setMargins(0, 0, Ui.u(8), Ui.u(6));
        stage.addView(chip, cp);
        stage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean play = !spin.isPlaying();
                spin.setPlaying(play);
                chipGlyph.set(spin.isPlaying() ? Icons.PAUSE : Icons.PLAY);
                chipText.setText(spin.isPlaying() ? "Tap to pause" : "Tap to spin");
            }
        });
        if (Ui.reduceMotion) {
            chipGlyph.set(Icons.PLAY);
            chipText.setText("Tap to spin");
        }
        col.addView(stage, Ui.margins(Ui.lp(Ui.u(700), Ui.u(452)), 0, 6, 0, 0));
        panel.addView(col, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER));
        Ui.rise(panel, 80);
        return panel;
    }

    /** Accent radial glow under the car. */
    private static final class GlowEllipse extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();

        GlowEllipse(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = w / 2f;
            RadialGradient g = new RadialGradient(w / 2f, h / 2f, r, Ui.accentGlow(), 0, Shader.TileMode.CLAMP);
            m.setScale(1f, h / w, w / 2f, h / 2f);
            g.setLocalMatrix(m);
            p.setShader(g);
            c.drawOval(0, 0, w, h, p);
        }
    }

    // ---- Minimal: huge clock on the theme wallpaper ---------------------------------------
    private View minimal(Context c) {
        LinearLayout col = Ui.col(c);
        col.setGravity(Gravity.CENTER);
        LinearLayout clockRow = Ui.row(c);
        clockRow.setGravity(Gravity.TOP);
        bigClock = Ui.text(c, "", 200, Ui.TEXT, Ui.display(500));
        bigClock.setLetterSpacing(-0.02f);
        shadow(bigClock);
        bigAmPm = Ui.text(c, "", 52, Ui.TEXT_E6, Ui.display(600));
        bigAmPm.setLetterSpacing(0.02f);
        shadow(bigAmPm);
        clockRow.addView(bigClock);
        clockRow.addView(bigAmPm, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 14, 22, 0, 0));
        col.addView(clockRow, Ui.lp(Ui.WRAP, Ui.WRAP));
        bigDate = Ui.text(c, "", 28, Ui.TEXT_E6, Ui.body(400));
        shadow(bigDate);
        col.addView(bigDate, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 10, 0, 0));
        bigGreeting = Ui.text(c, "", 20, Ui.TEXT_2, Ui.body(400));
        shadow(bigGreeting);
        col.addView(bigGreeting, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 28, 0, 0));
        updateClock();
        Ui.rise(col, 80);
        return col;
    }

    private static void shadow(TextView t) { t.setShadowLayer(Ui.uf(18), 0, Ui.uf(4), 0x8C000000); }

    private void updateClock() {
        if (bigClock == null) return;
        bigClock.setText(Parts.timeShort());
        bigAmPm.setText(Parts.ampm());
        bigDate.setText(Parts.dateLong());
        bigGreeting.setText(Parts.greeting());
    }

    // ---- Lifecycle ------------------------------------------------------------------------
    @Override
    void onShow() {
        a.background().setWallpaperMode(layout == 2);
        if (spin != null) spin.start();
        onMedia();
        onWeather();
        if (map != null) map.update();
    }

    @Override
    void onHide() {
        if (spin != null) spin.stop();
    }

    @Override
    void onTick() {
        if (header != null) header.update();
        updateClock();
        if (musicBar != null) musicBar.tick();
    }

    @Override
    void onMedia() { if (musicBar != null) musicBar.update(); }

    @Override
    void onPhone() { if (header != null) header.update(); }

    @Override
    void onWeather() { if (weather != null) weather.update(); }
}
