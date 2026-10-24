package com.clean.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.graphics.PathParser;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One monochrome line glyph per app, from Lawnicons (assets/lawnicons, Apache-2.0), so every app
 * reads the same way whatever its own icon looks like. tools/lawnicons.py builds the two assets.
 *
 * An app is matched, in order, by: a TV alias for its package, its exact activity, its package,
 * its name, a word in its name (Gallery, Settings, Player...), and finally its initial letter.
 */
final class LineIcons {

    private LineIcons() {}

    /** A glyph's strokes, in the units of its viewBox (192 for Lawnicons). */
    static final class Glyph {
        final float left, top, size;
        final Path[] paths;
        final float[] widths;
        final Paint.Cap[] caps;
        final Paint.Join[] joins;

        Glyph(float left, float top, float size, int n) {
            this.left = left;
            this.top = top;
            this.size = size;
            paths = new Path[n];
            widths = new float[n];
            caps = new Paint.Cap[n];
            joins = new Paint.Join[n];
        }
    }

    private static final String CACHE = "glyphs.txt";

    /**
     * Finds a glyph for every app that has one; runs on the loader thread. Scanning the two assets
     * takes over a second on a 1 GB TV, so the result is kept in a file keyed by the installed apps
     * and the launcher's own install time, and the scan runs again only when either changes.
     */
    static void attach(Context context, List<Apps.Entry> apps) {
        long start = SystemClock.elapsedRealtime();
        String key = cacheKey(context, apps);
        boolean cached = readCache(context, key, apps);
        if (!cached) scan(context, key, apps);
        Log.i("LineIcons", apps.size() + " apps " + (cached ? "from cache" : "scanned") + " in "
                + (SystemClock.elapsedRealtime() - start) + " ms");
    }

    private static void scan(Context context, String cacheKey, List<Apps.Entry> apps) {
        Set<String> wanted = new HashSet<>();
        for (Apps.Entry e : apps) {
            wanted.add("a:" + e.pkg);
            wanted.add("c:" + e.component.getPackageName() + "/" + e.component.getClassName());
            wanted.add("p:" + e.pkg);
            wanted.add("n:" + norm(e.label));
        }
        Map<String, String> found = new HashMap<>();
        Map<String, String> words = new LinkedHashMap<>();
        try (BufferedReader r = open(context, "lawnicons/map.txt")) {
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                String key = line.substring(0, tab);
                if (key.startsWith("k:")) words.put(key.substring(2), line.substring(tab + 1));
                else if (wanted.contains(key)) found.put(key, line.substring(tab + 1));
            }
        } catch (IOException e) {
            return; // no glyphs: the circles show the apps' names instead
        }

        Map<Apps.Entry, String> chosen = new HashMap<>();
        Set<String> needed = new HashSet<>();
        for (Apps.Entry e : apps) {
            String name = first(found.get("a:" + e.pkg),
                    found.get("c:" + e.component.getPackageName() + "/" + e.component.getClassName()),
                    found.get("p:" + e.pkg),
                    found.get("n:" + norm(e.label)),
                    byWord(e.label, words),
                    letter(e.label));
            if (name != null) {
                chosen.put(e, name);
                needed.add(name);
            }
        }

        Map<String, String> lines = new HashMap<>();
        try (BufferedReader r = open(context, "lawnicons/icons.txt")) {
            for (String line; (line = r.readLine()) != null && lines.size() < needed.size(); ) {
                int tab = line.indexOf('\t');
                if (tab > 0 && needed.contains(line.substring(0, tab))) lines.put(line.substring(0, tab), line);
            }
        } catch (IOException e) {
            return;
        }
        StringBuilder cache = new StringBuilder(cacheKey).append('\n');
        for (Apps.Entry e : apps) {
            String line = lines.get(chosen.get(e));
            if (line == null) continue;
            e.glyph = parse(line);
            cache.append(e.component.flattenToString()).append('\t').append(line).append('\n');
        }
        try (FileOutputStream out = context.openFileOutput(CACHE, Context.MODE_PRIVATE)) {
            out.write(cache.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // no cache: the next start scans again
        }
    }

