package it.tiziano.agenda;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
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
}
