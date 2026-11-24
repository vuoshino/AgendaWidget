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

        Result(Rect widget, int screenW, int screenH) {
            this.widget = widget;
            this.screenW = screenW;
            this.screenH = screenH;
        }
    }

    /** @return la posizione del widget, oppure null se i segni non si trovano. */
    public static Result find(Context c, Uri uri) throws IOException {
        Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri),
                (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();

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
        Rect top = null, bottom = null, left = null, right = null;
        for (Rect r : blobs) {
            if (r.width() > r.height() * 2) {
                if (top == null || r.top < top.top) top = r;
                if (bottom == null || r.bottom > bottom.bottom) bottom = r;
            } else if (r.height() > r.width() * 2) {
                if (left == null || r.left < left.left) left = r;
                if (right == null || r.right > right.right) right = r;
            }
        }
        if (top == null || bottom == null || left == null || right == null || top == bottom || left == right) {
            return null;
        }
        Rect widget = new Rect(left.left, top.top, right.right, bottom.bottom);
        if (widget.width() < 50 || widget.height() < 50) return null;
        return new Result(widget, w, h);
    }
}
