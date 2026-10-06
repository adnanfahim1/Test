package com.adnan.glasslauncher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Car settings. EQ, camera, steering keys, lights, radio region and factory reset are
 * controlled by the head unit vendor; Android has no public API for them. Each row opens
 * the vendor's own settings app, which the user picks once.
 */
final class CarScreen extends SidebarScreen {
    private static final String[] LABELS = {"Sound & EQ", "Reverse camera", "Steering wheel", "Lights & driving",
            "Navigation & startup", "System"};
    private static final Icons.Glyph[] GLYPHS = {Icons.SLIDERS, Icons.CAMERA, Icons.WHEEL, Icons.BULB, Icons.NAV, Icons.CHIP};

    CarScreen(MainActivity a) { super(a, LABELS.length); }

    @Override String title() { return "Car settings"; }
    @Override String sectionLabel(int i) { return LABELS[i]; }
    @Override Icons.Glyph sectionGlyph(int i) { return GLYPHS[i]; }
    @Override String linkLabel() { return "Android settings"; }
    @Override void onLink() { a.show(MainActivity.SETTINGS); }

    private final View.OnClickListener vendor = new View.OnClickListener() {
        @Override
        public void onClick(View v) { a.openVendorSettings(); }
    };

    private void vendorBanner(LinearLayout out) {
        String label = AppsRepo.labelFor(a, a.prefs().vendorApp());
        if (label == null) {
            para(out, "These settings live in the head unit's own settings app. Choose it once (it is often called “Car settings”, “CarSetting” or “Factory”) and every row here opens it.");
            button(out, "Choose head unit settings app", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) { pickVendor(); }
            });
        } else {
            nav(out, "Head unit settings app", "Rows on this page open this app", label, new View.OnClickListener() {
                @Override
                public void onClick(View v) { pickVendor(); }
            });
        }
    }

    private void pickVendor() {
        a.pickApp("Head unit settings app", new MainActivity.AppCallback() {
            @Override
            public void picked(String component, String label) {
                a.prefs().setVendorApp(component);
                a.toast("Car settings rows now open " + label);
                refresh();
            }
        });
    }

    @Override
    void fillSection(int i, LinearLayout out) {
        switch (i) {
            case 0:
                heading(out, "Sound & EQ", null);
                vendorBanner(out);
                nav(out, "Equalizer", "Tries the Android equalizer first, then the head unit app", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { a.openEqualizer(); }
                });
                nav(out, "Loudness & bass boost", null, null, vendor);
                nav(out, "Balance & fader", null, null, vendor);
                break;
            case 1:
                heading(out, "Reverse camera", null);
                vendorBanner(out);
                nav(out, "Camera format", "PAL / NTSC / AHD", null, vendor);
                nav(out, "Parking guide lines", null, null, vendor);
                nav(out, "Mirror image", null, null, vendor);
                nav(out, "Lower volume when reversing", "Music drops while the camera is on", null, vendor);
                break;
            case 2:
                heading(out, "Steering wheel keys", "Steering-wheel buttons are read by the vehicle module (MCU), so learning them happens in the head unit app.");
                vendorBanner(out);
                nav(out, "Learn steering wheel keys", null, null, vendor);
                break;
            case 3:
                heading(out, "Lights & driving", null);
                vendorBanner(out);
                nav(out, "Button backlight colour", null, null, vendor);
                nav(out, "Dim with headlights", "Uses the illumination wire", null, vendor);
                nav(out, "Block video while driving", "Needs the parking-brake wire", null, vendor);
                nav(out, "Boot logo", null, null, vendor);
                break;
            case 4:
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
