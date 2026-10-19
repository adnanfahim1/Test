package com.adnan.glasslink;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.util.List;

/**
 * Which Bluetooth devices the phone uses for calls and for media audio. A car stereo
 * connected for calls but not for media is the usual reason the phone's music stays on the
 * phone: then the car is told the stereo's name and how to switch "Media audio" on.
 * (Apps can't switch it on themselves; Android reserves that for system apps.)
 */
final class BtAudio implements BluetoothProfile.ServiceListener {
    private static volatile BluetoothProfile headset, a2dp;
    private static boolean started;

    private BtAudio() {}

    static synchronized void init(Context c) {
        if (started || !LinkService.hasBluetoothPermission(c)) return;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null) return;
            BtAudio l = new BtAudio();
            ad.getProfileProxy(c.getApplicationContext(), l, BluetoothProfile.HEADSET);
            ad.getProfileProxy(c.getApplicationContext(), l, BluetoothProfile.A2DP);
            started = true;
        } catch (Throwable ignored) {}
    }

    @Override
    public void onServiceConnected(int profile, BluetoothProfile proxy) {
        if (profile == BluetoothProfile.HEADSET) headset = proxy;
        else if (profile == BluetoothProfile.A2DP) a2dp = proxy;
        PhoneMedia.refresh();
    }

    @Override
    public void onServiceDisconnected(int profile) {
        if (profile == BluetoothProfile.HEADSET) headset = null;
        else if (profile == BluetoothProfile.A2DP) a2dp = null;
    }

    /** Name of a device connected for calls but not for media audio, or null. */
    static String callsOnly() {
        try {
            BluetoothProfile h = headset, m = a2dp;
            if (h == null) return null;
            List<BluetoothDevice> media = m != null ? m.getConnectedDevices() : null;
            for (BluetoothDevice d : h.getConnectedDevices()) {
                if (media == null || !media.contains(d)) return LinkService.name(d);
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
