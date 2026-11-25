package it.tiziano.agenda;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Effetto vetro come le cartelle di One UI: lo sfondo della home viene sfocato una volta,
 * poi per ogni widget si ritaglia il pezzo che sta esattamente dietro e lo si tinge.
 */
public final class Glass {

    private static final String FILE = "wall_blur.png";
    private static final String BASE = "wall_base.png";
    /** Raggio massimo della sfocatura, sull'immagine ridotta a 360 px. */
    private static final int MAX_RADIUS = 16;
    private static final int BASE_WIDTH = 360;
    /** Il bitmap del widget è a un terzo della risoluzione: è sfocato, non si nota. */
    private static final int SCALE_DOWN = 3;

    private static Bitmap cache;

    private Glass() {
    }

    public static boolean hasWallpaper(Context c) {
        return new File(c.getFilesDir(), FILE).exists();
    }

    /** Carica l'immagine scelta, la riduce, la sfoca e la salva. */
    public static void importWallpaper(Context c, Uri uri) throws IOException {
        Bitmap src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri),
                (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth();
                    if (w > BASE_WIDTH * 4) decoder.setTargetSampleSize(w / (BASE_WIDTH * 2));
                });
        int h = Math.max(1, Math.round(src.getHeight() * (BASE_WIDTH / (float) src.getWidth())));
        Bitmap small = Bitmap.createScaledBitmap(src, BASE_WIDTH, h, true)
                .copy(Bitmap.Config.ARGB_8888, true);
        src.recycle();
        save(c, BASE, small);
        rebuild(c);
    }

    public static boolean canAdjust(Context c) {
        return new File(c.getFilesDir(), BASE).exists();
    }

    /** Rifà la sfocatura con l'intensità scelta nelle impostazioni. */
    public static void rebuild(Context c) throws IOException {
        File base = new File(c.getFilesDir(), BASE);
        if (!base.exists()) return;
        Bitmap b = BitmapFactory.decodeFile(base.getAbsolutePath());
        if (b == null) return;
        b = b.copy(Bitmap.Config.ARGB_8888, true);
        int radius = Math.round(Store.getBlur(c) * MAX_RADIUS / 100f);
        if (radius > 0) blur(b, radius);
        save(c, FILE, b);
        synchronized (Glass.class) {
            cache = b;
        }
    }

    private static void save(Context c, String name, Bitmap b) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    public static void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
        new File(c.getFilesDir(), BASE).delete();
        synchronized (Glass.class) {
            cache = null;
        }
    }

    private static synchronized Bitmap load(Context c) {
        if (cache == null) {
            File f = new File(c.getFilesDir(), FILE);
            if (f.exists()) cache = BitmapFactory.decodeFile(f.getAbsolutePath());
        }
        return cache;
    }

    /**
     * Ritaglio per un rettangolo dello schermo (in pixel), con lo sfondo adattato allo schermo
     * come fa il sistema (riempie e centra).
     */
    public static Bitmap render(Context c, int left, int top, int width, int height,
                                int screenW, int screenH, int tint, int strength) {
        return render(c, left, top, width, height, screenW, screenH, tint, strength, false);
    }

    /** @param vivid colori più saturi e luminosi, come i widget di One UI 9.5 */
    public static Bitmap render(Context c, int left, int top, int width, int height,
                                int screenW, int screenH, int tint, int strength, boolean vivid) {
        Bitmap blur = load(c);
        if (blur == null || width <= 0 || height <= 0 || screenW <= 0 || screenH <= 0) return null;

        float s = Math.max(screenW / (float) blur.getWidth(), screenH / (float) blur.getHeight());
        float ox = (screenW - blur.getWidth() * s) / 2f;
        float oy = (screenH - blur.getHeight() * s) / 2f;
        Rect src = new Rect(
                Math.round((left - ox) / s), Math.round((top - oy) / s),
                Math.round((left + width - ox) / s), Math.round((top + height - oy) / s));
        src.intersect(0, 0, blur.getWidth(), blur.getHeight());
        if (src.isEmpty()) return null;

        int outW = Math.max(1, width / SCALE_DOWN);
        int outH = Math.max(1, height / SCALE_DOWN);
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (vivid) {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(1.45f);
            android.graphics.ColorMatrix light = new android.graphics.ColorMatrix();
            light.setScale(1.12f, 1.12f, 1.12f, 1f);
            cm.postConcat(light);
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        }
        cv.drawBitmap(blur, src, new Rect(0, 0, outW, outH), paint);
        int a = Math.round(Math.max(0, Math.min(100, strength)) * 2.55f);
        cv.drawColor((tint & 0x00FFFFFF) | (a << 24));
        return out;
    }

    // Sfondo preso dallo screenshot: esattamente ciò che sta dietro al widget

    private static final int SHOT_SCALE = 4;
    private static final int SHOT_MAX_RADIUS = 15;
    private static final java.util.Map<String, Bitmap> shotCache = new java.util.HashMap<>();

    private static File shotFile(Context c, int id) {
        return new File(c.getFilesDir(), "shot_" + id + ".png");
    }

    public static boolean hasShot(Context c, int id) {
        return shotFile(c, id).exists();
    }

    public static int firstShot(Context c) {
        File[] files = c.getFilesDir().listFiles();
        if (files == null) return -1;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("shot_") && n.endsWith(".png")) {
                try {
                    return Integer.parseInt(n.substring(5, n.length() - 4));
                } catch (NumberFormatException ignored) {
                    // file estraneo
                }
            }
        }
        return -1;
    }

    public static void saveShot(Context c, int id, Bitmap crop) throws IOException {
        int w = Math.max(16, crop.getWidth() / SHOT_SCALE);
        int h = Math.max(16, crop.getHeight() / SHOT_SCALE);
        Bitmap small = Bitmap.createScaledBitmap(crop, w, h, true);
        save(c, "shot_" + id + ".png", small);
        synchronized (Glass.class) {
            shotCache.clear();
        }
    }

    /** Sfondo del widget: ritaglio sfocato, poi colori vivaci e tinta se richiesti. */
    public static Bitmap renderShot(Context c, int id, int tint, int strength, boolean vivid, int blurPct) {
        String key = id + ":" + blurPct;
        Bitmap blurred;
        synchronized (Glass.class) {
            blurred = shotCache.get(key);
        }
        if (blurred == null) {
            File f = shotFile(c, id);
            if (!f.exists()) return null;
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null) return null;
            blurred = b.copy(Bitmap.Config.ARGB_8888, true);
            int radius = Math.round(blurPct * SHOT_MAX_RADIUS / 100f);
            if (radius > 0) blur(blurred, radius);
            synchronized (Glass.class) {
                shotCache.put(key, blurred);
            }
        }
        Bitmap out = Bitmap.createBitmap(blurred.getWidth(), blurred.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawBitmap(blurred, 0, 0, vividPaint(vivid));
        int a = Math.round(Math.max(0, Math.min(100, strength)) * 2.55f);
        cv.drawColor((tint & 0x00FFFFFF) | (a << 24));
        return out;
    }

    /** Anteprima per l'app: una fascia centrale dello sfondo del primo widget. */
    public static Bitmap previewShot(Context c, int pw, int ph, int tint, int strength, boolean vivid, int blurPct) {
        int id = firstShot(c);
        if (id < 0) return null;
        Bitmap full = renderShot(c, id, tint, strength, vivid, blurPct);
        if (full == null) return null;
        int w = full.getWidth();
        int h = Math.max(1, Math.min(full.getHeight(), Math.round(w * ph / (float) Math.max(1, pw))));
        return Bitmap.createBitmap(full, 0, (full.getHeight() - h) / 2, w, h);
    }

    private static Paint vividPaint(boolean vivid) {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (vivid) {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(1.45f);
            android.graphics.ColorMatrix light = new android.graphics.ColorMatrix();
            light.setScale(1.12f, 1.12f, 1.12f, 1f);
            cm.postConcat(light);
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        }
        return paint;
    }

    /** Sfocatura a scatole ripetuta tre volte: molto vicina a una gaussiana. */
    private static void blur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            pass(px, tmp, w, h, r, true);
            pass(tmp, px, w, h, r, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[idx(line, clamp(k, len), w, horizontal)];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[idx(line, i, w, horizontal)] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int pOut = in[idx(line, clamp(i - r, len), w, horizontal)];
                int pIn = in[idx(line, clamp(i + r + 1, len), w, horizontal)];
                sr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                sg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                sb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }
}
