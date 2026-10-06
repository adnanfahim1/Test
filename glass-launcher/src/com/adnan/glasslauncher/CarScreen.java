package com.adnan.glasslauncher;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Car settings. EQ, camera, steering keys, lights, radio region and factory reset are
 * controlled by the head unit vendor; Android has no public API for them. The head unit's
 * own apps are found automatically (see {@link Vendor}) and every row opens the right one.
 */
final class CarScreen extends SidebarScreen {
    private static final String[] LABELS = {"Quick launch", "Sound & EQ", "Reverse camera", "Steering wheel",
            "Lights & driving", "Navigation & startup", "System"};
    private static final Icons.Glyph[] GLYPHS = {Icons.GRID, Icons.SLIDERS, Icons.CAMERA, Icons.WHEEL, Icons.BULB,
            Icons.NAV, Icons.CHIP};
    private static final String[] QUICK = {Vendor.RADIO, Vendor.PHONE, Vendor.PROJECTION, Vendor.CAMERA, Vendor.EQ,
            Vendor.AV_IN, Vendor.CAR_SETTINGS, Vendor.VIDEO};

    CarScreen(MainActivity a) { super(a, LABELS.length); }

    @Override String title() { return "Car settings"; }
    @Override String sectionLabel(int i) { return LABELS[i]; }
    @Override Icons.Glyph sectionGlyph(int i) { return GLYPHS[i]; }
    @Override String linkLabel() { return "Android settings"; }
    @Override void onLink() { a.show(MainActivity.SETTINGS); }

