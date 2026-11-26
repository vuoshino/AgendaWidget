package it.tiziano.agenda;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

/** Aspetto comune ai widget dell'app: sfondo pieno o vetro, segni di calibrazione. */
public final class Look {

    private Look() {
    }

    /** Tutti i widget dell'app sulla home: agenda e meteo. */
    public static int[] allWidgetIds(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] a = m.getAppWidgetIds(new ComponentName(c, AgendaWidget.class));
        int[] b = m.getAppWidgetIds(new ComponentName(c, WeatherWidget.class));
        if (a == null) a = new int[0];
        if (b == null) b = new int[0];
        int[] all = new int[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    /** Sfondo vetro per questo widget, se lo stile è attivo e lo sfondo è noto. */
    public static Bitmap glassFor(Context c, AppWidgetManager manager, int id) {
        if (Store.getStyle(c) != Store.STYLE_GLASS) return null;
        if (Glass.hasShot(c, id)) {
            return Glass.renderShot(c, id, Store.getBgColor(c), Store.getBgOpacity(c),
                    Store.isVivid(c), Store.getBlur(c));
        }
        if (!Glass.hasWallpaper(c)) return null;
        int[] p = Store.getPlacement(c, id);
        if (p == null) return null;
        int[] size = p.length == 6 ? new int[]{p[4], p[5]} : sizePx(c, manager, id, p[2], p[3]);
        return Glass.render(c, p[0], p[1], size[0], size[1], p[2], p[3],
                Store.getBgColor(c), Store.getBgOpacity(c), Store.isVivid(c));
    }

    /** Dimensione del widget in pixel: in verticale vale larghezza minima × altezza massima. */
    static int[] sizePx(Context c, AppWidgetManager manager, int id, int screenW, int screenH) {
        Bundle o = manager.getAppWidgetOptions(id);
        boolean portrait = screenH >= screenW;
        int wDp = o.getInt(portrait ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
                : AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
        int hDp = o.getInt(portrait ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
        float d = c.getResources().getDisplayMetrics().density;
        return new int[]{Math.round(wDp * d), Math.round(hDp * d)};
    }

    /**
     * Applica sfondo e segni a un widget che usa gli id comuni
     * (bg_fill, bg_shade, bg_glow, bg_rim, mark_*, content).
     */
    public static void applyBackground(Context c, RemoteViews v, Bitmap glass) {
        // Durante la calibrazione il widget è vuoto: solo i segni, così lo screenshot mostra lo sfondo
        boolean calibrating = Store.isCalibrating(c);
        int marks = calibrating ? View.VISIBLE : View.GONE;
        v.setViewVisibility(R.id.mark_top, marks);
        v.setViewVisibility(R.id.mark_bottom, marks);
        v.setViewVisibility(R.id.mark_left, marks);
        v.setViewVisibility(R.id.mark_right, marks);
        v.setViewVisibility(R.id.content, calibrating ? View.INVISIBLE : View.VISIBLE);
        v.setViewVisibility(R.id.bg_rim, View.GONE);

        if (calibrating) {
            v.setInt(R.id.bg_fill, "setImageAlpha", 0);
            v.setInt(R.id.bg_shade, "setImageAlpha", 0);
            v.setViewVisibility(R.id.bg_glow, View.GONE);
            return;
        }

        if (glass != null) {
            v.setImageViewBitmap(R.id.bg_fill, glass);
            v.setInt(R.id.bg_fill, "setColorFilter", 0);
            v.setInt(R.id.bg_fill, "setImageAlpha",
                    Math.round((100 - Store.getGlassTransparency(c)) * 2.55f));
            v.setInt(R.id.bg_shade, "setImageAlpha", 0);
            // Niente bordo: le cartelle di One UI non ce l'hanno, resta solo il riflesso in alto
            v.setViewVisibility(R.id.bg_glow, Store.isVivid(c) ? View.VISIBLE : View.GONE);
        } else {
            v.setViewVisibility(R.id.bg_glow, View.GONE);
            boolean glassStyle = Store.getStyle(c) == Store.STYLE_GLASS;
            // In stile vetro, finché lo sfondo non è noto, una velatura leggera
            int opacity = glassStyle ? 35 : Store.getBgOpacity(c);
            int alpha = Math.round(opacity * 2.55f);
            v.setInt(R.id.bg_fill, "setColorFilter", Store.getBgColor(c));
            v.setInt(R.id.bg_fill, "setImageAlpha", alpha);
            v.setInt(R.id.bg_shade, "setImageAlpha", glassStyle ? 0 : alpha);
        }
    }
}
