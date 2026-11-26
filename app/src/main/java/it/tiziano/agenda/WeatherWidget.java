package it.tiziano.agenda;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Map;

/** Widget meteo nello stesso stile vetro di Agenda. */
public class WeatherWidget extends AppWidgetProvider {

    private static final int[] COL = {R.id.col_0, R.id.col_1, R.id.col_2, R.id.col_3, R.id.col_4, R.id.col_5};
    private static final int[] HOUR = {R.id.h_0, R.id.h_1, R.id.h_2, R.id.h_3, R.id.h_4, R.id.h_5};
    private static final int[] ICON = {R.id.i_0, R.id.i_1, R.id.i_2, R.id.i_3, R.id.i_4, R.id.i_5};
    private static final int[] TEMP = {R.id.t_0, R.id.t_1, R.id.t_2, R.id.t_3, R.id.t_4, R.id.t_5};
    private static final int[] PROB = {R.id.p_0, R.id.p_1, R.id.p_2, R.id.p_3, R.id.p_4, R.id.p_5};

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
        RefreshJob.runNow(context);
    }

    public static boolean hasAny(Context c) {
        int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, WeatherWidget.class));
        return ids != null && ids.length > 0;
    }

    public static void renderAll(Context ctx) {
        Context c = ctx.getApplicationContext();
        AppWidgetManager manager = AppWidgetManager.getInstance(c);
        int[] ids = manager.getAppWidgetIds(new ComponentName(c, WeatherWidget.class));
        if (ids == null || ids.length == 0) return;
        Weather.Data data = Weather.load(c);
        for (int id : ids) {
            Bitmap glass = Look.glassFor(c, manager, id);
            Map<SizeF, RemoteViews> sizes = new ArrayMap<>();
            // Stretto: 4 ore; largo: 6 ore. Basso: niente fascia oraria.
            sizes.put(new SizeF(100f, 80f), build(c, data, glass, 0));
            sizes.put(new SizeF(100f, 175f), build(c, data, glass, 4));
            sizes.put(new SizeF(250f, 80f), build(c, data, glass, 0));
            sizes.put(new SizeF(250f, 175f), build(c, data, glass, 6));
            manager.updateAppWidget(id, new RemoteViews(sizes));
        }
    }

    private static RemoteViews build(Context c, Weather.Data d, Bitmap glass, int columns) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_weather);
        Look.applyBackground(c, v, glass);

        Intent open = new Intent(c, ActionActivity.class)
                .putExtra(ActionActivity.EXTRA_ACTION, ActionActivity.ACTION_OPEN_WEATHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.content, PendingIntent.getActivity(c, 50, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        v.setTextViewText(R.id.place, Weather.PLACE);
        if (d == null) {
            v.setTextViewText(R.id.temp, "--°");
            v.setTextViewText(R.id.summary, "Aggiornamento…");
            v.setTextViewText(R.id.minmax, "");
            v.setImageViewResource(R.id.icon, R.drawable.ic_w_cloud);
            v.setViewVisibility(R.id.hourly, View.GONE);
            return v;
        }
        v.setTextViewText(R.id.temp, d.temp + "°");
        v.setTextViewText(R.id.summary, d.summary);
        v.setTextViewText(R.id.minmax, "↑" + d.max + "°  ↓" + d.min + "°");
        v.setImageViewResource(R.id.icon, Weather.icon(d.code, d.day));

        int n = Math.min(columns, d.hours.size());
        v.setViewVisibility(R.id.hourly, n > 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < COL.length; i++) {
            if (i >= n) {
                v.setViewVisibility(COL[i], View.GONE);
                continue;
            }
            Weather.Hour h = d.hours.get(i);
            v.setViewVisibility(COL[i], View.VISIBLE);
            v.setTextViewText(HOUR[i], h.label);
            v.setImageViewResource(ICON[i], Weather.icon(h.code, h.day));
            v.setTextViewText(TEMP[i], h.temp + "°");
            v.setTextViewText(PROB[i], h.prob >= 20 ? h.prob + "%" : "");
        }
        return v;
    }
}
