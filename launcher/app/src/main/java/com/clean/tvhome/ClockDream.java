package com.clean.tvhome;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The screensaver, fixed in place. Left half: the rail's dot-matrix clock, the red weekday and
 * the date, the day as 24 dots (past hours lit, the current one red, pulsing each second) and the
 * input with a device switched on. Right half: the weather now and for the week.
 *
 * It redraws on the minute tick, plus the one small dot each second. Weather icons come from the
 * Sanyaas set (drawable/sanyaas_*) when present, otherwise from DotMatrix's own pictures.
 * Any key wakes the TV back to whatever was on screen.
 */
public class ClockDream extends DreamService {

    private static final int HOURS = 24;

    private TextView clock, day, date, inputValue, temp, place, range;
    private View root, inputLine, weather, rule;
    private FrameLayout nowIcon;
    private LinearLayout week;
    private final View[] hourDots = new View[HOURS];
    private int currentHour = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dreaming;
    private TvInputManager tvInputs;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            update();
            Weather.refreshIfStale(ClockDream.this, ClockDream.this::showWeather);
        }
    };

    /** Dims the current hour's dot once a second, on the second. */
    private final Runnable pulse = new Runnable() {
        @Override
        public void run() {
            if (!dreaming) return;
            View dot = currentHour >= 0 ? hourDots[currentHour] : null;
            if (dot != null) {
                dot.animate().alpha(0.45f).setDuration(120).setInterpolator(Motion.standard())
                        .withEndAction(() -> dot.animate().alpha(1f).setDuration(380)
                                .setInterpolator(Motion.standard()).start())
                        .start();
            }
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000);
        }
    };

    private final TvInputManager.TvInputCallback inputChanged = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            updateInput();
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(R.layout.dream);
        root = findViewById(R.id.dream_root);
        clock = findViewById(R.id.dream_clock);
        day = findViewById(R.id.dream_day);
        date = findViewById(R.id.dream_date);
        inputLine = findViewById(R.id.dream_input);
        inputValue = findViewById(R.id.dream_input_value);
        weather = findViewById(R.id.dream_weather);
        rule = findViewById(R.id.dream_rule);
        nowIcon = findViewById(R.id.dream_now_icon);
        temp = findViewById(R.id.dream_temp);
        place = findViewById(R.id.dream_place);
        range = findViewById(R.id.dream_range);
        week = findViewById(R.id.dream_week);

        LinearLayout dots = findViewById(R.id.dream_dots);
        int size = dim(R.dimen.dream_hour_dot);
        int gap = dim(R.dimen.dream_hour_gap);
        for (int h = 0; h < HOURS; h++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (h > 0) lp.leftMargin = gap;
            // Rounded squares like the clock's own dots; a circle this small renders flat-topped.
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(size * 0.22f);
            dot.setBackground(shape);
            dots.addView(dot, lp);
            hourDots[h] = dot;
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        tvInputs = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tvInputs != null) tvInputs.registerCallback(inputChanged, handler);

        update();
        updateInput();
        showWeather(Weather.cached(this));
        Weather.refreshIfStale(this, this::showWeather);
        handler.postDelayed(pulse, 1000 - System.currentTimeMillis() % 1000);

        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Motion.REVEAL_MS).setInterpolator(Motion.standard()).start();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        dreaming = false;
        unregisterReceiver(tick);
        handler.removeCallbacks(pulse);
        if (tvInputs != null) tvInputs.unregisterCallback(inputChanged);
    }

    // ---- time --------------------------------------------------------------------------------

    private void update() {
        Calendar now = Calendar.getInstance();
        // Stacked like the rail's clock: Doto's colon is a pair of crosses, not dots.
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH" : "h", now) + "\n"
                + DateFormat.format("mm", now));
        day.setText(DateFormat.format("EEEE", now));
        date.setText(DateFormat.format("d MMMM", now));

        int hour = now.get(Calendar.HOUR_OF_DAY);
        if (hour != currentHour) {
            currentHour = hour;
            for (int h = 0; h < HOURS; h++) {
                int colour = h < hour ? R.color.text : h == hour ? R.color.accent : R.color.hairline_strong;
                ((GradientDrawable) hourDots[h].getBackground()).setColor(getColor(colour));
                hourDots[h].animate().cancel();
                hourDots[h].setAlpha(1f);
            }
        }
    }

    // ---- weather -----------------------------------------------------------------------------

    /** Fills the right half, or hides it (and the time centres) when there is no reading. */
    private void showWeather(Weather.Reading r) {
        boolean visible = r != null;
        weather.setVisibility(visible ? View.VISIBLE : View.GONE);
        rule.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        nowIcon.removeAllViews();
        nowIcon.addView(icon(Weather.icon(r.code, r.day), dim(R.dimen.dream_now_icon), R.color.text),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        temp.setText(Math.round(r.temp) + "°");
        String where = r.place == null || r.place.isEmpty() ? "" : r.place + " · ";
        place.setText(where + Weather.condition(r.code));
        range.setText("H " + Math.round(r.high) + "°  L " + Math.round(r.low) + "°");

        // Seven days from today, by each entry's own date; anything before today is skipped.
        week.removeAllViews();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        String today = iso.format(new Date());
        for (int i = 0; i < r.dates.length && week.getChildCount() < Weather.DAYS; i++) {
            if (r.dates[i].compareTo(today) < 0) continue;
            boolean isToday = r.dates[i].equals(today);
            String name;
            try {
                name = isToday ? getString(R.string.today) : DateFormat.format("EEE", iso.parse(r.dates[i])).toString();
            } catch (ParseException e) {
                name = r.dates[i];
            }
            week.addView(weekRow(name, Weather.icon(r.codes[i], true), r.highs[i], r.lows[i], isToday));
        }
    }

    /** "MON  [icon]  31°  24°": the day, its sky, the high, the low dimmed. */
    private View weekRow(String name, String icon, float high, float low, boolean today) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dim(R.dimen.dream_week_row)));

        row.addView(label(name, today ? R.color.text : R.color.text_2),
                new LinearLayout.LayoutParams(dim(R.dimen.dream_week_day), ViewGroup.LayoutParams.WRAP_CONTENT));

        int size = dim(R.dimen.dream_week_icon);
        row.addView(icon(icon, size, R.color.text_2), new LinearLayout.LayoutParams(size, size));

        TextView hi = label(Math.round(high) + "°", R.color.text);
        hi.setGravity(Gravity.END);
        row.addView(hi, new LinearLayout.LayoutParams(dim(R.dimen.dream_week_temp), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView lo = label(Math.round(low) + "°", R.color.text_3);
        lo.setGravity(Gravity.END);
        row.addView(lo, new LinearLayout.LayoutParams(dim(R.dimen.dream_week_temp), ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView label(String text, int colour) {
        TextView t = new TextView(this, null, 0, R.style.Label);
        t.setText(text);
        t.setTextColor(getColor(colour));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.dream_date));
        return t;
    }

    /** A weather icon: the Sanyaas drawable when the build has it, else the dot-matrix picture. */
    @SuppressLint("DiscouragedApi") // looked up by name so a build without the Sanyaas files still compiles
    private View icon(String name, int size, int colour) {
        int res = getResources().getIdentifier("sanyaas_" + name, "drawable", getPackageName());
        if (res != 0) {
            ImageView v = new ImageView(this);
            v.setImageResource(res);
            v.setImageTintList(ColorStateList.valueOf(getColor(colour)));
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return v;
        }
        DotMatrix m = new DotMatrix(this, size);
        m.show(DotMatrix.forWeather(name));
        return m;
    }

    // ---- input -------------------------------------------------------------------------------

    private void updateInput() {
        String text = TvSources.connectedLabel(this, tvInputs);
        if (text != null) inputValue.setText(text);
        inputLine.setVisibility(text != null ? View.VISIBLE : View.GONE);
    }

    private int dim(int res) {
        return getResources().getDimensionPixelSize(res);
    }
}
