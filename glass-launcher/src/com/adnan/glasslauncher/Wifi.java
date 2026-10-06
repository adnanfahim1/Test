package com.adnan.glasslauncher;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wi-Fi for the Settings page. Up to Android 9 the launcher switches Wi-Fi and joins
 * networks itself. Android 10+ only allows that from the system, so there it opens the
 * system Wi-Fi panel on top of the launcher.
 */
final class Wifi {
    static final class Network {
        String ssid;
        int level;      // 0-4
        int rssi;       // dBm
        boolean secured;
        String caps;
    }

    private final WifiManager wm;

    Wifi(Context c) {
        wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    boolean available() { return wm != null; }

    /** Whether the launcher may switch Wi-Fi and join networks itself (Android 9 and older). */
    static boolean canControl() { return NewApi.SDK < 29; }

    boolean enabled() {
        try { return wm != null && wm.isWifiEnabled(); } catch (Throwable t) { return false; }
    }

    boolean setEnabled(boolean on) {
        try { return wm != null && wm.setWifiEnabled(on); } catch (Throwable t) { return false; }
    }

    /** Current network name, or null when not connected. */
    String connectedSsid() {
        try {
            WifiInfo i = wm != null ? wm.getConnectionInfo() : null;
            if (i == null || i.getNetworkId() == -1) return null;
            String s = unquote(i.getSSID());
            return s == null || s.length() == 0 || "<unknown ssid>".equals(s) ? "Connected network" : s;
        } catch (Throwable t) {
            return null;
        }
    }

    int connectedLevel() {
        try {
            WifiInfo i = wm.getConnectionInfo();
            return WifiManager.calculateSignalLevel(i.getRssi(), 5);
        } catch (Throwable t) {
            return 0;
        }
    }

    boolean scan() {
        try { return wm != null && wm.startScan(); } catch (Throwable t) { return false; }
    }

    /** Nearby networks, strongest first, one entry per name. Needs location on Android 6+. */
    List<Network> nearby() {
        List<Network> out = new ArrayList<Network>();
        List<ScanResult> rs;
        try {
            rs = wm != null ? wm.getScanResults() : null;
        } catch (Throwable t) {
            rs = null;
        }
        if (rs == null) return out;
        Map<String, Network> best = new HashMap<String, Network>();
        for (ScanResult r : rs) {
            if (r.SSID == null || r.SSID.length() == 0) continue;
            Network n = new Network();
            n.ssid = r.SSID;
            n.rssi = r.level;
            n.level = WifiManager.calculateSignalLevel(r.level, 5);
            n.caps = r.capabilities != null ? r.capabilities : "";
            n.secured = n.caps.contains("WEP") || n.caps.contains("PSK") || n.caps.contains("EAP") || n.caps.contains("SAE");
            Network old = best.get(n.ssid);
            if (old == null || n.rssi > old.rssi) best.put(n.ssid, n);
        }
        out.addAll(best.values());
        Collections.sort(out, new Comparator<Network>() {
            @Override
            public int compare(Network x, Network y) {
                if (x.rssi != y.rssi) return y.rssi - x.rssi;
                return x.ssid.compareToIgnoreCase(y.ssid);
            }
        });
        return out;
    }

    /** True when this phone/head unit already has the network saved (no password needed). */
    boolean isSaved(String ssid) {
        return savedId(ssid) != -1;
    }

    private int savedId(String ssid) {
        try {
            List<WifiConfiguration> list = wm.getConfiguredNetworks();
            if (list == null) return -1;
            for (WifiConfiguration c : list) if (ssid.equals(unquote(c.SSID))) return c.networkId;
        } catch (Throwable ignored) {}
        return -1;
    }

    /** Joins a network (Android 9 and older). {@code password} is ignored for open networks. */
    boolean connect(Network n, String password) {
        if (wm == null) return false;
        try {
            if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
            int id = savedId(n.ssid);
            if (id == -1 || password != null) {
                WifiConfiguration c = new WifiConfiguration();
                c.SSID = quote(n.ssid);
                if (!n.secured) {
                    c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else if (n.caps.contains("WEP")) {
                    c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                    c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                    c.wepKeys[0] = isHex(password) ? password : quote(password);
                    c.wepTxKeyIndex = 0;
                } else {
                    c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    c.preSharedKey = quote(password);
                }
                if (id != -1) {
                    c.networkId = id;
                    id = wm.updateNetwork(c);
                } else {
                    id = wm.addNetwork(c);
                }
                if (id == -1) return false;
                wm.saveConfiguration();
            }
            wm.disconnect();
            boolean ok = wm.enableNetwork(id, true);
            wm.reconnect();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    static String signalLabel(int level) {
        return level >= 4 ? "Excellent" : level == 3 ? "Good" : level == 2 ? "Fair" : "Weak";
    }

    private static boolean isHex(String s) {
        if (s == null || !(s.length() == 10 || s.length() == 26 || s.length() == 58)) return false;
        for (int i = 0; i < s.length(); i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    private static String quote(String s) { return "\"" + (s == null ? "" : s) + "\""; }

    static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }
}
