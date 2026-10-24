package com.clean.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a small dot-matrix picture with the same rounded-square dots as the Doto clock.
 * The pictures are 11 x 9 grids of '#' (lit) and '.' (dark), drawn for this launcher.
 */
public class DotMatrix extends View {

    static final int COLS = 11, ROWS = 9;
    private static final Map<String, String[]> ICONS = new HashMap<>();

    private static void put(String name, String... rows) {
        ICONS.put(name, rows);
    }

    static {
        put("sun",
                ".....#.....",
                "..#.....#..",
                "....###....",
                "...#####...",
                "#..#####..#",
                "...#####...",
                "....###....",
                "..#.....#..",
                ".....#.....");
        put("moon",
                "..####.....",
                ".###.......",
                ".##.....#..",
                "###........",
                "###........",
                "###......#.",
                ".##........",
                ".###.......",
                "..####.....");
        put("partly_day",
                ".......###.",
                "......#####",
                "......#####",
                ".......####",
                "...###..##.",
                "..#####....",
                ".#######...",
                "#########..",
                ".#######...");
        put("partly_night",
                "........##.",
                ".......#...",
                ".......#...",
                "........##.",
                "...###.....",
                "..#####....",
                ".#######...",
                "#########..",
                ".#######...");
        put("cloud",
                "...........",
                "....###....",
                "...#####...",
                ".#########.",
                "###########",
                "###########",
                ".#########.",
                "...........",
                "...........");
        put("fog",
                "...........",
                ".#########.",
                "...........",
                "###########",
                "...........",
                ".#########.",
                "...........",
                "...#####...",
                "...........");
        put("drizzle",
                "....###....",
                "...#####...",
                ".#########.",
                "###########",
                ".#########.",
                "...........",
                "..#.....#..",
                ".....#.....",
                "...........");
        put("rain",
                "....###....",
                "...#####...",
                ".#########.",
                "###########",
                ".#########.",
                "...........",
                ".#..#..#..#",
                "#..#..#..#.",
                "...........");
        put("snow",
                "....###....",
                "...#####...",
                ".#########.",
                "###########",
                ".#########.",
                "...........",
                ".#...#...#.",
                "...#...#...",
                ".#...#...#.");
        put("thunder",
                "....###....",
                "...#####...",
                ".#########.",
                "###########",
                ".###.######",
                "....##.....",
                "...##......",
                "....##.....",
                ".....#.....");
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private final float dot, gap, radius;
    private String[] rows;

    /** A picture whose whole width is about {@code width} pixels. */
    DotMatrix(Context context, float width) {
        this(context, width / (COLS + (COLS - 1) / 3f), width / (COLS + (COLS - 1) / 3f) / 3f);
    }

    private DotMatrix(Context context, float dot, float gap) {
        super(context);
        this.dot = dot;
        this.gap = gap;
        radius = dot * 0.22f;
        paint.setColor(context.getColor(R.color.text));
    }

    /** Weather.icon() names ("rain_night") as this view's pictures. */
    static String forWeather(String icon) {
        if (icon.startsWith("clear")) return icon.endsWith("day") ? "sun" : "moon";
        if (icon.startsWith("partly_cloudy")) return icon.endsWith("day") ? "partly_day" : "partly_night";
        if (icon.startsWith("fog")) return "fog";
        if (icon.startsWith("rain") || icon.startsWith("sleet")) return "rain";
        if (icon.startsWith("snow")) return "snow";
        if (icon.startsWith("thunder")) return "thunder";
        return "cloud";
    }

    /** Shows the named picture; unknown names draw nothing. */
    void show(String name) {
        rows = ICONS.get(name);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Math.round(COLS * (dot + gap) - gap), Math.round(ROWS * (dot + gap) - gap));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rows == null) return;
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                if (rows[y].charAt(x) != '#') continue;
                float left = x * (dot + gap), top = y * (dot + gap);
                cell.set(left, top, left + dot, top + dot);
                canvas.drawRoundRect(cell, radius, radius, paint);
            }
        }
    }
}
