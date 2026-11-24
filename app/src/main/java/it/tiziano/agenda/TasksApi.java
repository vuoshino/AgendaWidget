package it.tiziano.agenda;

import android.content.Context;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Accesso a Google Tasks tramite le API REST. */
public final class TasksApi {

    public static final String SCOPE = "https://www.googleapis.com/auth/tasks";
    private static final String BASE = "https://tasks.googleapis.com/tasks/v1";

    private TasksApi() {
    }

    public static AuthorizationRequest request() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
                .build();
    }

    /** Token di accesso, oppure null se serve di nuovo il consenso dell'utente. Bloccante. */
    public static String token(Context c) throws Exception {
        AuthorizationResult r = Tasks.await(
                Identity.getAuthorizationClient(c.getApplicationContext()).authorize(request()),
                30, TimeUnit.SECONDS);
        if (r.hasResolution()) return null;
        return r.getAccessToken();
    }

    /** Tutti i promemoria non completati di tutte le liste. */
    public static List<Store.TaskItem> fetchAll(String token) throws IOException, JSONException {
        List<Store.TaskItem> out = new ArrayList<>();
        JSONObject lists = get(BASE + "/users/@me/lists?maxResults=100", token);
        JSONArray listItems = lists.optJSONArray("items");
        if (listItems == null) return out;

        for (int i = 0; i < listItems.length(); i++) {
            String listId = listItems.getJSONObject(i).optString("id");
            if (listId.isEmpty()) continue;
            String pageToken = null;
            do {
                String url = BASE + "/lists/" + enc(listId)
                        + "/tasks?showCompleted=false&showHidden=false&maxResults=100"
                        + (pageToken != null ? "&pageToken=" + enc(pageToken) : "");
                JSONObject page = get(url, token);
                JSONArray tasks = page.optJSONArray("items");
                if (tasks != null) {
                    for (int j = 0; j < tasks.length(); j++) {
                        JSONObject t = tasks.getJSONObject(j);
                        String title = t.optString("title").trim();
                        if (title.isEmpty() || "completed".equals(t.optString("status"))) continue;
                        String due = t.optString("due");
                        if (due.length() >= 10) due = due.substring(0, 10);
                        else due = "";
                        out.add(new Store.TaskItem(t.optString("id"), listId, title, due));
                    }
                }
                pageToken = page.optString("nextPageToken", null);
                if (pageToken != null && pageToken.isEmpty()) pageToken = null;
            } while (pageToken != null);
        }
        return out;
    }

    /** Segna un promemoria come completato. */
    public static void complete(String token, String listId, String taskId) throws IOException {
        URL url = new URL(BASE + "/lists/" + enc(listId) + "/tasks/" + enc(taskId));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            byte[] body = "{\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Google Tasks ha risposto " + code);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject get(String address, String token) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = read(in);
            if (code < 200 || code >= 300) {
                throw new IOException("Google Tasks ha risposto " + code + ": " + shorten(text));
            }
            return new JSONObject(text);
        } finally {
            conn.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
