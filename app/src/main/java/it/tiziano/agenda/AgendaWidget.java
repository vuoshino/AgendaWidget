package it.tiziano.agenda;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.util.ArrayMap;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Widget agenda: si impagina da solo in versione stretta o larga. */
public class AgendaWidget extends AppWidgetProvider {

    private static final float WIDE_FROM_DP = 250f;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        renderAll(context);
        RefreshJob.schedule(context);
        RefreshJob.runNow(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        renderAll(context);
    }

    @Override
    public void onEnabled(Context context) {
        RefreshJob.schedule(context);
    }

    /** Ridisegna tutti i widget dell'app presenti sulla home (agenda e meteo). */
    public static void renderAll(Context ctx) {
        renderAgenda(ctx);
        WeatherWidget.renderAll(ctx);
    }

    private static void renderAgenda(Context ctx) {
        Context c = ctx.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        int[] ids = manager.getAppWidgetIds(new ComponentName(c, AgendaWidget.class));
        if (ids == null || ids.length == 0) return;

        List<AgendaData.Row> rows = AgendaData.build(c);
        for (int id : ids) {
            Bitmap glass = Look.glassFor(c, manager, id);
            Map<SizeF, RemoteViews> sizes = new ArrayMap<>();
            sizes.put(new SizeF(100f, 80f), build(c, id, rows, true, glass));
            sizes.put(new SizeF(WIDE_FROM_DP, 80f), build(c, id, rows, false, glass));
            manager.updateAppWidget(id, new RemoteViews(sizes));
        }
    }

    private static RemoteViews build(Context c, int widgetId, List<AgendaData.Row> rows,
                                     boolean narrow, Bitmap glass) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_agenda);

        Look.applyBackground(c, v, glass);

        LocalDate today = LocalDate.now();
        Locale it = Locale.ITALIAN;
        v.setTextViewText(R.id.day_name,
                AgendaData.capitalize(today.format(DateTimeFormatter.ofPattern("EEEE", it))));
        v.setTextViewText(R.id.date_text,
                today.format(DateTimeFormatter.ofPattern(narrow ? "d MMM" : "d MMMM", it)));

        // Intestazione: apre il calendario sulla data di oggi
        // Passa da ActionActivity, che intanto rileva dove si trova il widget sullo schermo
        Intent openCalendar = new Intent(c, ActionActivity.class)
                .putExtra(ActionActivity.EXTRA_ACTION, ActionActivity.ACTION_OPEN_CALENDAR)
                .putExtra(ActionActivity.EXTRA_WIDGET, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Deve essere modificabile: solo così il launcher può aggiungere la posizione del tocco
        v.setOnClickPendingIntent(R.id.header, PendingIntent.getActivity(c, 1000 + widgetId, openCalendar,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        // Pulsante +: nuovo evento
        Intent newEvent = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.add_button, PendingIntent.getActivity(c, 2, newEvent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        // Elenco
        RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder();
        items.setHasStableIds(false);
        items.setViewTypeCount(5);
        long index = 0;
        for (AgendaData.Row r : rows) {
            items.addItem(index++, item(c, r));
        }
        v.setRemoteAdapter(R.id.list, items.build());

        Intent template = new Intent(c, ActionActivity.class);
        v.setPendingIntentTemplate(R.id.list, PendingIntent.getActivity(c, 3, template,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        boolean empty = rows.isEmpty();
        v.setViewVisibility(R.id.list, empty ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.empty, empty ? View.VISIBLE : View.GONE);

        return v;
    }

    private static RemoteViews item(Context c, AgendaData.Row r) {
        String pkg = c.getPackageName();
        switch (r.type) {
            case AgendaData.TYPE_SUMMARY: {
                RemoteViews v = new RemoteViews(pkg, R.layout.item_summary);
                v.setTextViewText(R.id.text, r.title);
                v.setOnClickFillInIntent(R.id.row, new Intent()
                        .putExtra(ActionActivity.EXTRA_ACTION, ActionActivity.ACTION_TOGGLE_OVERDUE));
                return v;
            }
            case AgendaData.TYPE_TASK: {
                RemoteViews v = new RemoteViews(pkg, R.layout.item_task);
                v.setTextViewText(R.id.title, r.title);
                v.setTextViewText(R.id.subtitle, r.subtitle);
                v.setViewVisibility(R.id.subtitle, r.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
                v.setOnClickFillInIntent(R.id.row, new Intent()
                        .putExtra(ActionActivity.EXTRA_ACTION, ActionActivity.ACTION_OPEN_TASKS));
                v.setOnClickFillInIntent(R.id.check, new Intent()
                        .putExtra(ActionActivity.EXTRA_ACTION, ActionActivity.ACTION_COMPLETE)
                        .putExtra(ActionActivity.EXTRA_TASK, r.taskId)
                        .putExtra(ActionActivity.EXTRA_LIST, r.listId));
                return v;
            }
            case AgendaData.TYPE_EVENT: {
                RemoteViews v = new RemoteViews(pkg, R.layout.item_event);
                v.setTextViewText(R.id.title, r.title);
                v.setTextViewText(R.id.subtitle, r.subtitle);
                v.setInt(R.id.bar, "setColorFilter", r.color);
                v.setOnClickFillInIntent(R.id.row, new Intent()
                        .putExtra(ActionActivity.EXTRA_ACTION, ActionActivity.ACTION_OPEN_EVENT)
                        .putExtra(ActionActivity.EXTRA_EVENT, r.eventId)
                        .putExtra(ActionActivity.EXTRA_BEGIN, r.begin)
                        .putExtra(ActionActivity.EXTRA_END, r.end));
                return v;
            }
            case AgendaData.TYPE_NOTE: {
                RemoteViews v = new RemoteViews(pkg, R.layout.item_note);
                v.setTextViewText(R.id.text, r.title);
                return v;
            }
            default: {
                RemoteViews v = new RemoteViews(pkg, R.layout.item_day);
                v.setTextViewText(R.id.text, r.title);
                return v;
            }
        }
    }
}