    private View.OnClickListener open(final String role) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.openRole(role); }
        };
    }

    private void vendorBanner(LinearLayout out) {
        ComponentName cn = Vendor.find(a, a.prefs(), Vendor.CAR_SETTINGS);
        String label = cn != null ? AppsRepo.labelFor(a, cn.flattenToString()) : null;
        boolean auto = a.prefs().roleApp(Vendor.CAR_SETTINGS) == null;
        if (label == null) {
            para(out, "These settings live in the head unit's own settings app, which wasn't found automatically. "
                    + "Choose it once (it is often called “Car settings”, “CarSetting” or “Factory”).");
            button(out, "Choose head unit settings app", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) { a.pickConnection(Vendor.CAR_SETTINGS, CarScreen.this); }
            });
        } else {
            nav(out, "Head unit settings app", auto ? "Found automatically · rows on this page open it" : "Chosen by you · rows on this page open it",
                    label, new View.OnClickListener() {
                        @Override
                        public void onClick(View v) { a.pickConnection(Vendor.CAR_SETTINGS, CarScreen.this); }
                    });
        }
    }

    @Override
    void fillSection(int i, LinearLayout out) {
        View.OnClickListener vendor = open(Vendor.CAR_SETTINGS);
        switch (i) {
            case 0:
                heading(out, "Quick launch", "The head unit's own apps, found automatically. Long-press a tile to choose a different app.");
                quickGrid(out);
                break;
            case 1:
                heading(out, "Sound & EQ", null);
                vendorBanner(out);
                nav(out, "Equalizer", "Opens the head unit's EQ, or Android's if there is none", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { a.openEqualizer(); }
                });
                nav(out, "Loudness & bass boost", null, null, vendor);
                nav(out, "Balance & fader", null, null, vendor);
                break;
            case 2:
                heading(out, "Reverse camera", null);
                vendorBanner(out);
                nav(out, "Open camera / DVR", null, null, open(Vendor.CAMERA));
                nav(out, "Camera format", "PAL / NTSC / AHD", null, vendor);
                nav(out, "Parking guide lines", null, null, vendor);
                nav(out, "Mirror image", null, null, vendor);
                nav(out, "Lower volume when reversing", "Music drops while the camera is on", null, vendor);
                break;
            case 3:
                heading(out, "Steering wheel keys", "Steering-wheel buttons are read by the vehicle module (MCU), so learning them happens in the head unit app. "
                        + "Once learned, play/pause, next, previous, volume, mute, call and music keys also work inside this launcher.");
                vendorBanner(out);
                nav(out, "Learn steering wheel keys", null, null, vendor);
                break;
            case 4:
                heading(out, "Lights & driving", null);
                vendorBanner(out);
                nav(out, "Button backlight colour", null, null, vendor);
                nav(out, "Dim with headlights", "Uses the illumination wire", null, vendor);
                nav(out, "Block video while driving", "Needs the parking-brake wire", null, vendor);
                nav(out, "Boot logo", null, null, vendor);
                break;
            case 5:
                heading(out, "Navigation & startup", null);
                String maps = AppsRepo.labelFor(a, a.prefs().mapsApp());
                nav(out, "Default navigation app", "Opened by Maps on the rail and the Navigation card", maps != null ? maps : "Ask each time",
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                a.pickApp("Default navigation app", new MainActivity.AppCallback() {
                                    @Override
                                    public void picked(String component, String label) {
                                        a.prefs().setMapsApp(component);
                                        a.toast("Navigate opens " + label);
                                        refresh();
                                    }
                                });
                            }
                        });
                nav(out, "CarPlay / Android Auto", null, null, open(Vendor.PROJECTION));
                nav(out, "Navigation voice mixing", "Set inside your maps app or the head unit app", null, vendor);
                nav(out, "Auto-launch CarPlay / Android Auto", null, null, vendor);
                para(out, "A launcher can't read turn-by-turn directions from another app, so the Navigation card opens your maps app instead of showing the next turn.");
                break;
            default:
                heading(out, "System", null);
                vendorBanner(out);
                nav(out, "Radio region", null, null, vendor);
                nav(out, "MCU version", null, null, vendor);
                nav(out, "Factory settings", "Installer only · usually needs a code", null, vendor);
                button(out, "Reset to factory defaults…", true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { confirmReset(); }
                });
                break;
        }
    }

    private void quickGrid(LinearLayout out) {
        LinearLayout row = null;
        for (int k = 0; k < QUICK.length; k++) {
            if (k % 4 == 0) {
                row = Ui.row(a);
                out.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 12));
            }
            final String role = QUICK[k];
            ComponentName cn = Vendor.find(a, a.prefs(), role);
            String app = cn != null ? AppsRepo.labelFor(a, cn.flattenToString()) : null;
            LinearLayout tile = Ui.col(a);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(Ui.u(8), Ui.u(14), Ui.u(8), Ui.u(12));
            tile.setBackground(Ui.tile(20));
            tile.addView(new Icons.GlassView(a, Vendor.glassIcon(role)), Ui.lp(Ui.u(52), Ui.u(52)));
            TextView t = Ui.text(a, Vendor.label(role), 15, Ui.TEXT, Ui.body(600));
            t.setGravity(Gravity.CENTER);
            tile.addView(t, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
            TextView s = Ui.text(a, app != null ? app : "Tap to choose", 12, app != null ? Ui.MUTED : Ui.FAINT, Ui.body(400));
            s.setGravity(Gravity.CENTER);
            tile.addView(s, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 3, 0, 0));
            tile.setContentDescription(Vendor.label(role));
            tile.setOnClickListener(open(role));
            tile.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    a.pickConnection(role, CarScreen.this);
                    return true;
                }
            });
            Ui.pressable(tile);
            row.addView(tile, Ui.margins(Ui.lpw(0, Ui.WRAP, 1), k % 4 == 0 ? 0 : 6, 0, k % 4 == 3 ? 0 : 6, 0));
        }
    }

    /** Never resets anything itself: it only opens the vendor screen, which asks again. */
    private void confirmReset() {
        new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Reset to factory defaults?")
                .setMessage("This erases your apps, paired phones and all settings on the head unit. It can't be undone.\n\n"
                        + "The reset itself is done in the head unit's settings app, which will ask you to confirm again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open head unit settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { a.openVendorSettings(); }
                })
                .show();
    }
}
