package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Calendar;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;

    /** Built-in wallpapers, switchable from the wallpaper pill in the top bar. */
    private static final String PREFS = "tvhome";
    private static final String KEY_WALLPAPER_INDEX = "wallpaper_index";   // legacy int pref (old builds)
    private static final String KEY_WALLPAPER_KEY = "wallpaper_key";
    private static final String KEY_WALLPAPER_MOTION = "wallpaper_motion";
    private static final String KEY_WALLPAPER_TIME = "wallpaper_time_mode";
    private static final String KEY_WALLPAPER_SCENE = "wallpaper_time_scene";
    /** Time-of-day scenes [scene][band]: four places, each at dawn, day, dusk and night,
     *  auto-switched by the clock. Bands: 0 dawn, 1 day, 2 dusk, 3 night. */
    private static final int[][] TIME_WALLPAPERS = {
            {R.drawable.wallpaper_time_peak_dawn, R.drawable.wallpaper_time_peak_day,
                    R.drawable.wallpaper_time_peak_dusk, R.drawable.wallpaper_time_peak_night},
            {R.drawable.wallpaper_time_forest_dawn, R.drawable.wallpaper_time_forest_day,
                    R.drawable.wallpaper_time_forest_dusk, R.drawable.wallpaper_time_forest_night},
            {R.drawable.wallpaper_time_lake_dawn, R.drawable.wallpaper_time_lake_day,
                    R.drawable.wallpaper_time_lake_dusk, R.drawable.wallpaper_time_lake_night},
            {R.drawable.wallpaper_time_city_dawn, R.drawable.wallpaper_time_city_day,
                    R.drawable.wallpaper_time_city_dusk, R.drawable.wallpaper_time_city_night}};
    private static final int[] TIME_SCENE_NAMES = {
            R.string.wp_t_peak, R.string.wp_t_forest, R.string.wp_t_lake, R.string.wp_t_city};
    private int currentTimeBand = -1;
    private static final int[] WALLPAPERS = {
            R.drawable.wallpaper, R.drawable.wallpaper_waves,
            R.drawable.wallpaper_lake, R.drawable.wallpaper_borealis,
            R.drawable.wallpaper_gold, R.drawable.wallpaper_forest,
            R.drawable.wallpaper_desert, R.drawable.wallpaper_city,
            R.drawable.wallpaper_sakura, R.drawable.wallpaper_glacier,
            R.drawable.wallpaper_lava, R.drawable.wallpaper_falls};
    private static final int[] WALLPAPER_NAMES = {
            R.string.wp_aurora, R.string.wp_waves, R.string.wp_lake,
            R.string.wp_borealis, R.string.wp_gold, R.string.wp_forest,
            R.string.wp_desert, R.string.wp_city, R.string.wp_sakura,
            R.string.wp_glacier, R.string.wp_lava, R.string.wp_falls};
    /** Stable keys aligned with WALLPAPERS/WALLPAPER_NAMES; the selection is persisted by key. */
    private static final String[] WALLPAPER_KEYS = {
            "aurora", "waves", "lake", "borealis", "gold", "forest",
            "desert", "city", "sakura", "glacier", "lava", "falls"};
    /** The 7-entry order used by old builds; only for migrating the legacy int pref. */
    private static final String[] WALLPAPER_KEYS_LEGACY = {
            "aurora", "waves", "ocean", "lake", "borealis", "gold", "forest"};

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private ImageView wallpaperView;
    private Animator wallpaperAnim;
    private GlassView glass;
    private TextView heroName;
    private boolean heroShown;
    private TextView greeting;
    private TextView btnWifi;
    private TextView btnWallpaper;
    private TextView btnRemote;
    private int wallpaperIndex = 0;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private int lastFocused = 0;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            refreshTimeWallpaper();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        heroName = findViewById(R.id.heroName);
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);
        wallpaperView = findViewById(R.id.wallpaper);
        wallpaperIndex = resolveWallpaperIndex();
        applyCurrentWallpaper(false);
        if (wallpaperMotion()) createWallpaperDrift();

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.addItemDecoration(new DockDecoration(COLUMNS, density));
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow - (int) (30 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
                else glass.setTranslationY(glassTargetY);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        RemoteServer.start(this);

        View btnSettings = findViewById(R.id.btnSettings);
        btnWallpaper = findViewById(R.id.btnWallpaper);
        btnRemote = findViewById(R.id.btnRemote);
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnWallpaper.setOnClickListener(v -> showWallpaperPicker());
        btnRemote.setOnClickListener(v -> showRemotePanel());
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnRemote) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnWifi.setOnKeyListener(pillKeys);
        btnWallpaper.setOnKeyListener(pillKeys);
        btnRemote.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });
    }

    /**
     * A very slow Ken Burns drift on the wallpaper so the backdrop feels alive.
     * Any failure just leaves the wallpaper static.
     */
    private void createWallpaperDrift() {
        try {
            wallpaperView.setScaleX(1.08f);
            wallpaperView.setScaleY(1.08f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(wallpaperView, View.SCALE_X, 1.08f, 1.16f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(wallpaperView, View.SCALE_Y, 1.08f, 1.16f);
            ObjectAnimator tx = ObjectAnimator.ofFloat(wallpaperView, View.TRANSLATION_X, 0f, -14f * density);
            ObjectAnimator ty = ObjectAnimator.ofFloat(wallpaperView, View.TRANSLATION_Y, 0f, -8f * density);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(sx, sy, tx, ty);
            for (ObjectAnimator a : new ObjectAnimator[]{sx, sy, tx, ty}) {
                a.setDuration(26_000L);
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                a.setInterpolator(new LinearInterpolator());
            }
            wallpaperAnim = set;
        } catch (Exception ignored) {
            wallpaperAnim = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wallpaperAnim != null && wallpaperMotion()) {
            if (wallpaperAnim.isPaused()) wallpaperAnim.resume();
            else if (!wallpaperAnim.isStarted()) wallpaperAnim.start();
        }
        glassShown = false;
        glass.setAlpha(0f);
        heroShown = false;
        heroName.setAlpha(0f);
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateWifiLabel();
        refreshTimeWallpaper();
        handler.postDelayed(minuteTick, MINUTE_MS);

        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wallpaperAnim != null && wallpaperAnim.isStarted()) wallpaperAnim.pause();
        if (adapter != null && adapter.isMoveMode()) adapter.exitMoveMode();   // never strand an unsaved order
        handler.removeCallbacks(minuteTick);
    }

    @Override
    public void onBackPressed() {
        if (adapter != null && adapter.isMoveMode()) {
            adapter.exitMoveMode();   // Back also confirms the new order
            return;
        }
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(22f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
        View item = (View) card.getParent();
        if (position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            showHero(position, r.top - dy);
        } else {
            hideHero();
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - heroName.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());

        if (!heroShown) {
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            heroShown = true;
        } else if (changed) {
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
        } else {
            heroName.setY(y);
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    // ---- move mode (long-press a tile to reposition it) --------------------------------------------

    @Override
    public void onTileLongPressed(int position, View card) {
        adapter.enterMoveMode(position);
        Toast.makeText(this, getString(R.string.move_mode_hint), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onTileMoved(int newPosition) {
        // Defer the focus follow until layout settles, so focus lands on the carried tile
        // itself and not on the neighbour that slid into its old spot.
        grid.post(() -> focusTile(newPosition));
    }

    @Override
    public boolean onTileMoveKey(int position, int keyCode) {
        if (adapter == null || !adapter.isMoveMode()) return false;
        // D-pad moves the carried tile (tracked by the adapter, not by focus); the key is
        // consumed even when the move itself is out of bounds.
        // DPAD_CENTER exits via the card's click listener, Back via onBackPressed.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            adapter.moveCarried(keyCode);
        }
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Fixed label and icon for the connection type: "Ethernet" (port icon) on a wired
     *  network, "Wi-Fi" (Wi-Fi icon) otherwise. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) {
                text = getString(R.string.offline);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                text = getString(R.string.wifi);
            }
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
        btnWifi.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
    }

    private int dp(float v) {
        return (int) (v * density);
    }

    /** Whether the slow wallpaper drift is enabled (picker toggle, default on). */
    private boolean wallpaperMotion() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WALLPAPER_MOTION, true);
    }

    /** Persists the drift on/off choice and applies it to the backdrop right away. */
    private void setWallpaperMotion(boolean on) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_WALLPAPER_MOTION, on).commit();
        if (on) {
            if (wallpaperAnim == null) createWallpaperDrift();
            if (wallpaperAnim != null) {
                if (wallpaperAnim.isPaused()) wallpaperAnim.resume();
                else if (!wallpaperAnim.isStarted()) wallpaperAnim.start();
            }
        } else {
            if (wallpaperAnim != null) {
                wallpaperAnim.cancel();
                wallpaperAnim = null;
            }
            wallpaperView.setScaleX(1f);
            wallpaperView.setScaleY(1f);
            wallpaperView.setTranslationX(0f);
            wallpaperView.setTranslationY(0f);
        }
    }

    /**
     * Resolves the current wallpaper index from the persisted key. Falls back to the legacy
     * int pref from old builds, mapping it through the old 7-entry order (the removed "ocean"
     * entry lands on "aurora"), and persists the key so later launches take the fast path.
     */
    private int resolveWallpaperIndex() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = prefs.getString(KEY_WALLPAPER_KEY, null);
        if (key != null) {
            int idx = wallpaperKeyIndex(key);
            if (idx >= 0) return idx;
        }
        String mapped = "aurora";
        if (prefs.contains(KEY_WALLPAPER_INDEX)) {
            int legacy = prefs.getInt(KEY_WALLPAPER_INDEX, 0);
            if (legacy < 0) legacy = 0;
            if (legacy >= WALLPAPER_KEYS_LEGACY.length) legacy = WALLPAPER_KEYS_LEGACY.length - 1;
            mapped = WALLPAPER_KEYS_LEGACY[legacy];
            if ("ocean".equals(mapped)) mapped = "aurora";
        }
        prefs.edit().putString(KEY_WALLPAPER_KEY, mapped).commit();
        return wallpaperKeyIndex(mapped);
    }

    private static int wallpaperKeyIndex(String key) {
        for (int i = 0; i < WALLPAPER_KEYS.length; i++) {
            if (WALLPAPER_KEYS[i].equals(key)) return i;
        }
        return -1;
    }

    /** Switches the backdrop to a built-in wallpaper and remembers the choice.
     *  Picking a fixed wallpaper turns the time-of-day mode off. */
    private void applyWallpaper(int index) {
        wallpaperIndex = index;
        wallpaperView.setImageResource(WALLPAPERS[index]);
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_WALLPAPER_KEY, WALLPAPER_KEYS[index])
                .putBoolean(KEY_WALLPAPER_TIME, false).commit();
        currentTimeBand = -1;
    }

    // ---- time-of-day wallpaper ---------------------------------------------------------------------

    private boolean wallpaperTimeMode() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WALLPAPER_TIME, false);
    }

    private int timeScene() {
        int scene = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_WALLPAPER_SCENE, 0);
        return Math.max(0, Math.min(scene, TIME_WALLPAPERS.length - 1));
    }

    /** Clicking a time-of-day scene opens it: time mode on, that scene showing. There is
     *  no switch to turn off — picking any fixed wallpaper leaves time mode again. */
    private void openTimeScene(int scene) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_WALLPAPER_SCENE, scene)
                .putBoolean(KEY_WALLPAPER_TIME, true).commit();
        currentTimeBand = -1;
        applyCurrentWallpaper(true);
    }

    /** 0 dawn (05–08), 1 day (09–16), 2 dusk (17–20), 3 night (21–04). */
    private static int timeBand() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 9) return 0;
        if (h >= 9 && h < 17) return 1;
        if (h >= 17 && h < 21) return 2;
        return 3;
    }

    private void applyCurrentWallpaper(boolean fade) {
        if (wallpaperTimeMode()) {
            currentTimeBand = timeBand();
            setWallpaperImage(TIME_WALLPAPERS[timeScene()][currentTimeBand], fade);
        } else {
            setWallpaperImage(WALLPAPERS[wallpaperIndex], fade);
        }
    }

    /** Called every minute and on resume: cross over to the new time of day when it arrives. */
    private void refreshTimeWallpaper() {
        if (!wallpaperTimeMode()) return;
        int band = timeBand();
        if (band != currentTimeBand) {
            currentTimeBand = band;
            setWallpaperImage(TIME_WALLPAPERS[timeScene()][band], true);
        }
    }

    private void setWallpaperImage(int res, boolean fade) {
        if (!fade) {
            wallpaperView.setImageResource(res);
            return;
        }
        wallpaperView.animate().cancel();
        wallpaperView.animate().alpha(0f).setDuration(320).withEndAction(() -> {
            wallpaperView.setImageResource(res);
            wallpaperView.animate().alpha(1f).setDuration(480).start();
        }).start();
    }

    // ---- phone remote ----------------------------------------------------------------------------

    private TextView panelText(CharSequence text, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(0, 1.25f);
        return tv;
    }

    private TextView panelButton(int stringRes) {
        TextView btn = new TextView(this);
        btn.setText(stringRes);
        btn.setTextSize(15);
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(20), dp(10), dp(20), dp(10));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOnFocusChangeListener((v, focused) ->
                v.setBackgroundResource(focused ? R.drawable.motion_row_bg : 0));
        return btn;
    }

    /** Pairing panel for the phone remote: QR code, address, pairing code and the
     *  accessibility-service status, centred over the home screen. */
    private void showRemotePanel() {
        String url = RemoteServer.pairingUrl(this);
        boolean a11y = TvAccessibilityService.isRunning();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setClickable(true);
        panel.setOnClickListener(v -> {
            // Consume clicks so they do not fall through to the dismiss scrim.
        });
        panel.setPadding(dp(30), dp(24), dp(30), dp(22));
        panel.setLayoutParams(new FrameLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        TextView title = panelText(getString(R.string.remote_title), 19, 0xFFFFFFFF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);

        if (url == null) {
            TextView noNet = panelText(getString(R.string.remote_no_network), 14, 0xFFF2C169);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            noNet.setLayoutParams(lp);
            panel.addView(noNet);
        } else {
            TextView hint = panelText(getString(R.string.remote_scan_hint), 13, 0xB3FFFFFF);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = dp(10);
            hint.setLayoutParams(hintLp);
            panel.addView(hint);

            ImageView qrView = new ImageView(this);
            Bitmap qrBitmap = QrCode.toBitmap(QrCode.encode(url), dp(196));
            qrView.setImageBitmap(qrBitmap);
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(dp(196), dp(196));
            qrLp.gravity = Gravity.CENTER_HORIZONTAL;
            qrLp.topMargin = dp(14);
            qrView.setLayoutParams(qrLp);
            panel.addView(qrView);

            TextView addr = panelText(url, 16.5f, 0xFFFFFFFF);
            LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            addrLp.topMargin = dp(12);
            addr.setLayoutParams(addrLp);
            panel.addView(addr);

            TextView pin = panelText(getString(R.string.remote_pin, RemoteServer.pin(this)),
                    13.5f, 0xD9FFFFFF);
            LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pinLp.topMargin = dp(6);
            pin.setLayoutParams(pinLp);
            panel.addView(pin);

            TextView wifi = panelText(getString(R.string.remote_same_wifi), 12, 0x8CFFFFFF);
            LinearLayout.LayoutParams wifiLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wifiLp.topMargin = dp(4);
            wifi.setLayoutParams(wifiLp);
            panel.addView(wifi);
        }

        TextView status = panelText(getString(a11y ? R.string.remote_a11y_on : R.string.remote_a11y_off),
                13.5f, a11y ? 0xFF8FE3B0 : 0xFFF2C169);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(16);
        status.setLayoutParams(statusLp);
        panel.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = dp(16);
        buttons.setLayoutParams(buttonsLp);

        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x99000000);
        scrim.addView(panel);
        final PopupWindow popup = new PopupWindow(scrim,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        scrim.setOnClickListener(v -> popup.dismiss());

        TextView openA11y = panelButton(R.string.remote_open_a11y);
        openA11y.setOnClickListener(v -> {
            popup.dismiss();
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        TextView close = panelButton(R.string.remote_close);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = dp(10);
        close.setLayoutParams(closeLp);
        close.setOnClickListener(v -> popup.dismiss());
        if (!a11y) buttons.addView(openA11y);
        buttons.addView(close);
        panel.addView(buttons);

        popup.showAtLocation(root, Gravity.CENTER, 0, 0);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        final View focusTarget = a11y ? close : openA11y;
        panel.post(() -> focusTarget.requestFocus());
    }

    /** One picker entry: rounded thumbnail over a centred name; the thumbnail is the tag. */
    private LinearLayout wallpaperThumb(int imageRes, int nameRes, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(6);
        item.setPadding(pad, pad, pad, pad);

        ImageView thumb = new ImageView(this);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(dp(176), dp(99)));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setImageResource(imageRes);
        thumb.setFocusable(true);
        thumb.setClickable(true);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 14f * density);
            }
        });
        thumb.setClipToOutline(true);
        thumb.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.06f : 1.0f;
            v.animate().scaleX(s).scaleY(s).setDuration(150).start();
        });
        thumb.setOnClickListener(click);

        TextView name = new TextView(this);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                dp(176), ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(6);
        name.setLayoutParams(nameLp);
        name.setText(nameRes);
        name.setTextSize(12);
        name.setTextColor(0xF2FFFFFF);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);

        item.addView(thumb);
        item.addView(name);
        item.setTag(thumb);
        return item;
    }

    /** Thumbnail strip of the built-in wallpapers, dropped below the wallpaper pill. */
    private void showWallpaperPicker() {
        View panel = getLayoutInflater().inflate(R.layout.popup_wallpapers, root, false);
        LinearLayout motionRow = panel.findViewById(R.id.wpMotionRow);
        TextView motionState = panel.findViewById(R.id.wpMotionState);
        motionState.setText(getString(wallpaperMotion() ? R.string.wp_on : R.string.wp_off));
        motionRow.setOnFocusChangeListener((v, focused) ->
                v.setBackgroundResource(focused ? R.drawable.motion_row_bg : 0));
        motionRow.setOnClickListener(v -> {
            boolean on = !wallpaperMotion();
            setWallpaperMotion(on);
            motionState.setText(getString(on ? R.string.wp_on : R.string.wp_off));
        });
        // The thumbnail area scrolls: on TVs whose density makes four rows taller than the
        // screen, the panel used to clip the last row out of reach. Cap its height and let
        // focus movement roll it.
        ScrollView wpScroll = panel.findViewById(R.id.wpScroll);
        ViewGroup.LayoutParams slp = wpScroll.getLayoutParams();
        slp.height = Math.min(4 * dp(141),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.55f));
        wpScroll.setLayoutParams(slp);
        LinearLayout row = panel.findViewById(R.id.wpRow);
        LinearLayout row2 = panel.findViewById(R.id.wpRow2);
        LinearLayout row3 = panel.findViewById(R.id.wpRow3);
        final PopupWindow popup = new PopupWindow(panel,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        View focusTarget = null;
        for (int i = 0; i < WALLPAPERS.length; i++) {
            final int index = i;
            LinearLayout item = wallpaperThumb(WALLPAPERS[i], WALLPAPER_NAMES[i], v -> {
                applyWallpaper(index);
                popup.dismiss();
            });
            (i < 4 ? row : i < 8 ? row2 : row3).addView(item);   // 12 wallpapers: 4 per row, three rows
            if (i == wallpaperIndex) focusTarget = (View) item.getTag();
        }

        // Fourth row: the time-of-day scenes. Picking one turns time mode on with that scene.
        LinearLayout row4 = panel.findViewById(R.id.wpRow4);
        View sceneFocus = null;
        for (int s = 0; s < TIME_WALLPAPERS.length; s++) {
            final int scene = s;
            LinearLayout item = wallpaperThumb(TIME_WALLPAPERS[s][1], TIME_SCENE_NAMES[s], v -> {
                openTimeScene(scene);
                popup.dismiss();
            });
            row4.addView(item);
            if (s == timeScene()) sceneFocus = (View) item.getTag();
        }
        if (wallpaperTimeMode() && sceneFocus != null) focusTarget = sceneFocus;

        // The pill sits near the right edge: hang the panel below it, end-aligned, so it stays on screen.
        popup.showAsDropDown(btnWallpaper, 0, dp(10), Gravity.END);
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        // Focus the current selection once the popup is on screen (it may live in the second row).
        final View target = focusTarget;
        if (target != null) panel.post(() -> target.requestFocus());
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }
}
