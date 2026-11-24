package it.tiziano.agenda;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.provider.CalendarContract;

import java.util.List;

/** Aggiorna il widget: periodicamente, quando cambia il calendario e su richiesta. */
public class RefreshJob extends JobService {

    private static final int JOB_PERIODIC = 1;
    private static final int JOB_CALENDAR = 2;
    private static final int JOB_NOW = 3;
    private static final int JOB_COMPLETE_BASE = 1000;

    private static final String KEY_COMPLETE_LIST = "complete_list";
    private static final String KEY_COMPLETE_TASK = "complete_task";

    public static void schedule(Context ctx) {
        Context c = ctx.getApplicationContext();
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName cn = new ComponentName(c, RefreshJob.class);

        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, cn)
                    .setPeriodic(30 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        }
        scheduleCalendarTrigger(c);
    }

    private static void scheduleCalendarTrigger(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_CALENDAR, new ComponentName(c, RefreshJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(2000)
                .setTriggerContentMaxDelay(15000)
                .build());
    }

    public static void runNow(Context ctx) {
        Context c = ctx.getApplicationContext();
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_NOW, new ComponentName(c, RefreshJob.class))
                .setOverrideDeadline(1000)
                .build());
    }

    public static void completeTask(Context ctx, String listId, String taskId) {
        Context c = ctx.getApplicationContext();
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString(KEY_COMPLETE_LIST, listId);
        extras.putString(KEY_COMPLETE_TASK, taskId);
        int id = JOB_COMPLETE_BASE + (taskId.hashCode() & 0x7FFF);
        js.schedule(new JobInfo.Builder(id, new ComponentName(c, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final Context c = getApplicationContext();
        new Thread(() -> {
            boolean retry = false;
            try {
                retry = work(c, params.getExtras());
            } finally {
                if (params.getJobId() == JOB_CALENDAR) scheduleCalendarTrigger(c);
                jobFinished(params, retry);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** @return true se il lavoro va ripetuto più tardi. */
    private static boolean work(Context c, PersistableBundle extras) {
        boolean retry = false;
        String completeList = extras != null ? extras.getString(KEY_COMPLETE_LIST) : null;
        String completeTask = extras != null ? extras.getString(KEY_COMPLETE_TASK) : null;

        if (Store.isConnected(c)) {
            try {
                String token = TasksApi.token(c);
                if (token == null) {
                    Store.setError(c, "Serve di nuovo il consenso: apri Agenda e ricollega l'account.");
                } else {
                    if (completeList != null && completeTask != null) {
                        TasksApi.complete(token, completeList, completeTask);
                    }
                    List<Store.TaskItem> tasks = TasksApi.fetchAll(token);
                    Store.saveTasks(c, tasks);
                }
            } catch (Exception e) {
                Store.setError(c, e.getMessage() != null ? e.getMessage() : e.toString());
                retry = completeTask != null;
            }
        }
        AgendaWidget.renderAll(c);
        return retry;
    }
}
