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
        String source; // phone name when the data came from Glass Link, null for the internet

        String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("t", tempC).put("c", code).put("d", day).put("r", rainPct).put("v", visibilityKm)
                        .put("p", place == null ? "" : place).put("at", fetchedAt);
                if (source != null) o.put("src", source);
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
                d.source = o.has("src") ? o.optString("src", null) : null;
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
                if (d == null) {
                    // Backup service when Open-Meteo is down or blocked.
                    try {
                        d = parseWttr(new JSONObject(get(String.format(Locale.US, "https://wttr.in/%.4f,%.4f?format=j1", lat, lon))));
                        d.place = place;
                        d.fetchedAt = System.currentTimeMillis();
                        err = null;
                    } catch (Exception e2) {
                        if (err == null) err = e2.getMessage();
                    }
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

    /** A raw Open-Meteo forecast answer (as fetched by the Glass Link phone app). */
    static Data fromOpenMeteo(String raw, String place) {
        try {
            Data d = parse(new JSONObject(raw));
            d.place = place;
            d.fetchedAt = System.currentTimeMillis();
            return d;
        } catch (Exception e) {
            return null;
        }
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
    /** wttr.in "j1" answer, mapped onto the same weather codes as Open-Meteo. */
    static Data parseWttr(JSONObject o) throws Exception {
        JSONObject cur = o.getJSONArray("current_condition").getJSONObject(0);
        Data d = new Data();
        d.tempC = Double.parseDouble(cur.getString("temp_C"));
        d.code = wwoToWmo(cur.optInt("weatherCode", 116));
        d.visibilityKm = cur.has("visibility") ? cur.optDouble("visibility", -1) : -1;
        try {
            JSONObject day = o.getJSONArray("weather").getJSONObject(0);
            JSONArray hours = day.getJSONArray("hourly");
            String obs = cur.optString("localObsDateTime", ""); // "2026-10-06 02:15 PM"
            int hour = hour24(obs.length() > 11 ? obs.substring(11) : "");
            JSONObject h = hours.getJSONObject(Math.min(hours.length() - 1, Math.max(0, hour / 3)));
            d.rainPct = h.optInt("chanceofrain", -1);
            JSONObject astro = day.getJSONArray("astronomy").getJSONObject(0);
            int rise = hour24(astro.optString("sunrise")), set = hour24(astro.optString("sunset"));
            if (hour >= 0 && rise >= 0 && set >= 0) d.day = hour >= rise && hour < set;
        } catch (Exception ignored) {
        }
        return d;
    }

    /** "02:15 PM" -> 14, or -1. */
    private static int hour24(String t) {
        try {
            t = t.trim();
            int h = Integer.parseInt(t.substring(0, 2));
            boolean pm = t.toUpperCase(Locale.US).endsWith("PM");
            if (h == 12) h = 0;
            return pm ? h + 12 : h;
        } catch (Exception e) {
            return -1;
        }
    }

    static int wwoToWmo(int c) {
        switch (c) {
            case 113: return 0;
            case 116: return 2;
            case 119: case 122: return 3;
            case 143: case 248: case 260: return 45;
            case 263: case 266: return 51;
            case 176: case 293: case 296: return 61;
            case 299: case 302: return 63;
            case 305: case 308: return 65;
            case 281: case 284: case 311: case 314: case 179: case 182: case 185: case 317: case 320: case 362: case 365: return 66;
            case 323: case 326: case 368: return 71;
            case 329: case 332: return 73;
            case 227: case 230: case 335: case 338: case 371: return 75;
            case 350: case 374: case 377: return 77;
            case 353: return 80;
            case 356: return 81;
            case 359: return 82;
            case 200: case 386: case 389: case 392: case 395: return 95;
            default: return 2;
        }
    }

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
