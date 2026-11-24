package it.tiziano.agenda;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_CALENDAR = 1;
    private static final int REQ_AUTH = 2;
    private static final int REQ_WALLPAPER = 3;
    private static final int REQ_SHOT = 4;

    private TextView calendarStatus;
    private TextView calendarButton;
    private TextView tasksStatus;
    private TextView tasksConnect;
    private TextView tasksDisconnect;
    private String lastError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View content = findViewById(R.id.content);
        if (getResources().getConfiguration().screenWidthDp < 560) {
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            content.setLayoutParams(lp);
        }

        calendarStatus = findViewById(R.id.calendar_status);
        calendarButton = findViewById(R.id.calendar_button);
        tasksStatus = findViewById(R.id.tasks_status);
        tasksConnect = findViewById(R.id.tasks_connect);
        tasksDisconnect = findViewById(R.id.tasks_disconnect);

        calendarButton.setOnClickListener(v ->
                requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, REQ_CALENDAR));
        tasksConnect.setOnClickListener(v -> connect());
        tasksDisconnect.setOnClickListener(v -> {
            Store.setConnected(this, false);
            lastError = null;
            AgendaWidget.renderAll(this);
            refreshUi();
        });
        setupLook();

        findViewById(R.id.refresh_button).setOnClickListener(v -> {
            AgendaWidget.renderAll(this);
            RefreshJob.runNow(this);
            Toast.makeText(this, "Aggiornamento in corso", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        RefreshJob.schedule(this);
        refreshUi();
    }

    private void connect() {
        lastError = null;
        Identity.getAuthorizationClient(this)
                .authorize(TasksApi.request())
                .addOnSuccessListener(result -> {
                    if (result.hasResolution() && result.getPendingIntent() != null) {
                        try {
                            startIntentSenderForResult(result.getPendingIntent().getIntentSender(),
                                    REQ_AUTH, null, 0, 0, 0, null);
                        } catch (IntentSender.SendIntentException e) {
                            showError(e);
                        }
                    } else {
                        onAuthorized();
                    }
                })
                .addOnFailureListener(this::showError);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SHOT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) alignFromShot(data.getData());
            return;
        }
        if (requestCode == REQ_WALLPAPER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) importWallpaper(data.getData());
            return;
        }
        if (requestCode != REQ_AUTH) return;
        if (resultCode != RESULT_OK) {
            lastError = "Collegamento annullato.";
            refreshUi();
            return;
        }
        try {
            AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
            if (r.getAccessToken() != null) {
                onAuthorized();
            } else {
                lastError = "Google non ha concesso l'accesso ai promemoria.";
                refreshUi();
            }
        } catch (ApiException e) {
            showError(e);
        }
    }

    private void onAuthorized() {
        Store.setConnected(this, true);
        RefreshJob.runNow(this);
        Toast.makeText(this, "Promemoria collegati", Toast.LENGTH_SHORT).show();
        refreshUi();
    }

    private void showError(Exception e) {
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getStatusCode();
            if (code == 10) {
                lastError = "Errore 10: Google non riconosce l'app. Controlla su Google Cloud "
                        + "il client OAuth Android (nome pacchetto e SHA-1).";
            } else {
                lastError = "Errore " + code + ": " + e.getMessage();
            }
        } else {
            lastError = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        refreshUi();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AgendaWidget.renderAll(this);
        refreshUi();
    }

    private void refreshUi() {
        boolean calendarOk = checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
        calendarStatus.setText(calendarOk ? R.string.calendar_ok : R.string.calendar_missing);
        calendarButton.setVisibility(calendarOk ? View.GONE : View.VISIBLE);

        boolean connected = Store.isConnected(this);
        String status;
        if (connected) {
            int count = Store.getTasks(this).size();
            long sync = Store.getLastSync(this);
            status = getString(R.string.tasks_on) + " · " + count + " da fare";
            if (sync > 0) {
                status += " · aggiornati alle "
                        + new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date(sync));
            }
            String err = Store.getError(this);
            if (err != null) status += "\n" + err;
        } else {
            status = getString(R.string.tasks_off);
        }
        if (lastError != null) status += "\n" + lastError;
        tasksStatus.setText(status);
        tasksConnect.setVisibility(connected ? View.GONE : View.VISIBLE);
        tasksDisconnect.setVisibility(connected ? View.VISIBLE : View.GONE);
    }

    // Aspetto

    private void setupLook() {
        LinearLayout row = findViewById(R.id.swatches);
        float d = getResources().getDisplayMetrics().density;
        int size = Math.round(34 * d);
        for (int i = 0; i < Store.COLORS.length; i++) {
            final int color = Store.COLORS[i];
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(Math.round(12 * d));
            dot.setLayoutParams(lp);
            dot.setTag(color);
            dot.setContentDescription(Store.COLOR_NAMES[i]);
            dot.setOnClickListener(v -> {
                Store.setBgColor(this, color);
                updateLook();
                AgendaWidget.renderAll(this);
            });
            row.addView(dot);
        }

        findViewById(R.id.style_solid).setOnClickListener(v -> setStyle(Store.STYLE_SOLID));
        findViewById(R.id.style_glass).setOnClickListener(v -> setStyle(Store.STYLE_GLASS));
        findViewById(R.id.glass_pick).setOnClickListener(v -> pickImage(REQ_WALLPAPER));

        findViewById(R.id.glass_align).setOnClickListener(v -> {
            if (!Store.isCalibrating(this)) {
                Store.setCalibrating(this, true);
                AgendaWidget.renderAll(this);
                updateLook();
            } else {
                pickImage(REQ_SHOT);
            }
        });
        findViewById(R.id.glass_cancel).setOnClickListener(v -> {
            Store.setCalibrating(this, false);
            AgendaWidget.renderAll(this);
            updateLook();
        });

        SeekBar blur = findViewById(R.id.blur);
        blur.setProgress(Store.getBlur(this));
        blur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                Store.setBlur(MainActivity.this, progress);
                ((TextView) findViewById(R.id.blur_label)).setText("Sfocatura " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (!Glass.canAdjust(MainActivity.this)) {
                    if (Glass.hasWallpaper(MainActivity.this)) {
                        Toast.makeText(MainActivity.this,
                                "Scegli di nuovo lo sfondo per poter regolare la sfocatura",
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                new Thread(() -> {
                    try {
                        Glass.rebuild(MainActivity.this);
                    } catch (Exception ignored) {
                        // resta la sfocatura precedente
                    }
                    runOnUiThread(() -> {
                        updateLook();
                        AgendaWidget.renderAll(MainActivity.this);
                    });
                }).start();
            }
        });

        SeekBar bar = findViewById(R.id.opacity);
        bar.setProgress(Store.getBgOpacity(this));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                Store.setBgOpacity(MainActivity.this, progress);
                updateLook();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                AgendaWidget.renderAll(MainActivity.this);
            }
        });
        updateLook();
    }

    private void pickImage(int request) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(pick, request);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Nessuna app per scegliere immagini", Toast.LENGTH_SHORT).show();
        }
    }

    private void alignFromShot(android.net.Uri uri) {
        TextView status = findViewById(R.id.glass_status);
        status.setText("Cerco il widget nello screenshot…");
        new Thread(() -> {
            Calibrate.Result r = null;
            String error = null;
            try {
                r = Calibrate.find(this, uri);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final Calibrate.Result res = r;
            final String err = error;
            runOnUiThread(() -> {
                if (res == null) {
                    Toast.makeText(this, err != null ? "Immagine non valida: " + err
                            : "Non trovo i segni viola: lo screenshot deve mostrare il widget con i segni sui bordi",
                            Toast.LENGTH_LONG).show();
                } else {
                    int id = pickWidget(res.widget.width(), res.widget.height());
                    if (id >= 0) {
                        Store.setPlacement(this, id, res.widget, res.screenW, res.screenH);
                        Store.setCalibrating(this, false);
                        Toast.makeText(this, "Widget allineato", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Nessun widget Agenda sulla home", Toast.LENGTH_LONG).show();
                    }
                }
                AgendaWidget.renderAll(this);
                updateLook();
            });
        }).start();
    }

    /** Con più widget (es. schermo esterno e interno), sceglie quello con le misure più simili. */
    private int pickWidget(int wPx, int hPx) {
        android.appwidget.AppWidgetManager m = android.appwidget.AppWidgetManager.getInstance(this);
        int[] ids = m.getAppWidgetIds(new android.content.ComponentName(this, AgendaWidget.class));
        if (ids == null || ids.length == 0) return -1;
        if (ids.length == 1) return ids[0];
        float d = getResources().getDisplayMetrics().density;
        int best = ids[0];
        float bestDiff = Float.MAX_VALUE;
        for (int id : ids) {
            Bundle o = m.getAppWidgetOptions(id);
            int[][] sizes = {
                    {o.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                            o.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)},
                    {o.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                            o.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)}};
            for (int[] sz : sizes) {
                float diff = Math.abs(sz[0] * d - wPx) + Math.abs(sz[1] * d - hPx);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = id;
                }
            }
        }
        return best;
    }

    private void setStyle(int style) {
        Store.setStyle(this, style);
        ((SeekBar) findViewById(R.id.opacity)).setProgress(Store.getBgOpacity(this));
        updateLook();
        AgendaWidget.renderAll(this);
    }

    private void importWallpaper(android.net.Uri uri) {
        TextView status = findViewById(R.id.glass_status);
        status.setText("Preparo lo sfondo…");
        new Thread(() -> {
            String error = null;
            try {
                Glass.importWallpaper(this, uri);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String err = error;
            runOnUiThread(() -> {
                if (err != null) Toast.makeText(this, "Immagine non valida: " + err, Toast.LENGTH_LONG).show();
                updateLook();
                AgendaWidget.renderAll(this);
            });
        }).start();
    }

    private void updateLook() {
        boolean glass = Store.getStyle(this) == Store.STYLE_GLASS;
        findViewById(R.id.style_solid).setBackgroundResource(glass ? R.drawable.bg_button_ghost : R.drawable.bg_button);
        findViewById(R.id.style_glass).setBackgroundResource(glass ? R.drawable.bg_button : R.drawable.bg_button_ghost);
        findViewById(R.id.glass_box).setVisibility(glass ? View.VISIBLE : View.GONE);
        boolean hasWall = Glass.hasWallpaper(this);
        ((TextView) findViewById(R.id.glass_pick)).setText(hasWall ? R.string.glass_change : R.string.glass_pick);
        String gs;
        if (!hasWall) {
            gs = "Scegli dalla galleria la stessa immagine che usi come sfondo della home: "
                    + "verrà sfocata e colorata come le cartelle di One UI.";
        } else if (Store.isCalibrating(this)) {
            gs = "Sui bordi del widget sono comparsi quattro segni viola. Vai alla home, fai uno screenshot "
                    + "(tasto laterale + volume giù), torna qui e tocca «Scegli lo screenshot».";
        } else if (!Store.anyPlacement(this)) {
            gs = "Sfondo pronto. Ora tocca «Allinea con uno screenshot»: serve a capire "
                    + "dove si trova il widget sulla home.";
        } else {
            gs = "Sfondo allineato. Se sposti o ridimensioni il widget, rifai l'allineamento.";
        }
        boolean cal = Store.isCalibrating(this);
        TextView align = findViewById(R.id.glass_align);
        align.setText(cal ? "Scegli lo screenshot" : "Allinea con uno screenshot");
        align.setVisibility(hasWall ? View.VISIBLE : View.GONE);
        findViewById(R.id.glass_cancel).setVisibility(cal ? View.VISIBLE : View.GONE);
        findViewById(R.id.glass_pick).setBackgroundResource(hasWall ? R.drawable.bg_button_ghost : R.drawable.bg_button);
        ((TextView) findViewById(R.id.glass_status)).setText(gs);
        ((TextView) findViewById(R.id.blur_label)).setText("Sfocatura " + Store.getBlur(this) + "%");
        findViewById(R.id.blur_label).setVisibility(hasWall ? View.VISIBLE : View.GONE);
        findViewById(R.id.blur).setVisibility(hasWall ? View.VISIBLE : View.GONE);

        int selected = Store.getBgColor(this);
        int opacity = Store.getBgOpacity(this);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout row = findViewById(R.id.swatches);
        for (int i = 0; i < row.getChildCount(); i++) {
            View dot = row.getChildAt(i);
            int color = (int) dot.getTag();
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            if (color == selected) {
                g.setStroke(Math.round(2.5f * d), 0xFFFFFFFF);
            } else {
                g.setStroke(Math.round(1 * d), 0x40FFFFFF);
            }
            dot.setBackground(g);
            if (color == selected) {
                ((TextView) findViewById(R.id.swatch_name)).setText(Store.COLOR_NAMES[i]);
            }
        }

        ImageView fill = findViewById(R.id.preview_fill);
        View preview = findViewById(R.id.preview);
        preview.setClipToOutline(true);
        android.graphics.Bitmap glassPreview = null;
        if (glass && hasWall) {
            android.graphics.Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
            int pw = Math.max(1, preview.getWidth() > 0 ? preview.getWidth() : screen.width() - Math.round(80 * d));
            int ph = Math.round(64 * d);
            glassPreview = Glass.render(this, (screen.width() - pw) / 2, screen.height() / 2,
                    pw, ph, screen.width(), screen.height(), selected, opacity);
        }
        if (glassPreview != null) {
            fill.setImageBitmap(glassPreview);
            fill.clearColorFilter();
            fill.setImageAlpha(255);
        } else {
            fill.setImageResource(R.drawable.bg_preview);
            fill.setColorFilter(selected);
            fill.setImageAlpha(Math.round((glass ? 35 : opacity) * 2.55f));
        }
        ((TextView) findViewById(R.id.opacity_label)).setText(
                (glass ? "Intensità del colore " : "Opacità ") + opacity + "%");
    }
}
