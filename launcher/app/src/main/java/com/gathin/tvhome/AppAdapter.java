package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        /** A tile was swapped to a new position while in move mode. */
        void onTileMoved(int newPosition);

        /** A tile was long-pressed: the Activity enters move mode. */
        void onTileLongPressed(int position, View card);

        /**
         * D-pad key on a tile while the adapter may be in move mode.
         * Return true when the key was consumed (move mode swallows all D-pad keys).
         */
        boolean onTileMoveKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.10f;

    /** SharedPreferences holding the user's custom tile order (move mode). */
    private static final String PREFS = "tvhome";
    private static final String KEY_ORDER = "app_order";

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** True while a tile is being repositioned with the D-pad. */
    private boolean moveMode;
    /** Adapter position of the tile being carried in move mode; -1 when not moving. */
    private int movingPos = -1;
    private RecyclerView recyclerView;
    /** Item animator stashed while move mode runs with animations off. */
    private RecyclerView.ItemAnimator savedAnimator;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), banner, icon));
        }
        // A saved custom order (from move mode) wins: its apps come first, in saved order.
        // The rest keeps the old rule: favourites (dock) first, then alphabetical.
        // Fully deterministic: ties fall through to label, then component class name, so
        // equal-key entries (e.g. two activities of one package) never swap between reloads.
        final List<String> saved = savedOrder();
        final Map<String, Integer> savedIndex = new HashMap<>();
        for (int i = 0; i < saved.size(); i++) {
            if (!savedIndex.containsKey(saved.get(i))) savedIndex.put(saved.get(i), i);
        }
        Collections.sort(fresh, (a, b) -> {
            int sa = savedIndexOf(savedIndex, a), sb = savedIndexOf(savedIndex, b);
            if (sa != sb) {
                if (sa < 0) return 1;
                if (sb < 0) return -1;
                return Integer.compare(sa, sb);
            }
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa != fb) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            int byLabel = a.label.compareToIgnoreCase(b.label);
            if (byLabel != 0) return byLabel;
            return a.component.getClassName().compareTo(b.component.getClassName());
        });
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    /**
     * Saved-order index of an entry. Orders are stored per entry (pkg#class) so that apps
     * with several leanback activities keep distinct slots; older orders are package-only,
     * so fall back to the package token. -1 when the entry is not in the saved order.
     */
    private static int savedIndexOf(Map<String, Integer> savedIndex, Entry e) {
        Integer i = savedIndex.get(e.pkg + "#" + e.component.getClassName());
        if (i == null) i = savedIndex.get(e.pkg);
        return i == null ? -1 : i;
    }

    /** The user's saved tile order; empty when they have never rearranged anything. */
    private List<String> savedOrder() {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ORDER, "");
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        // Packages that are no longer installed simply never match an entry; no pruning needed.
        return Arrays.asList(raw.split(","));
    }

    // ---- move mode (long-press a tile, D-pad to reposition, OK/Back to finish) -------------------

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        super.onAttachedToRecyclerView(rv);
        recyclerView = rv;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        super.onDetachedFromRecyclerView(rv);
        recyclerView = null;
    }

    boolean isMoveMode() {
        return moveMode;
    }

    void enterMoveMode(int position) {
        moveMode = true;
        movingPos = position;
        if (recyclerView != null) {
            // No item animations while carrying a tile: positions must be final immediately
            // so the next D-pad press acts on settled geometry, not a tile mid-slide.
            savedAnimator = recyclerView.getItemAnimator();
            recyclerView.setItemAnimator(null);
        }
    }

    /** Leaves move mode and persists the current tile order. */
    void exitMoveMode() {
        moveMode = false;
        movingPos = -1;
        if (recyclerView != null && savedAnimator != null) {
            recyclerView.setItemAnimator(savedAnimator);
            savedAnimator = null;
        }
        saveOrder();
    }

    /** Persists the current tile order (per-entry pkg#class keys, comma-joined). */
    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.pkg).append('#').append(e.component.getClassName());
        }
        // commit(), not apply(): the write must be on disk before a heavy app can kill
        // this process on a 1 GB TV, or the user's order is lost.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ORDER, sb.toString()).commit();
    }

    /**
     * Swaps the tile at {@code from} one step in the D-pad direction. Left/Right never wrap
     * to another row. Returns true when the key was consumed (always, in move mode).
     */
    boolean moveFocused(int from, int keyCode) {
        if (!moveMode || from < 0 || from >= items.size()) return moveMode;
        int to;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (from % dockSize == 0) return true;              // row start: no wrap
                to = from - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (from % dockSize == dockSize - 1) return true;   // row end: no wrap
                to = from + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                to = from - dockSize;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                to = from + dockSize;
                break;
            default:
                return true;
        }
        if (to < 0 || to >= items.size() || to == from) return true;
        Collections.swap(items, from, to);
        notifyItemMoved(from, to);
        saveOrder();   // persist now: the order must survive even if move mode is never formally exited
        if (listener != null) listener.onTileMoved(to);
        return true;
    }

    /**
     * Moves the carried tile (tracked explicitly in {@link #movingPos}, never derived from
     * whichever view happens to hold focus) one step in the D-pad direction. Same geometry
     * as {@link #moveFocused}: Left/Right never wrap to another row. Returns true when the
     * key was consumed (always, in move mode).
     */
    boolean moveCarried(int keyCode) {
        if (!moveMode || movingPos < 0 || movingPos >= items.size()) return moveMode;
        int from = movingPos;
        int to;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (from % dockSize == 0) return true;              // row start: no wrap
                to = from - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (from % dockSize == dockSize - 1) return true;   // row end: no wrap
                to = from + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                to = from - dockSize;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                to = from + dockSize;
                break;
            default:
                return true;
        }
        if (to < 0 || to >= items.size() || to == from) return true;
        Collections.swap(items, from, to);
        notifyItemMoved(from, to);
        movingPos = to;
        saveOrder();   // persist now: the order must survive even if move mode is never formally exited
        if (listener != null) listener.onTileMoved(to);
        return true;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        h.name.setAlpha(0f);
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> {
            if (moveMode) {
                exitMoveMode();   // OK while repositioning just confirms the new order
            } else {
                launch(e);
            }
        });
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) {
                listener.onTileLongPressed(pos, h.card);
            }
            return true;
        });
    }

    private void launch(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 22f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                boolean dpad = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (!dpad) return false;
                int pos = getBindingAdapterPosition();
                if (listener == null || pos == RecyclerView.NO_POSITION) return false;
                // Move mode first: the Activity routes these to the adapter and consumes them all.
                if (listener.onTileMoveKey(pos, keyCode)) return true;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    return listener.onTileVerticalKey(pos, keyCode);
                }
                return false;
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
