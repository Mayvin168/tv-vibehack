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
        wallpaperView.setImageResource(WALLPAPERS[wallpaperIndex]);
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

        View btnSettings = findViewById(R.id.btnSettings);
        btnWallpaper = findViewById(R.id.btnWallpaper);
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnWallpaper.setOnClickListener(v -> showWallpaperPicker());
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnWallpaper) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnWifi.setOnKeyListener(pillKeys);
        btnWallpaper.setOnKeyListener(pillKeys);
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

    /** Switches the backdrop to a built-in wallpaper and remembers the choice. */
    private void applyWallpaper(int index) {
        wallpaperIndex = index;
        wallpaperView.setImageResource(WALLPAPERS[index]);
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_WALLPAPER_KEY, WALLPAPER_KEYS[index]).commit();
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
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(6);
            item.setPadding(pad, pad, pad, pad);

            ImageView thumb = new ImageView(this);
            thumb.setLayoutParams(new LinearLayout.LayoutParams(dp(176), dp(99)));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setImageResource(WALLPAPERS[i]);
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
            thumb.setOnClickListener(v -> {
                applyWallpaper(index);
                popup.dismiss();
            });

            TextView name = new TextView(this);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    dp(176), ViewGroup.LayoutParams.WRAP_CONTENT);
            nameLp.topMargin = dp(6);
            name.setLayoutParams(nameLp);
            name.setText(WALLPAPER_NAMES[i]);
            name.setTextSize(12);
            name.setTextColor(0xF2FFFFFF);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);

            item.addView(thumb);
            item.addView(name);
            (i < 4 ? row : i < 8 ? row2 : row3).addView(item);   // 12 wallpapers: 4 per row, three rows
            if (i == wallpaperIndex) focusTarget = thumb;
        }

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