    /** True when the cache was written for exactly these apps and this build. */
    private static boolean readCache(Context context, String key, List<Apps.Entry> apps) {
        Map<String, Apps.Entry> byComponent = new HashMap<>();
        for (Apps.Entry e : apps) byComponent.put(e.component.flattenToString(), e);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.openFileInput(CACHE), StandardCharsets.UTF_8))) {
            if (!key.equals(r.readLine())) return false;
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                Apps.Entry e = tab > 0 ? byComponent.get(line.substring(0, tab)) : null;
                if (e != null) e.glyph = parse(line.substring(tab + 1));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String cacheKey(Context context, List<Apps.Entry> apps) {
        long built = 0;
        try {
            built = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
            // our own package is always there
        }
        StringBuilder sb = new StringBuilder();
        for (Apps.Entry e : apps) sb.append(e.component.flattenToString()).append('=').append(e.label).append(';');
        return built + ":" + Integer.toHexString(sb.toString().hashCode()) + ":" + apps.size();
    }

    /** "name \t x y w h \t width cap join pathData \t ..." */
    private static Glyph parse(String line) {
        String[] f = line.split("\t");
        if (f.length < 3) return null;
        String[] box = f[1].split(" ");
        try {
            float w = Float.parseFloat(box[2]), h = Float.parseFloat(box[3]);
            float size = Math.max(w, h);
            Glyph g = new Glyph(Float.parseFloat(box[0]) - (size - w) / 2f,
                    Float.parseFloat(box[1]) - (size - h) / 2f, size, f.length - 2);
            for (int i = 2; i < f.length; i++) {
                String[] s = f[i].split(" ", 4);
                g.widths[i - 2] = Float.parseFloat(s[0]);
                g.caps[i - 2] = s[1].equals("r") ? Paint.Cap.ROUND : s[1].equals("s") ? Paint.Cap.SQUARE : Paint.Cap.BUTT;
                g.joins[i - 2] = s[2].equals("r") ? Paint.Join.ROUND : s[2].equals("b") ? Paint.Join.BEVEL : Paint.Join.MITER;
                g.paths[i - 2] = PathParser.createPathFromPathData(s[3]);
            }
            return g;
        } catch (RuntimeException e) {
            return null; // a glyph we can't read: that app shows its initial
        }
    }

    private static String byWord(String label, Map<String, String> words) {
        String joined = norm(label);
        Set<String> parts = new HashSet<>();
        for (String p : label.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) parts.add(p);
        for (Map.Entry<String, String> w : words.entrySet()) {
            if (joined.equals(w.getKey()) || parts.contains(w.getKey())) return w.getValue();
        }
        return null;
    }

    private static String letter(String label) {
        for (char ch : label.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch >= 'a' && ch <= 'z') return "generic_alphabet_" + ch;
        }
        return null;
    }

    private static String first(String... options) {
        for (String o : options) if (o != null) return o;
        return null;
    }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static BufferedReader open(Context context, String asset) throws IOException {
        return new BufferedReader(new InputStreamReader(context.getAssets().open(asset), StandardCharsets.UTF_8), 32768);
    }

    /** Draws a glyph's strokes, scaled to fill the bounds, in one colour. */
    static final class GlyphDrawable extends Drawable {
        private final Glyph glyph;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlyphDrawable(Glyph glyph, int color) {
            this.glyph = glyph;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(color);
        }

        void setColor(int color) {
            if (paint.getColor() == color) return;
            paint.setColor(color);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float side = Math.min(b.width(), b.height());
            float scale = side / glyph.size;
            canvas.save();
            canvas.translate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f);
            canvas.scale(scale, scale);
            canvas.translate(-glyph.left, -glyph.top);
            for (int i = 0; i < glyph.paths.length; i++) {
                paint.setStrokeWidth(glyph.widths[i]);
                paint.setStrokeCap(glyph.caps[i]);
                paint.setStrokeJoin(glyph.joins[i]);
                canvas.drawPath(glyph.paths[i], paint);
            }
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
