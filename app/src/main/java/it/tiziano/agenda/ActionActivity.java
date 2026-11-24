package it.tiziano.agenda;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Intent;
import android.os.Bundle;
import android.provider.CalendarContract;

/** Attività invisibile che riceve i tocchi sugli elementi del widget. */
public class ActionActivity extends Activity {

    public static final String EXTRA_ACTION = "action";
    public static final String EXTRA_TASK = "task";
    public static final String EXTRA_LIST = "list";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_BEGIN = "begin";
    public static final String EXTRA_END = "end";

    public static final String ACTION_OPEN_EVENT = "open_event";
    public static final String ACTION_OPEN_TASKS = "open_tasks";
    public static final String ACTION_COMPLETE = "complete";
    public static final String ACTION_OPEN_CALENDAR = "open_calendar";
    public static final String ACTION_TOGGLE_OVERDUE = "toggle_overdue";
    public static final String EXTRA_WIDGET = "widget";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String action = in != null ? in.getStringExtra(EXTRA_ACTION) : null;

        if (ACTION_TOGGLE_OVERDUE.equals(action)) {
            Store.toggleOverdue(this);
            AgendaWidget.renderAll(this);
        } else if (ACTION_OPEN_CALENDAR.equals(action)) {
            locate(in);
            android.net.Uri.Builder time = CalendarContract.CONTENT_URI.buildUpon().appendPath("time");
            ContentUris.appendId(time, System.currentTimeMillis());
            start(new Intent(Intent.ACTION_VIEW, time.build()));
        } else if (ACTION_OPEN_EVENT.equals(action)) {
            long id = in.getLongExtra(EXTRA_EVENT, -1);
            if (id >= 0) {
                Intent view = new Intent(Intent.ACTION_VIEW,
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, in.getLongExtra(EXTRA_BEGIN, 0))
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, in.getLongExtra(EXTRA_END, 0));
                start(view);
            }
        } else if (ACTION_OPEN_TASKS.equals(action)) {
            Intent tasks = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.tasks");
            if (tasks == null) {
                tasks = getPackageManager().getLaunchIntentForPackage("com.google.android.calendar");
            }
            if (tasks != null) start(tasks);
        } else if (ACTION_COMPLETE.equals(action)) {
            String taskId = in.getStringExtra(EXTRA_TASK);
            String listId = in.getStringExtra(EXTRA_LIST);
            if (taskId != null && listId != null) {
                Store.removeTask(this, taskId);
                AgendaWidget.renderAll(this);
                RefreshJob.completeTask(this, listId, taskId);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
            // nessuna app per aprirlo
        }
    }

    /**
     * Il launcher indica dove si trovava la data toccata: da lì si ricava l'angolo del widget
     * (la data sta a 15 dp dal bordo sinistro e 14 dp da quello alto).
     */
    private void locate(Intent in) {
        int id = in.getIntExtra(EXTRA_WIDGET, -1);
        android.graphics.Rect b = in.getSourceBounds();
        if (id < 0 || b == null) return;
        float d = getResources().getDisplayMetrics().density;
        android.graphics.Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
        int left = b.left - Math.round(15 * d);
        int top = b.top - Math.round(14 * d);
        if (Store.setPlacement(this, id, left, top, screen.width(), screen.height())) {
            AgendaWidget.renderAll(this);
        }
    }
}
