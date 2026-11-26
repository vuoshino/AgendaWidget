package it.tiziano.agenda;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Trova il widget in uno screenshot della home grazie ai quattro segni magenta
 * che compaiono sui bordi durante la calibrazione.
 */
public final class Calibrate {

    private Calibrate() {
    }

    public static final class Result {
        public final Rect widget;
        public final int screenW;
        public final int screenH;
        /** Sfondo dietro al widget (senza i segni ai bordi). */
        public final Bitmap backdrop;

        Result(Rect widget, int screenW, int screenH, Bitmap backdrop) {
            this.widget = widget;
            this.screenW = screenW;
            this.screenH = screenH;
            this.backdrop = backdrop;
        }
    }

    /** @return i widget trovati nello screenshot (anche più di uno), lista vuota se nessuno. */
    public static List<Result> findAll(Context c, Uri uri) throws IOException {
        Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri),
                (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);

        boolean[] mask = new boolean[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bl = p & 0xFF;
            mask[i] = r > 200 && bl > 200 && g < 80;
        }

        // Gruppi di pixel magenta contigui
        List<Rect> blobs = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start]) continue;
            mask[start] = false;
            queue.add(start);
            int minX = w, minY = h, maxX = -1, maxY = -1, count = 0;
            while (!queue.isEmpty()) {
                int i = queue.poll();
                int x = i % w, y = i / w;
                count++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (x > 0 && mask[i - 1]) { mask[i - 1] = false; queue.add(i - 1); }
                if (x < w - 1 && mask[i + 1]) { mask[i + 1] = false; queue.add(i + 1); }
                if (y > 0 && mask[i - w]) { mask[i - w] = false; queue.add(i - w); }
                if (y < h - 1 && mask[i + w]) { mask[i + w] = false; queue.add(i + w); }
            }
            // Solo rettangoli pieni, sottili e allungati come i segni (lo sfondo può avere zone magenta)
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            int lo = Math.max(bw, bh), sh = Math.min(bw, bh);
            if (lo >= 50 && lo <= 500 && sh >= 8 && sh <= 80 && lo >= sh * 3 && count >= 0.7f * bw * bh) {
                blobs.add(new Rect(minX, minY, maxX + 1, maxY + 1));
            }
        }

        // Orizzontali = segni in alto e in basso, verticali = a sinistra e a destra
        List<Rect> horiz = new ArrayList<>(), vert = new ArrayList<>();
        for (Rect r : blobs) {
            if (r.width() > r.height()) horiz.add(r); else vert.add(r);
        }
        List<Result> results = new ArrayList<>();
        boolean[] usedH = new boolean[horiz.size()];
        boolean[] usedV = new boolean[vert.size()];
        // Ogni widget: un segno in alto e uno in basso sulla stessa verticale,
        // uno a sinistra e uno a destra sulla stessa orizzontale, che si incrociano al centro
        for (int t = 0; t < horiz.size(); t++) {
            if (usedH[t]) continue;
            Rect top = horiz.get(t);
            for (int bo = 0; bo < horiz.size(); bo++) {
                if (bo == t || usedH[bo]) continue;
                Rect bottom = horiz.get(bo);
                if (bottom.top <= top.bottom || Math.abs(bottom.centerX() - top.centerX()) > 25) continue;
                Rect found = null;
                int li = -1, ri = -1;
                for (int l = 0; l < vert.size() && found == null; l++) {
                    if (usedV[l]) continue;
                    Rect left = vert.get(l);
                    if (left.centerX() >= top.centerX() || left.centerY() <= top.bottom
                            || left.centerY() >= bottom.top) continue;
                    for (int r = 0; r < vert.size(); r++) {
                        if (r == l || usedV[r]) continue;
                        Rect right = vert.get(r);
                        if (right.centerX() <= top.centerX() || Math.abs(right.centerY() - left.centerY()) > 25) continue;
                        // il segno in alto deve stare a metà tra sinistra e destra
                        int mid = (left.left + right.right) / 2;
                        if (Math.abs(mid - top.centerX()) > 25) continue;
                        int midY = (top.top + bottom.bottom) / 2;
                        if (Math.abs(midY - left.centerY()) > 25) continue;
                        found = new Rect(left.left, top.top, right.right, bottom.bottom);
                        li = l;
                        ri = r;
                        break;
                    }
                }
                if (found != null && found.width() >= 50 && found.height() >= 50) {
                    usedH[t] = usedH[bo] = true;
                    usedV[li] = usedV[ri] = true;
                    int inset = Math.max(Math.max(top.height(), bottom.height()),
                            Math.max(vert.get(li).width(), vert.get(ri).width())) + 4;
                    Rect inner = new Rect(found);
                    inner.inset(inset, inset);
                    Bitmap backdrop = null;
                    if (inner.width() > 10 && inner.height() > 10) {
                        backdrop = Bitmap.createBitmap(b, inner.left, inner.top, inner.width(), inner.height());
                    }
                    results.add(new Result(found, w, h, backdrop));
                    break;
                }
            }
        }
        b.recycle();
        return results;
    }
}
