package it.tiziano.agenda;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Meteo di Guidonia Montecelio da Open-Meteo (gratuito, senza chiave). */
public final class Weather {

    public static final String PLACE = "Guidonia Montecelio";
    private static final double LAT = 41.9998;
    private static final double LON = 12.7246;
    private static final long MIN_INTERVAL = 20 * 60 * 1000L;
    private static final int HOURS = 6;

    private static final String URL_FORECAST = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=" + LAT + "&longitude=" + LON
            + "&current=temperature_2m,weather_code,is_day"
            + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
            + "&daily=temperature_2m_max,temperature_2m_min"
            + "&timezone=Europe%2FRome&forecast_days=2";

    private Weather() {
    }

    public static final class Hour {
        public String label = "";
        public int temp;
        public int code;
        public boolean day;
        public int prob;
    }

    public static final class Data {
        public int temp;
        public int code;
        public boolean day;
        public int max;
        public int min;
        public String summary = "";
        /** Versione corta per il widget stretto: prima la pioggia, se c'è. */
        public String shortSummary = "";
        public final List<Hour> hours = new ArrayList<>();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** Scarica le previsioni, al massimo ogni 20 minuti salvo richiesta esplicita. */
    public static void refresh(Context c, boolean force) throws IOException {
        long last = prefs(c).getLong("time", 0);
        if (!force && System.currentTimeMillis() - last < MIN_INTERVAL && prefs(c).contains("json")) return;
        HttpURLConnection conn = (HttpURLConnection) new URL(URL_FORECAST).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            String body = read(conn.getInputStream());
            new JSONObject(body); // verifica che sia valido
            prefs(c).edit().putString("json", body).putLong("time", System.currentTimeMillis()).apply();
        } catch (org.json.JSONException e) {
            throw new IOException("Risposta non valida");
        } finally {
            conn.disconnect();
        }
    }

    /** Previsioni dall'ultima copia salvata, oppure null. */
    public static Data load(Context c) {
        String raw = prefs(c).getString("json", null);
        if (raw == null) return null;
        try {
            JSONObject j = new JSONObject(raw);
            JSONObject cur = j.getJSONObject("current");
            Data d = new Data();
            d.temp = (int) Math.round(cur.getDouble("temperature_2m"));
            d.code = cur.getInt("weather_code");
            d.day = cur.getInt("is_day") == 1;

            JSONObject daily = j.getJSONObject("daily");
            d.max = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
            d.min = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));

            JSONObject h = j.getJSONObject("hourly");
            JSONArray times = h.getJSONArray("time");
            JSONArray temps = h.getJSONArray("temperature_2m");
            JSONArray codes = h.getJSONArray("weather_code");
            JSONArray probs = h.getJSONArray("precipitation_probability");
            JSONArray days = h.getJSONArray("is_day");

            // Ora attuale: "2026-11-27T00:45" -> "2026-11-27T00:00"
            String now = java.time.LocalDateTime.now(java.time.ZoneId.of("Europe/Rome"))
                    .withMinute(0).withSecond(0).withNano(0).toString();
            if (now.length() > 16) now = now.substring(0, 16);
            int start = 0;
            for (int i = 0; i < times.length(); i++) {
                if (times.getString(i).equals(now)) {
                    start = i;
                    break;
                }
            }

            for (int i = start; i < start + HOURS && i < times.length(); i++) {
                Hour hr = new Hour();
                if (i == start) {
                    hr.label = "Ora";
                    hr.temp = d.temp;
                    hr.code = d.code;
                    hr.day = d.day;
                } else {
                    hr.label = times.getString(i).substring(11, 13);
                    hr.temp = (int) Math.round(temps.getDouble(i));
                    hr.code = codes.getInt(i);
                    hr.day = days.getInt(i) == 1;
                }
                hr.prob = probs.isNull(i) ? 0 : probs.getInt(i);
                d.hours.add(hr);
            }

            // Frase: descrizione + quando arriva o finisce la pioggia
            StringBuilder sb = new StringBuilder(describe(d.code));
            boolean rainingNow = isWet(d.code);
            for (int i = start + 1; i < start + 13 && i < times.length(); i++) {
                int code = codes.getInt(i);
                int prob = probs.isNull(i) ? 0 : probs.getInt(i);
                int hour = Integer.parseInt(times.getString(i).substring(11, 13));
                if (!rainingNow && (isWet(code) || prob >= 50)) {
                    sb.append(" · ").append(isStorm(code) ? "temporali " : "pioggia ").append(dalle(hour));
                    break;
                }
                if (rainingNow && !isWet(code) && prob < 40) {
                    sb.append(" · fino ").append(alle(hour));
                    break;
                }
            }
            d.summary = sb.toString();
            String extra = sb.length() > describe(d.code).length()
                    ? sb.substring(describe(d.code).length() + 3) : "";
            d.shortSummary = extra.isEmpty() ? shortDescribe(d.code)
                    : Character.toUpperCase(extra.charAt(0)) + extra.substring(1);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static String dalle(int h) {
        return h == 1 ? "dall'1" : "dalle " + h;
    }

    private static String alle(int h) {
        return h == 1 ? "all'1" : "alle " + h;
    }

    static boolean isWet(int code) {
        return (code >= 51 && code <= 67) || (code >= 71 && code <= 86) || code >= 95;
    }

    static boolean isStorm(int code) {
        return code >= 95;
    }

    public static String describe(int code) {
        if (code == 0) return "Sereno";
        if (code == 1) return "Poco nuvoloso";
        if (code == 2) return "Parzialmente nuvoloso";
        if (code == 3) return "Nuvoloso";
        if (code == 45 || code == 48) return "Nebbia";
        if (code >= 51 && code <= 55) return "Pioggerella";
        if (code == 56 || code == 57) return "Pioggerella gelata";
        if (code == 61) return "Pioggia debole";
        if (code == 63) return "Pioggia";
        if (code == 65) return "Pioggia forte";
        if (code == 66 || code == 67) return "Pioggia gelata";
        if (code >= 71 && code <= 75) return "Neve";
        if (code == 77) return "Nevischio";
        if (code >= 80 && code <= 82) return "Rovesci";
        if (code == 85 || code == 86) return "Rovesci di neve";
        if (code == 95) return "Temporale";
        if (code >= 96) return "Temporale con grandine";
        return "";
    }

    public static String shortDescribe(int code) {
        if (code == 2) return "Parz. nuvoloso";
        if (code == 96 || code == 99) return "Temporale";
        if (code == 56 || code == 57) return "Pioggerella";
        if (code == 85 || code == 86) return "Neve";
        return describe(code);
    }

    public static int icon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        if (code == 1 || code == 2) return day ? R.drawable.ic_w_partly_day : R.drawable.ic_w_partly_night;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_storm;
        if (code >= 51) return R.drawable.ic_w_rain;
        return R.drawable.ic_w_cloud;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
