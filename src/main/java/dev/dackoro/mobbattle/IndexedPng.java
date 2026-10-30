package dev.dackoro.mobbattle;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Small palette PNGs for pictures sent to every client (2026-10-16, playtest: "turbo" optimize what the VS pictures weigh
 * and how fast they are made).
 * <ul>
 *   <li>Median cut over a histogram of 5 bits per color channel and 4 bits of alpha (soft edges keep their fade; the
 *   palette has alpha per entry), at most COLORS colors, index 0 = fully transparent. No dithering: renders are flat
 *   shaded blocks.</li>
 *   <li>Our own PNG writer: 8-bit palette, filter None on every row (the best for palette images), Deflater at its
 *   strongest level, tRNS only as long as needed. ImageIO's writer gave bigger files.</li>
 *   <li>{@link #encodePair}: the left picture and its mirror share one quantization (same colors), so a pair costs one
 *   median cut and two deflates.</li>
 *   <li>The histogram and index buffers are reused per thread (pictures are made on several threads at once).</li>
 * </ul>
 */
final class IndexedPng {

    /**
     * Palette size (measured 2026-10-16 over 608 models: 255 colors 384 px 18.4 KB, 128 colors 320 px 14.4 KB per
     * picture). 255 again since the VS pictures are budgeted (VsScreen.MAX_PAIRS): playtests found them pixelated.
     */
    static final int COLORS = 255;
    /** Pixels fainter than this are fully transparent. */
    private static final int MIN_ALPHA = 8;
    private static final int BINS = 1 << 19;
    private static final ThreadLocal<int[]> HIST = ThreadLocal.withInitial(() -> new int[BINS]);
    private static final ThreadLocal<int[]> INDEX = ThreadLocal.withInitial(() -> new int[BINS]);
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private IndexedPng() {
    }

    /** A quantized picture: palette (n entries, 0 = transparent) and one index per pixel. */
    private record Quantized(int w, int h, byte[] r, byte[] g, byte[] b, byte[] a, int n, byte[] pixels) {
    }

    static byte[] encode(BufferedImage img) throws IOException {
        Quantized q = quantize(img);
        return png(q, q.pixels());
    }

    /** {left, mirrored}: one quantization, two PNGs. */
    static byte[][] encodePair(BufferedImage img) throws IOException {
        Quantized q = quantize(img);
        byte[] mirrored = new byte[q.pixels().length];
        for (int y = 0; y < q.h(); y++) {
            int row = y * q.w();
            for (int x = 0; x < q.w(); x++) mirrored[row + x] = q.pixels()[row + q.w() - 1 - x];
        }
        return new byte[][] {png(q, q.pixels()), png(q, mirrored)};
    }

    // ---- quantization ----

    private static Quantized quantize(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] hist = HIST.get();
        Arrays.fill(hist, 0);
        int distinct = 0;
        for (int p : px) {
            if ((p >>> 24) < MIN_ALPHA) continue;
            if (hist[bin(p)]++ == 0) distinct++;
        }
        int[] all = new int[distinct];
        for (int i = 0, k = 0; i < BINS && k < distinct; i++) if (hist[i] > 0) all[k++] = i;
        // median cut: boxes are ranges of one array, sorted in place
        int[] start = new int[255], len = new int[255];
        int boxes = 0;
        if (distinct > 0) {
            start[0] = 0;
            len[0] = distinct;
            boxes = 1;
        }
        while (boxes < COLORS) {
            int pick = -1;
            long best = 0;
            for (int i = 0; i < boxes; i++) {
                if (len[i] < 2) continue;
                long score = (long) widest(all, start[i], len[i])[1] * weight(all, start[i], len[i], hist);
                if (score > best) {
                    pick = i;
                    best = score;
                }
            }
            if (pick < 0) break;
            int cut = split(all, start[pick], len[pick], hist);
            start[boxes] = start[pick] + cut;
            len[boxes] = len[pick] - cut;
            len[pick] = cut;
            boxes++;
        }
        int n = boxes + 1;
        byte[] r = new byte[n], g = new byte[n], b = new byte[n], a = new byte[n];
        int[] indexOf = INDEX.get();
        for (int i = 0; i < boxes; i++) {
            long sr = 0, sg = 0, sb = 0, sa = 0, cnt = 0;
            for (int k = start[i]; k < start[i] + len[i]; k++) {
                int bin = all[k];
                long c = hist[bin];
                sr += (channel(bin, 0) * 8L + 4) * c;
                sg += (channel(bin, 1) * 8L + 4) * c;
                sb += (channel(bin, 2) * 8L + 4) * c;
                sa += (alpha4(bin) * 17L) * c;
                cnt += c;
                indexOf[bin] = i + 1;
            }
            cnt = Math.max(1, cnt);
            r[i + 1] = (byte) (sr / cnt);
            g[i + 1] = (byte) (sg / cnt);
            b[i + 1] = (byte) (sb / cnt);
            a[i + 1] = (byte) Math.max(MIN_ALPHA, sa / cnt);
        }
        byte[] pixels = new byte[w * h];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            pixels[i] = (byte) ((p >>> 24) >= MIN_ALPHA ? indexOf[bin(p)] : 0);
        }
        return new Quantized(w, h, r, g, b, a, n, pixels);
    }

    /** 4 bits alpha, then 5 bits each of red, green, blue. */
    private static int bin(int argb) {
        return ((argb >>> 28) << 15) | (((argb >> 19) & 31) << 10) | (((argb >> 11) & 31) << 5) | ((argb >> 3) & 31);
    }

    private static int alpha4(int bin) {
        return (bin >> 15) & 15;
    }

    /** Channel 0-2 = red, green, blue (0-31); 3 = alpha, scaled to 0-30 so it weighs like a color. */
    private static int channel(int bin, int c) {
        return switch (c) {
            case 0 -> (bin >> 10) & 31;
            case 1 -> (bin >> 5) & 31;
            case 2 -> bin & 31;
            default -> alpha4(bin) * 2;
        };
    }

    private static long weight(int[] all, int from, int count, int[] hist) {
        long s = 0;
        for (int k = from; k < from + count; k++) s += hist[all[k]];
        return s;
    }

    /** {channel, range} of the box's widest channel. */
    private static int[] widest(int[] all, int from, int count) {
        int[] lo = {99, 99, 99, 99}, hi = {-1, -1, -1, -1};
        for (int k = from; k < from + count; k++) {
            int bin = all[k];
            for (int c = 0; c < 4; c++) {
                int v = channel(bin, c);
                if (v < lo[c]) lo[c] = v;
                if (v > hi[c]) hi[c] = v;
            }
        }
        int ch = 0;
        for (int c = 1; c < 4; c++) if (hi[c] - lo[c] > hi[ch] - lo[ch]) ch = c;
        return new int[] {ch, hi[ch] - lo[ch]};
    }

    /**
     * Sorts the box by its widest channel (counting sort: channels are 0-31) and returns where the weighted median
     * cuts it (1 .. count-1).
     */
    private static int split(int[] all, int from, int count, int[] hist) {
        int ch = widest(all, from, count)[0];
        int[] buckets = new int[33];
        for (int k = from; k < from + count; k++) buckets[channel(all[k], ch) + 1]++;
        for (int v = 1; v < 33; v++) buckets[v] += buckets[v - 1];
        int[] sorted = new int[count];
        for (int k = from; k < from + count; k++) sorted[buckets[channel(all[k], ch)]++] = all[k];
        System.arraycopy(sorted, 0, all, from, count);
        long total = weight(all, from, count, hist), run = 0;
        for (int i = 0; i < count - 1; i++) {
            run += hist[all[from + i]];
            if (run * 2 >= total) return i + 1;
        }
        return count - 1;
    }

    // ---- PNG ----

    private static byte[] png(Quantized q, byte[] pixels) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        out.write(SIGNATURE);
        byte[] ihdr = new byte[13];
        put32(ihdr, 0, q.w());
        put32(ihdr, 4, q.h());
        ihdr[8] = 8;     // bit depth
        ihdr[9] = 3;     // palette
        chunk(out, "IHDR", ihdr);
        byte[] plte = new byte[q.n() * 3];
        for (int i = 0; i < q.n(); i++) {
            plte[i * 3] = q.r()[i];
            plte[i * 3 + 1] = q.g()[i];
            plte[i * 3 + 2] = q.b()[i];
        }
        chunk(out, "PLTE", plte);
        int last = 0;   // tRNS up to the last entry that is not opaque
        for (int i = 0; i < q.n(); i++) if ((q.a()[i] & 255) != 255) last = i;
        chunk(out, "tRNS", Arrays.copyOf(q.a(), last + 1));
        byte[] raw = new byte[(q.w() + 1) * q.h()];
        for (int y = 0; y < q.h(); y++) {
            raw[y * (q.w() + 1)] = 0;   // filter None
            System.arraycopy(pixels, y * q.w(), raw, y * (q.w() + 1) + 1, q.w());
        }
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 4);
        byte[] buf = new byte[16 * 1024];
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf));
        deflater.end();
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] len = new byte[4];
        put32(len, 0, data.length);
        out.write(len);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        put32(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void put32(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }
}
