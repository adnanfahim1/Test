package com.adnan.glasslauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the connected phone in several independent ways, because head units differ:
 * 1) ACL connect/disconnect broadcasts seen while the launcher runs,
 * 2) Bluetooth profile proxies (A2DP/headset, and the car-side A2DP-sink / hands-free
 *    client profiles head units use),
 * 3) BluetoothDevice.isConnected() via reflection (works on many firmwares, blocked on some).
 * Any method failing is ignored, so a firmware or Android update can only reduce accuracy,
 * never crash.
 */
final class PhoneLink {
    /** Public profile ids: HEADSET 1, A2DP 2; car-side ids used by head units: A2DP_SINK 11, HEADSET_CLIENT 16. */
    private static final int[] PROFILES = {1, 2, 11, 16};
    static final String PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    private final Context ctx;
    private final Map<Integer, BluetoothProfile> proxies = new HashMap<Integer, BluetoothProfile>();
    private final Map<String, String> aclConnected = new LinkedHashMap<String, String>();
    private Runnable onChange;

    PhoneLink(Context c) { ctx = c.getApplicationContext(); }

    void setOnChange(Runnable r) { onChange = r; }

    /** Android 12+ needs the runtime "Nearby devices" permission for names and states. */
    boolean needsPermission() { return Build.VERSION.SDK_INT >= 31 && !NewApi.granted(ctx, PERM_CONNECT); }

    static BluetoothAdapter adapter() {
        try { return BluetoothAdapter.getDefaultAdapter(); } catch (Throwable t) { return null; }
    }

    boolean enabled() {
        try {
            BluetoothAdapter a = adapter();
            return a != null && !needsPermission() && a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    void start() {
        if (needsPermission()) return;
        BluetoothAdapter a = adapter();
        if (a == null) return;
        for (final int p : PROFILES) {
            if (proxies.containsKey(p)) continue;
            try {
                a.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        proxies.put(profile, proxy);
                        if (onChange != null) onChange.run();
                    }

                    @Override
                    public void onServiceDisconnected(int profile) { proxies.remove(profile); }
                }, p);
            } catch (Throwable ignored) {
                // Profile not present on this unit.
            }
        }
    }

    void stop() {
        BluetoothAdapter a = adapter();
        for (Map.Entry<Integer, BluetoothProfile> e : proxies.entrySet()) {
            try { if (a != null) a.closeProfileProxy(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
        }
        proxies.clear();
    }

    void onAcl(BluetoothDevice d, boolean connected) {
        if (d == null) return;
        try {
            if (connected) aclConnected.put(d.getAddress(), name(d));
            else aclConnected.remove(d.getAddress());
        } catch (Throwable ignored) {
        }
    }

    /** Name of a connected phone, or null. */
    String connectedName() {
        if (!enabled()) return null;
        for (BluetoothProfile p : proxies.values()) {
            try {
                List<BluetoothDevice> list = p.getConnectedDevices();
                if (list != null && !list.isEmpty()) return name(list.get(0));
            } catch (Throwable ignored) {
            }
        }
        try {
            BluetoothAdapter a = adapter();
            Set<BluetoothDevice> bonded = a != null ? a.getBondedDevices() : null;
            if (bonded != null) {
                Method m = BluetoothDevice.class.getMethod("isConnected");
                for (BluetoothDevice d : bonded) {
                    if (Boolean.TRUE.equals(m.invoke(d))) return name(d);
                }
            }
        } catch (Throwable ignored) {
        }
        for (String n : aclConnected.values()) return n;
        return null;
    }

    private static String name(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n != null && n.length() > 0 ? n : "Phone";
        } catch (Throwable t) {
            return "Phone";
        }
    }
}
