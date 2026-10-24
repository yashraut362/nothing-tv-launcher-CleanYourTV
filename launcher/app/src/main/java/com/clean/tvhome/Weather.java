package com.clean.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather for the screensaver, from Open-Meteo (free, no key, no account): now, and the week.
 *
 * Where: weather_latitude / weather_longitude in config.xml when set; otherwise the city the TV's
 * internet address belongs to, looked up once a week (ipapi.co) and kept on the TV.
 * When: only while the screensaver runs, at most every 30 minutes. The last reading is kept, so
 * the screensaver shows it at once and offline; one older than three hours is not shown.
 */
final class Weather {

    static final int DAYS = 7;

    static final class Reading {
        float temp, high, low;
        int code;
        boolean day;
        String place;
        long time;
        /** The week from today: dates as yyyy-MM-dd, WMO codes, highs and lows. */
        String[] dates = new String[0];
        int[] codes = new int[0];
        float[] highs = new float[0], lows = new float[0];
    }

    interface Listener {
        void onWeather(Reading reading);
    }

    private static final long STALE_MS = 30 * 60_000L;
    private static final long TOO_OLD_MS = 3 * 60 * 60_000L;
    private static final long PLACE_MS = 7 * 24 * 60 * 60_000L;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static boolean fetching;

    private Weather() {}

    /** The kept reading, or null when there is none or it is over three hours old. */
    static Reading cached(Context context) {
        SharedPreferences p = prefs(context);
        long time = p.getLong("time", 0);
        if (time == 0 || System.currentTimeMillis() - time > TOO_OLD_MS) return null;
        try {
            return parse(new JSONObject(p.getString("forecast", "{}")), p.getString("place", ""), time);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Fetches a new reading in the background when the kept one is 30 minutes old. */
    static void refreshIfStale(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        SharedPreferences p = prefs(app);
        // A reading from before the week was fetched has no "forecast": fetch now rather than in 30 minutes.
        if (fetching || (p.contains("forecast") && System.currentTimeMillis() - p.getLong("time", 0) < STALE_MS)) return;
        fetching = true;
        Handler main = new Handler(Looper.getMainLooper());
        worker.execute(() -> {
            Reading r = null;
            try {
                r = fetch(app);
            } catch (Exception ignored) {
                // offline or a service down: keep showing the last reading
            }
            final Reading result = r;
            main.post(() -> {
                fetching = false;
                if (result != null) listener.onWeather(result);
            });
        });
    }

    private static Reading fetch(Context context) throws IOException, JSONException {
        SharedPreferences p = prefs(context);
        double lat, lon;
        String place;
        String cfgLat = context.getString(R.string.weather_latitude).trim();
        String cfgLon = context.getString(R.string.weather_longitude).trim();
        if (!cfgLat.isEmpty() && !cfgLon.isEmpty()) {
            lat = Double.parseDouble(cfgLat);
            lon = Double.parseDouble(cfgLon);
            place = context.getString(R.string.weather_place).trim();
        } else if (System.currentTimeMillis() - p.getLong("placeTime", 0) < PLACE_MS) {
            lat = p.getFloat("lat", 0);
            lon = p.getFloat("lon", 0);
            place = p.getString("place", "");
        } else {
            JSONObject ip = new JSONObject(get("https://ipapi.co/json/"));
            lat = ip.getDouble("latitude");
            lon = ip.getDouble("longitude");
            place = ip.optString("city", "");
            p.edit().putFloat("lat", (float) lat).putFloat("lon", (float) lon)
                    .putString("place", place).putLong("placeTime", System.currentTimeMillis()).apply();
        }

        boolean fahrenheit = "fahrenheit".equals(context.getString(R.string.weather_unit));
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=%d%s",
                // One extra day: just after midnight Open-Meteo can still start the list at yesterday.
                lat, lon, DAYS + 1, fahrenheit ? "&temperature_unit=fahrenheit" : "");
        JSONObject forecast = new JSONObject(get(url));
        long now = System.currentTimeMillis();
        Reading r = parse(forecast, place, now);
        p.edit().putString("forecast", forecast.toString()).putString("place", place).putLong("time", now).apply();
        return r;
    }

    private static Reading parse(JSONObject forecast, String place, long time) throws JSONException {
        JSONObject now = forecast.getJSONObject("current");
        JSONObject daily = forecast.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray highs = daily.getJSONArray("temperature_2m_max");
        JSONArray lows = daily.getJSONArray("temperature_2m_min");
        Reading r = new Reading();
        r.temp = (float) now.getDouble("temperature_2m");
        r.code = now.getInt("weather_code");
        r.day = now.optInt("is_day", 1) == 1;
        r.place = place;
        r.time = time;
        int n = Math.min(DAYS + 1, dates.length());
        r.dates = new String[n];
        r.codes = new int[n];
        r.highs = new float[n];
        r.lows = new float[n];
        for (int i = 0; i < n; i++) {
            r.dates[i] = dates.getString(i);
            r.codes[i] = codes.getInt(i);
            r.highs[i] = (float) highs.getDouble(i);
            r.lows[i] = (float) lows.getDouble(i);
        }
        // Today's high and low: the entry dated today, which is not always the first.
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date());
        r.high = r.low = r.temp;
        for (int i = 0; i < n; i++) {
            if (r.dates[i].equals(today)) {
                r.high = r.highs[i];
                r.low = r.lows[i];
            }
        }
        return r;
    }

    private static String get(String address) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "NothingTvLauncher");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /**
     * A WMO weather code as an icon name: "clear_day", "rain_night" and so on, the names of the
     * Sanyaas icon set (drawable/sanyaas_*). DotMatrix.forWeather() maps the same names onto
     * the launcher's own dot-matrix pictures when those files are absent.
     */
    static String icon(int code, boolean day) {
        String sky;
        if (code == 0) sky = "clear";
        else if (code <= 2) sky = "partly_cloudy";
        else if (code == 3) sky = "cloudy";
        else if (code == 45 || code == 48) sky = "fog";
        else if (code == 56 || code == 57 || code == 66 || code == 67) sky = "sleet";
        else if ((code >= 51 && code <= 55) || (code >= 61 && code <= 65) || (code >= 80 && code <= 82)) sky = "rain";
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) sky = "snow";
        else if (code >= 95) sky = "thunderstorm";
        else sky = "cloudy";
        return sky + (day ? "_day" : "_night");
    }

    /** A WMO weather code in a word or two, for the mono line. */
    static String condition(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }
}
