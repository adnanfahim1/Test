package com.adnan.glasslauncher;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * Live weather from Open-Meteo (no API key). Check Open-Meteo's current terms of use
 * before relying on it; the free tier is meant for non-commercial use.
 */
final class Weather {
    private Weather() {}

    static final class Data {
        double tempC;
        int code;
        boolean day = true;
        int rainPct = -1;
        double visibilityKm = -1;
        String place;
        long fetchedAt;

        String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("t", tempC).put("c", code).put("d", day).put("r", rainPct).put("v", visibilityKm)
                        .put("p", place == null ? "" : place).put("at", fetchedAt);
                return o.toString();
            } catch (Exception e) {
                return null;
            }
        }

        static Data fromJson(String s) {
            if (s == null) return null;
            try {
                JSONObject o = new JSONObject(s);
                Data d = new Data();
                d.tempC = o.getDouble("t");
                d.code = o.getInt("c");
                d.day = o.optBoolean("d", true);
                d.rainPct = o.optInt("r", -1);
                d.visibilityKm = o.optDouble("v", -1);
                d.place = o.optString("p", "");
                d.fetchedAt = o.optLong("at", 0);
                return d;
            } catch (Exception e) {
                return null;
            }
        }
    }

    interface Callback { void done(Data data, String error); }

    interface GeoCallback { void done(String name, double lat, double lon, String error); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void fetch(final double lat, final double lon, final String place, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Data d = null;
                String err = null;
                try {
                    String url = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                    + "&current=temperature_2m,weather_code,is_day"
                                    + "&hourly=precipitation_probability,visibility&forecast_days=1&timezone=auto",
                            lat, lon);
                    d = parse(new JSONObject(get(url)));
                    d.place = place;
                    d.fetchedAt = System.currentTimeMillis();
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                }
                final Data fd = d;
                final String fe = err;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() { cb.done(fd, fe); }
                });
            }
        }, "weather").start();
    }

    static void geocode(final String city, final GeoCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String name = null, err = null;
                double lat = 0, lon = 0;
                try {
                    String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name="
                            + URLEncoder.encode(city, "UTF-8");
                    JSONArray res = new JSONObject(get(url)).optJSONArray("results");
                    if (res == null || res.length() == 0) {
                        err = "No place called “" + city + "” was found";
                    } else {
                        JSONObject r = res.getJSONObject(0);
                        lat = r.getDouble("latitude");
                        lon = r.getDouble("longitude");
                        name = r.optString("name", city);
                    }
                } catch (Exception e) {
                    err = "Couldn't look up that city. Check the internet connection.";
                }
                final String fn = name, fe = err;
                final double fla = lat, flo = lon;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() { cb.done(fn, fla, flo, fe); }
                });
            }
        }, "geocode").start();
    }

    private static Data parse(JSONObject o) throws Exception {
        Data d = new Data();
        String nowTime = null;
        JSONObject cur = o.optJSONObject("current");
        if (cur != null) {
            d.tempC = cur.getDouble("temperature_2m");
            d.code = cur.optInt("weather_code", 0);
            d.day = cur.optInt("is_day", 1) == 1;
            nowTime = cur.optString("time", null);
        } else {
            JSONObject cw = o.getJSONObject("current_weather");
            d.tempC = cw.getDouble("temperature");
            d.code = cw.optInt("weathercode", 0);
            d.day = cw.optInt("is_day", 1) == 1;
            nowTime = cw.optString("time", null);
        }
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.optJSONArray("time");
            int idx = 0;
            if (times != null && nowTime != null && nowTime.length() >= 13) {
                String hour = nowTime.substring(0, 13);
                for (int i = 0; i < times.length(); i++) {
                    if (times.optString(i, "").startsWith(hour)) { idx = i; break; }
                }
            }
            JSONArray rain = hourly.optJSONArray("precipitation_probability");
            if (rain != null && idx < rain.length() && !rain.isNull(idx)) d.rainPct = rain.optInt(idx, -1);
            JSONArray vis = hourly.optJSONArray("visibility");
            if (vis != null && idx < vis.length() && !vis.isNull(idx)) d.visibilityKm = vis.optDouble(idx, -1000) / 1000.0;
        }
        return d;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "GlassLauncher/1.0 (Android head unit)");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Weather service answered " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** WMO weather interpretation codes, as documented by Open-Meteo. */
    static String describe(int code) {
        if (code == 0) return "Clear sky";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Rain showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    /** 0 = sun, 1 = sun + cloud, 2 = cloud, 3 = rain, 4 = storm, 5 = fog/snow. */
    static int art(int code) {
        if (code <= 1) return 0;
        if (code == 2) return 1;
        if (code == 3) return 2;
        if (code == 45 || code == 48 || (code >= 71 && code <= 77) || code == 85 || code == 86) return 5;
        if (code >= 95) return 4;
        return 3;
    }
}
