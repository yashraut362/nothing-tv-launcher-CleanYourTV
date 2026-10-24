package com.clean.tvhome;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class HomeActivity extends Activity {

    /** RAIL: the three readouts. GRID: the apps. MENU: the input list expanded inside the rail. */
    private enum Zone { RAIL, GRID, MENU }

    private static final int RAIL_INPUTS = 0, RAIL_WIFI = 1, RAIL_SETTINGS = 2;

    private ScrollView scroller;
    private FrameLayout content;
    private FocusOutline highlight;
    private TextView clock, dateDay, date, wifiLabel;
    private View ruleNetwork, ruleSettings;
    private LinearLayout inputsMenu;
    private RecyclerView grid;
    private View[] railItems, railDots;
    private boolean inputConnected;
    private TextView[][] railTexts;

    private final List<Apps.Entry> apps = new ArrayList<>();
    private final List<InputItem> inputs = new ArrayList<>();
    private final GridAdapter adapter = new GridAdapter();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Zone zone = Zone.GRID;
    private int railIdx, gridIdx, menuIdx;
    private int columns, tileW, tileH, gap;
    private boolean circles;
    private View focusedCard;
    private View pressedCard;
    private ObjectAnimator scrollAnim;
    /** True from onStop until the next onResume: a new intent in between is a return from an app. */
    private boolean wasStopped;
    // Set when Home opens something. Some apps (Play Store) don't cover Home fully, so Home is only
    // paused, never stopped, and wasStopped alone would read their Home press as Home-on-Home.
    private boolean openedSomething;
    private TvInputManager tvInputs;

    private static final class InputItem {
        final String label;
        final Intent intent;
        final boolean connected;

        InputItem(String label, Intent intent, boolean connected) {
            this.label = label;
            this.intent = intent;
            this.connected = connected;
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        scroller = findViewById(R.id.scroller);
        content = findViewById(R.id.content);
        highlight = findViewById(R.id.highlight);
        clock = findViewById(R.id.clock);
        dateDay = findViewById(R.id.date_day);
        date = findViewById(R.id.date);
        wifiLabel = findViewById(R.id.readout_wifi_value);
        ruleNetwork = findViewById(R.id.rule_network);
        ruleSettings = findViewById(R.id.rule_settings);
        inputsMenu = findViewById(R.id.inputs_menu);
        grid = findViewById(R.id.grid);
        railItems = new View[] {
                findViewById(R.id.readout_inputs), findViewById(R.id.readout_wifi), findViewById(R.id.readout_settings)
        };
        railDots = new View[] {findViewById(R.id.inputs_dot), findViewById(R.id.wifi_dot), findViewById(R.id.settings_dot)};
        railTexts = new TextView[][] {
                {findViewById(R.id.readout_inputs_label), findViewById(R.id.readout_inputs_value)},
                {findViewById(R.id.readout_wifi_label), wifiLabel},
                {findViewById(R.id.readout_settings_label), findViewById(R.id.readout_settings_value)}
        };

        circles = "circle".equals(getString(R.string.tile_shape));
        columns = Math.max(2, Math.min(6, getResources().getInteger(circles ? R.integer.circle_columns : R.integer.columns)));
        sizeTiles();

        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);

        tvInputs = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);

        loadWallpaper();
        restoreFocus();
        loadApps();
        revealClock();

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockReceiver, f);
        updateClock();
        updateWifi();
        updateInputsDot();
        releasePress();
        wasStopped = false;
        openedSomething = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(clockReceiver);
        saveFocus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        wasStopped = true;
        closeMenu(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    /** Home pressed while Home is already showing: back to the first app.
     *  Returning from an app (Home was stopped, or Home opened it) keeps the tile you left. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!wasStopped && !openedSomething) {
            closeMenu(false);
            goToFirst();
        }
    }

    // ---- layout ------------------------------------------------------------------------------

    private void sizeTiles() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        gap = Math.round(getResources().getDimension(columns >= 5 ? R.dimen.gutter_dense : R.dimen.gutter));
        int inner = screenW - dim(R.dimen.apps_left) - dim(R.dimen.safe_x);
        tileW = (inner - (columns - 1) * gap) / columns;
        // tileH is the art's height: a 16:9 banner, or a circle a little narrower than its column.
        tileH = circles ? Math.round(tileW * getResources().getFraction(R.fraction.circle_fill, 1, 1)) : tileW * 9 / 16;
    }

    private View makeTile(ViewGroup parent) {
        View tile = LayoutInflater.from(this).inflate(R.layout.tile, parent, false);
        tile.getLayoutParams().width = tileW;
        View card = tile.findViewById(R.id.card);
        card.getLayoutParams().height = tileH;
        card.setClipToOutline(true);
        if (circles) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.width = tileH;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            ((TextView) tile.findViewById(R.id.label)).setGravity(Gravity.CENTER);
        }
        return tile;
    }

    private void bindTile(View tile, Apps.Entry app) {
        ImageView banner = tile.findViewById(R.id.banner);
        TextView fallback = tile.findViewById(R.id.fallback_label);
        View card = tile.findViewById(R.id.card);
        ((TextView) tile.findViewById(R.id.label)).setText(app.label);
        if (circles) {
            bindCircle(card, banner, fallback, app);
        } else if (app.banner != null) {
            banner.setImageDrawable(app.banner);
            banner.setVisibility(View.VISIBLE);
            fallback.setVisibility(View.GONE);
            card.setBackgroundResource(R.drawable.tile_bg);
        } else {
            banner.setImageDrawable(null);
            banner.setVisibility(View.GONE);
            fallback.setText(app.label);
            fallback.setVisibility(View.VISIBLE);
            card.setBackgroundResource(R.drawable.tile_noart_bg);
        }
        setTileFocused(tile, false, false);
    }

    /**
     * A circle holding the app's line glyph (see LineIcons), the same weight and colour for every app.
     * An app with no glyph and no Latin initial shows its name in mono caps instead.
     */
    private void bindCircle(View card, ImageView art, TextView fallback, Apps.Entry app) {
        card.setBackgroundResource(R.drawable.circle_bg);
        if (app.glyph == null) {
            art.setImageDrawable(null);
            art.setVisibility(View.GONE);
            fallback.setText(app.label);
            fallback.setVisibility(View.VISIBLE);
            return;
        }
        int inset = Math.round(tileH * (1f - GLYPH_SHARE) / 2f);
        art.setPadding(inset, inset, inset, inset);
        art.setScaleType(ImageView.ScaleType.FIT_XY);
        art.setImageDrawable(new LineIcons.GlyphDrawable(app.glyph, getColor(R.color.text)));
        art.setVisibility(View.VISIBLE);
        fallback.setVisibility(View.GONE);
    }

    /** The glyph's 192 grid as a share of the circle; Lawnicons draws inside about 150 of it. */
    private static final float GLYPH_SHARE = 0.62f;

    /** Focus inverts a circle, as it does a rail readout: white disc, dark line. */
    private void styleCircle(View tile, boolean focused) {
        View card = tile.findViewById(R.id.card);
        card.setBackgroundResource(focused ? R.drawable.circle_focus_bg : R.drawable.circle_bg);
        int ink = getColor(focused ? R.color.ground : R.color.text);
        Drawable d = ((ImageView) tile.findViewById(R.id.banner)).getDrawable();
        if (d instanceof LineIcons.GlyphDrawable) ((LineIcons.GlyphDrawable) d).setColor(ink);
        ((TextView) tile.findViewById(R.id.fallback_label)).setTextColor(ink);
    }

    private void loadWallpaper() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        for (String name : new String[] {"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
            File f = new File(dir, name);
            if (!f.isFile()) continue;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            int maxW = getResources().getDisplayMetrics().widthPixels;
            while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(f.getPath(), o);
            if (bitmap == null) return;
            ImageView wallpaper = findViewById(R.id.wallpaper);
            int level = Math.round(255 * getResources().getInteger(R.integer.wallpaper_brightness) / 100f);
            wallpaper.setColorFilter(Color.rgb(level, level, level), PorterDuff.Mode.MULTIPLY);
            wallpaper.setImageBitmap(bitmap);
            wallpaper.setVisibility(View.VISIBLE);
            return;
        }
    }

    // ---- apps --------------------------------------------------------------------------------

    private void loadApps() {
        Context app = getApplicationContext();
        new Thread(() -> {
            List<Apps.Entry> all = Apps.load(app);
            runOnUiThread(() -> applyApps(all));
        }, "load-apps").start();
    }

    /** Pinned apps first, in config order, then everything else alphabetically. */
    private void applyApps(List<Apps.Entry> all) {
        apps.clear();
        for (String pkg : getResources().getStringArray(R.array.dock_apps)) {
            for (Apps.Entry e : all) {
                if (e.pkg.equals(pkg) && !apps.contains(e)) apps.add(e);
            }
        }
        for (Apps.Entry e : all) {
            if (!apps.contains(e)) apps.add(e);
        }

        focusedCard = null;
        adapter.notifyDataSetChanged();
        // Inside the ScrollView the grid only measures its first row; give it the exact height.
        int rows = (apps.size() + columns - 1) / columns;
        int tileTotal = tileH + dim(R.dimen.caption_top) + dim(R.dimen.caption_height);
        grid.getLayoutParams().height = rows == 0 ? 0 : rows * tileTotal + (rows - 1) * dim(R.dimen.row_gap);
        grid.requestLayout();

        if (zone == Zone.GRID && apps.isEmpty()) zone = Zone.RAIL;
        if (zone == Zone.MENU) zone = Zone.RAIL;
        gridIdx = clamp(gridIdx, 0, apps.size() - 1);
        content.post(() -> refreshFocus(false));
    }

    private final class GridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(makeTile(parent)) {};
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            bindTile(holder.itemView, apps.get(position));
            // Grid columns are equal; nudge each tile so the gutters come out even.
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            lp.leftMargin = (position % columns) * gap / columns;
            lp.bottomMargin = position / columns < (apps.size() - 1) / columns ? dim(R.dimen.row_gap) : 0;
            holder.itemView.setLayoutParams(lp);
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    private void launch(Apps.Entry app) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(i);
    }

    private void start(Intent i) {
        try {
            startActivity(i);
            openedSomething = true;
        } catch (Exception e) {
            Toast.makeText(this, "Can't open that here", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- rail: time and readouts -------------------------------------------------------------

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH" : "h", now) + "\n"
                + DateFormat.format("mm", now));
        dateDay.setText(DateFormat.format("EEEE", now));
        date.setText(DateFormat.format("d MMMM", now));
    }

    /** The one playful moment: the clock's dots settle into place, once, on launch. */
    private void revealClock() {
        clock.setAlpha(0f);
        clock.setLetterSpacing(0.3f);
        clock.animate().alpha(1f).setDuration(Motion.REVEAL_MS).setInterpolator(Motion.standard()).start();
        ObjectAnimator spacing = ObjectAnimator.ofFloat(clock, "letterSpacing", 0.3f, 0.02f);
        spacing.setDuration(Motion.REVEAL_MS);
        spacing.setInterpolator(Motion.standard());
        spacing.start();
    }

    private void updateWifi() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network net = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = net == null ? null : cm.getNetworkCapabilities(net);
        String label;
        if (caps == null) {
            label = "Offline";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            label = "Ethernet";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            label = wifiName(cm);
        } else {
            label = "Connected";
        }
        wifiLabel.setText(label);
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission") // allowed on Android 9 without location; guarded for newer
    private String wifiName(ConnectivityManager cm) {
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        String ssid = info == null ? null : info.getSSID();
        if (unknown(ssid) && info != null && info.getNetworkId() >= 0) {
            // With location off, Android 9 hides the SSID from WifiInfo but still lists the
            // saved networks; the connected one is found by its network id.
            // Android 10 and later need fine location for this list; there it reads "Wi-Fi".
            try {
                List<WifiConfiguration> saved = wm.getConfiguredNetworks();
                if (saved != null) {
                    for (WifiConfiguration c : saved) {
                        if (c.networkId == info.getNetworkId()) { ssid = c.SSID; break; }
                    }
                }
            } catch (SecurityException ignored) {
                // no permission: fall through to the generic label
            }
        }
        if (unknown(ssid)) {
            NetworkInfo ni = cm.getActiveNetworkInfo();
            ssid = ni == null ? null : ni.getExtraInfo();
        }
        if (unknown(ssid)) return "Wi-Fi";
        if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private static boolean unknown(String ssid) {
        return ssid == null || ssid.isEmpty() || ssid.contains("unknown ssid");
    }

    /** The Input readout shows the connected source and a red dot when a device on it is on. */
    private void updateInputsDot() {
        String connected = TvSources.connectedLabel(this, tvInputs);
        inputConnected = connected != null;
        railTexts[RAIL_INPUTS][1].setText(connected != null ? connected : getString(R.string.inputs));
        styleRailItem(RAIL_INPUTS, zone == Zone.RAIL && railIdx == RAIL_INPUTS);
    }

    private final BroadcastReceiver clockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            runOnUiThread(HomeActivity.this::updateWifi);
        }

        @Override
        public void onLost(Network network) {
            runOnUiThread(HomeActivity.this::updateWifi);
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            updateInputsDot();
        }

        @Override
        public void onInputAdded(String inputId) {
            updateInputsDot();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsDot();
        }
    };

    // ---- input list, expanded in place -------------------------------------------------------

    private void openMenu() {
        inputs.clear();
        if (tvInputs != null) {
            List<TvInputInfo> list = new ArrayList<>(tvInputs.getTvInputList());
            list.sort((a, b) -> {
                int ta = a.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                int tb = b.getType() == TvInputInfo.TYPE_HDMI ? 0 : 1;
                return ta != tb ? ta - tb : TvSources.label(this, a).compareToIgnoreCase(TvSources.label(this, b));
            });
            for (TvInputInfo info : list) {
                if (!TvSources.isSource(info)) continue;
                Intent view = new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(info.getId()));
                inputs.add(new InputItem(TvSources.label(this, info), view, TvSources.isConnected(tvInputs, info.getId())));
            }
        }
        Intent live = getPackageManager().getLeanbackLaunchIntentForPackage("com.android.tv");
        if (live != null) inputs.add(new InputItem(getString(R.string.live_tv), live, false));
        if (inputs.isEmpty()) {
            start(new Intent(Settings.ACTION_SETTINGS));
            return;
        }

        inputsMenu.removeAllViews();
        int pad = dim(R.dimen.rail_item_pad);
        for (InputItem item : inputs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, 0, pad, 0);
            TextView name = new TextView(this, null, 0, R.style.Label);
            name.setText(item.label);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // The dot marks the focused row (as in the mockup) and any input with a live device.
            View dot = new View(this);
            dot.setBackgroundResource(R.drawable.dot);
            dot.setVisibility(item.connected ? View.VISIBLE : View.INVISIBLE);
            row.addView(dot, new LinearLayout.LayoutParams(dim(R.dimen.dot), dim(R.dimen.dot)));
            row.setTag(item.connected);
            inputsMenu.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dim(R.dimen.option_row)));
        }
        // The list takes the other readouts' room while it is open.
        railTexts[RAIL_INPUTS][1].setVisibility(View.GONE);
        for (View v : new View[] {ruleNetwork, railItems[RAIL_WIFI], ruleSettings, railItems[RAIL_SETTINGS]}) {
            v.setVisibility(View.GONE);
        }
        inputsMenu.setAlpha(0f);
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.animate().alpha(1f).setDuration(Motion.PANEL_MS).setInterpolator(Motion.standard()).start();
        scroller.animate().alpha(0.35f).setDuration(Motion.PANEL_MS).start();
        zone = Zone.MENU;
        menuIdx = 0;
        inputsMenu.post(() -> refreshFocus(true));
    }

    private void closeMenu(boolean animate) {
        if (inputsMenu.getVisibility() != View.VISIBLE) return;
        inputsMenu.animate().cancel();
        inputsMenu.setVisibility(View.GONE);
        railTexts[RAIL_INPUTS][1].setVisibility(View.VISIBLE);
        for (View v : new View[] {ruleNetwork, railItems[RAIL_WIFI], ruleSettings, railItems[RAIL_SETTINGS]}) {
            v.setVisibility(View.VISIBLE);
        }
        scroller.animate().cancel();
        scroller.setAlpha(1f);
        if (zone == Zone.MENU) {
            zone = Zone.RAIL;
            railIdx = RAIL_INPUTS;
            refreshFocus(animate);
        }
    }

    private void styleMenuRow(int idx, boolean focused) {
        if (idx >= inputsMenu.getChildCount()) return;
        ViewGroup row = (ViewGroup) inputsMenu.getChildAt(idx);
        if (focused) row.setBackgroundResource(R.drawable.option_focus_bg);
        else row.setBackgroundColor(Color.TRANSPARENT);
        int color = getColor(focused ? R.color.ground : R.color.text_2);
        boolean connected = Boolean.TRUE.equals(row.getTag());
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child instanceof TextView) ((TextView) child).setTextColor(color);
            else child.setVisibility(focused || connected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // ---- focus & keys ------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (isSelectKey(code)) {
                if (pressedCard != null) {
                    releasePress();
                    activate();
                }
                return true;
            }
            return isNavKey(code) || super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9) {
            int n = code - KeyEvent.KEYCODE_0;
            if (event.getRepeatCount() == 0 && n <= apps.size()) launch(apps.get(n - 1));
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getRepeatCount() == 0) press();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (event.getRepeatCount() == 0) back();
                return true;
            case KeyEvent.KEYCODE_MENU:
                // One physical button for the TV's physical sockets.
                if (event.getRepeatCount() == 0 && zone != Zone.MENU) {
                    railIdx = RAIL_INPUTS;
                    openMenu();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private static boolean isSelectKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static boolean isNavKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU
                || (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9);
    }

    /** Select held down: the focused tile dips until release. Rail items act on release too. */
    private void press() {
        View card = currentCard();
        pressedCard = card != null ? card : content;
        if (card != null) {
            card.animate().scaleX(Motion.PRESS_SCALE).scaleY(Motion.PRESS_SCALE)
                    .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.standard()).start();
        }
    }

    private void releasePress() {
        if (pressedCard != null && pressedCard != content && pressedCard == focusedCard) {
            pressedCard.animate().scaleX(Motion.FOCUS_SCALE).scaleY(Motion.FOCUS_SCALE)
                    .setDuration(Motion.FOCUS_MS).setInterpolator(Motion.standard()).start();
        }
        pressedCard = null;
    }

    /** Moves exactly one step. Edges stop; Left from the first column enters the rail. */
    private void move(int dRow, int dCol) {
        Zone before = zone;
        int beforeIdx = currentIdx();
        switch (zone) {
            case RAIL:
                if (dRow != 0) railIdx = clamp(railIdx + dRow, 0, railItems.length - 1);
                else if (dCol > 0 && !apps.isEmpty()) zone = Zone.GRID;
                break;
            case GRID: {
                int row = gridIdx / columns, col = gridIdx % columns;
                if (dCol < 0) {
                    if (col > 0) gridIdx--;
                    else zone = Zone.RAIL;
                } else if (dCol > 0 && col < columns - 1 && gridIdx + 1 < apps.size()) gridIdx++;
                else if (dRow < 0 && row > 0) gridIdx -= columns;
                else if (dRow > 0 && (row + 1) * columns < apps.size()) {
                    gridIdx = Math.min(gridIdx + columns, apps.size() - 1);
                }
                break;
            }
            case MENU:
                if (dRow != 0) menuIdx = clamp(menuIdx + dRow, 0, inputs.size() - 1);
                else closeMenu(true);
                break;
        }
        if (zone != before || currentIdx() != beforeIdx) {
            releasePress();
            refreshFocus(true);
        }
    }

    private int currentIdx() {
        switch (zone) {
            case RAIL: return railIdx;
            case GRID: return gridIdx;
            default: return menuIdx;
        }
    }

    private void activate() {
        switch (zone) {
            case RAIL:
                if (railIdx == RAIL_INPUTS) openMenu();
                else if (railIdx == RAIL_WIFI) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                else start(new Intent(Settings.ACTION_SETTINGS));
                break;
            case GRID:
                if (gridIdx < apps.size()) launch(apps.get(gridIdx));
                break;
            case MENU:
                if (menuIdx < inputs.size()) {
                    Intent i = inputs.get(menuIdx).intent;
                    closeMenu(false);
                    start(i);
                }
                break;
        }
    }

    /** Back closes the input list, otherwise returns to the first app. Back never leaves Home. */
    private void back() {
        if (zone == Zone.MENU) closeMenu(true);
        else if (zone != Zone.GRID || gridIdx != 0) goToFirst();
    }

    private void goToFirst() {
        if (apps.isEmpty()) {
            zone = Zone.RAIL;
        } else {
            zone = Zone.GRID;
            gridIdx = 0;
        }
        releasePress();
        refreshFocus(true);
    }

    private View currentCard() {
        if (zone != Zone.GRID) return null;
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        View tile = lm == null ? null : lm.findViewByPosition(gridIdx);
        return tile == null ? null : tile.findViewById(R.id.card);
    }

    /** Applies the focus state: one outline, one scaled tile with its name, or one inverted rail item. */
    private void refreshFocus(boolean animate) {
        View card = currentCard();

        // While the input list is open only its focused row is inverted, not the whole item.
        for (int i = 0; i < railItems.length; i++) styleRailItem(i, zone == Zone.RAIL && i == railIdx);
        for (int i = 0; i < inputs.size(); i++) styleMenuRow(i, zone == Zone.MENU && i == menuIdx);

        if (focusedCard != null && focusedCard != card) {
            setTileFocused((View) focusedCard.getParent(), false, animate);
        }
        if (card != null) setTileFocused((View) card.getParent(), true, animate);
        // Banners get the outline; a circle needs none, since its inversion already marks focus.
        if (card != null && !circles) {
            RectF r = boundsInContent(card);
            float grow = (Motion.FOCUS_SCALE - 1f) / 2f;
            r.inset(-(r.width() * grow + dim(R.dimen.focus_offset)), -(r.height() * grow + dim(R.dimen.focus_offset)));
            highlight.moveTo(r, animate);
        } else {
            highlight.hide();
        }
        focusedCard = card;

        // Scroll by whole rows: only when the focused tile would not fit on the first screen.
        int scrollTarget = 0;
        if (card != null) {
            RectF bounds = boundsInContent((View) card.getParent());
            int viewport = scroller.getHeight() - scroller.getPaddingTop() - scroller.getPaddingBottom();
            if (bounds.bottom > viewport) scrollTarget = Math.round(bounds.top);
        }
        scrollTo(Math.max(0, scrollTarget), animate);
    }

    private void setTileFocused(View tile, boolean focused, boolean animate) {
        if (circles) styleCircle(tile, focused);
        View card = tile.findViewById(R.id.card);
        View label = tile.findViewById(R.id.label);
        float scale = focused ? Motion.FOCUS_SCALE : 1f;
        if (animate) {
            card.animate().scaleX(scale).scaleY(scale).setDuration(Motion.FOCUS_MS)
                    .setInterpolator(Motion.standard()).start();
            label.animate().alpha(focused ? 1f : restingNameAlpha()).setDuration(Motion.FOCUS_MS).start();
        } else {
            card.animate().cancel();
            label.animate().cancel();
            card.setScaleX(scale);
            card.setScaleY(scale);
            label.setAlpha(focused ? 1f : restingNameAlpha());
        }
    }

    /** Circles carry no wordmark, so every name shows, dimmed until focused; banners name only the focused app. */
    private float restingNameAlpha() {
        return circles ? 0.5f : 0f;
    }

    /** The focused readout inverts and carries the red dot, like a row of the input list. */
    private void styleRailItem(int i, boolean focused) {
        if (focused) railItems[i].setBackgroundResource(R.drawable.rail_focus_bg);
        else railItems[i].setBackgroundColor(Color.TRANSPARENT);
        railTexts[i][0].setTextColor(getColor(focused ? R.color.ground : R.color.text_3));
        railTexts[i][1].setTextColor(getColor(focused ? R.color.ground : R.color.text));
        boolean listOpen = inputsMenu.getVisibility() == View.VISIBLE;
        boolean dot = focused || (i == RAIL_INPUTS && inputConnected && !listOpen);
        railDots[i].setVisibility(dot ? View.VISIBLE : View.INVISIBLE);
    }

    private void scrollTo(int y, boolean animate) {
        if (scrollAnim != null) scrollAnim.cancel();
        if (!animate) {
            scroller.scrollTo(0, y);
            return;
        }
        if (scroller.getScrollY() == y) return;
        scrollAnim = ObjectAnimator.ofInt(scroller, "scrollY", y);
        scrollAnim.setDuration(Motion.ROW_MS);
        scrollAnim.setInterpolator(Motion.standard());
        scrollAnim.start();
    }

    private RectF boundsInContent(View v) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != content) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
            if (cur != content) {
                x -= cur.getScrollX();
                y -= cur.getScrollY();
            }
        }
        return new RectF(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private void saveFocus() {
        prefs().edit()
                .putString("zone", (zone == Zone.MENU ? Zone.RAIL : zone).name())
                .putInt("rail", railIdx).putInt("grid", gridIdx)
                .apply();
    }

    private void restoreFocus() {
        SharedPreferences p = prefs();
        try {
            zone = Zone.valueOf(p.getString("zone", Zone.GRID.name()));
        } catch (IllegalArgumentException e) {
            zone = Zone.GRID; // a zone name from the earlier layout
        }
        railIdx = clamp(p.getInt("rail", 0), 0, railItems.length - 1);
        gridIdx = p.getInt("grid", 0);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("focus", MODE_PRIVATE);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private int dim(int res) {
        return Math.round(getResources().getDimension(res));
    }

    private static int clamp(int v, int lo, int hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }
}
