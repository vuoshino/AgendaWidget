package it.tiziano.agenda;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Preferenze e cache locale dei promemoria. */
public final class Store {

    private static final String PREFS = "agenda";
    private static final String KEY_CONNECTED = "tasks_connected";
    private static final String KEY_TASKS = "tasks_cache";
    private static final String KEY_ERROR = "tasks_error";
    private static final String KEY_SYNC = "tasks_sync";

    private Store() {
    }

    /** Un promemoria di Google Tasks. due è "aaaa-mm-gg" oppure vuoto. */
    public static final class TaskItem {
        public final String id;
        public final String listId;
        public final String title;
        public final String due;

        public TaskItem(String id, String listId, String title, String due) {
            this.id = id;
            this.listId = listId;
            this.title = title;
            this.due = due == null ? "" : due;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isConnected(Context c) {
        return prefs(c).getBoolean(KEY_CONNECTED, false);
    }

    public static void setConnected(Context c, boolean connected) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(KEY_CONNECTED, connected);
        if (!connected) {
            e.remove(KEY_TASKS).remove(KEY_ERROR).remove(KEY_SYNC);
        }
        e.apply();
    }

    public static synchronized List<TaskItem> getTasks(Context c) {
        List<TaskItem> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY_TASKS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new TaskItem(o.optString("id"), o.optString("list"),
                        o.optString("title"), o.optString("due")));
            }
        } catch (JSONException ignored) {
            // cache illeggibile: la ricreerà il prossimo aggiornamento
        }
        return out;
    }

    public static synchronized void saveTasks(Context c, List<TaskItem> tasks) {
        JSONArray arr = new JSONArray();
        try {
            for (TaskItem t : tasks) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("list", t.listId);
                o.put("title", t.title);
                o.put("due", t.due);
                arr.put(o);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs(c).edit()
                .putString(KEY_TASKS, arr.toString())
                .putLong(KEY_SYNC, System.currentTimeMillis())
                .remove(KEY_ERROR)
                .apply();
    }

    /** Toglie subito un promemoria dalla cache, prima che Google confermi. */
    public static synchronized void removeTask(Context c, String taskId) {
        List<TaskItem> tasks = getTasks(c);
        List<TaskItem> kept = new ArrayList<>();
        for (TaskItem t : tasks) {
            if (!t.id.equals(taskId)) kept.add(t);
        }
        saveTasks(c, kept);
    }

    public static void setError(Context c, String error) {
        prefs(c).edit().putString(KEY_ERROR, error).apply();
    }

    public static String getError(Context c) {
        return prefs(c).getString(KEY_ERROR, null);
    }

    public static long getLastSync(Context c) {
        return prefs(c).getLong(KEY_SYNC, 0);
    }

    // Aspetto del widget

    /** Colori proposti: il primo riprende il widget Meteo di Samsung di sera. */
    public static final int[] COLORS = {0xFF1A2857, 0xFF2E2A6E, 0xFF4B3A8F, 0xFF22242C, 0xFF000000, 0xFFB0A0FF};
    public static final String[] COLOR_NAMES = {"Blu notte (come Meteo)", "Indaco", "Viola", "Grafite", "Nero", "Lavanda"};

    public static int getBgColor(Context c) {
        return prefs(c).getInt("bg_color", COLORS[0]);
    }

    public static void setBgColor(Context c, int color) {
        prefs(c).edit().putInt("bg_color", color).apply();
    }

    /** Opacità da 0 a 100. */
    public static int getBgOpacity(Context c) {
        return prefs(c).getInt("bg_opacity", 100);
    }

    public static void setBgOpacity(Context c, int opacity) {
        prefs(c).edit().putInt("bg_opacity", Math.max(0, Math.min(100, opacity))).apply();
    }
}
