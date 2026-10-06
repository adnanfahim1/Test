package com.adnan.glasslauncher;

import android.view.View;

/** One full-screen page hosted by {@link MainActivity}. */
abstract class Screen {
    final MainActivity a;
    private View root;

    Screen(MainActivity a) { this.a = a; }

    View view() {
        if (root == null) root = build();
        return root;
    }

    /** Drops the cached view so it is rebuilt (theme or font size changed). */
    void invalidate() { root = null; }

    abstract View build();

    void onShow() {}
    void onHide() {}
    /** Called about once a second while visible (clock, progress). */
    void onTick() {}
    void onMedia() {}
    void onPhone() {}
    void onWeather() {}
    void onNav() {}
    /** @return true if the screen handled Back itself. */
    boolean onBack() { return false; }
}
