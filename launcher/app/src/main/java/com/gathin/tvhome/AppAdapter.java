package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Silver app tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * the rest is alphabetical. Remembers the last app opened from here so the home screen can offer to resume it.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable icon;

        Entry(String label, ComponentName component, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Odd columns sit this much lower (the staggered row). */
    static final int STAGGER_DP = 20;

    private static final String PREFS = "nocturne";
    private static final String KEY_LAST = "last_component";

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private final String stockHome;
    /** Dock order (res/values/config.xml, dock_apps). */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.stockHome = ctx.getString(R.string.stock_home_package).trim();
        this.favourites = Arrays.asList(ctx.getResources().getStringArray(R.array.dock_apps));
        this.hidden = new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps)));
    }

    void reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(probe, 0));
        // Dock apps without a leanback activity (e.g. Fire OS's Silk browser) are found via the plain launcher category.
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo != null) seen.add(ri.activityInfo.packageName);
        }
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (ri.activityInfo != null && favourites.contains(ri.activityInfo.packageName)
                    && seen.add(ri.activityInfo.packageName)) {
                found.add(ri);
            }
        }
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable icon = ri.loadIcon(pm);
            String label = String.valueOf(ri.loadLabel(pm));
            fresh.add(new Entry(label, new ComponentName(pkg, ri.activityInfo.name), icon));
        }
        if (!stockHome.isEmpty()) {
            try {
                Drawable stockIcon = pm.getApplicationIcon(stockHome);
                fresh.add(new Entry(ctx.getString(R.string.stock_home_label), new ComponentName(stockHome, "home"), stockIcon));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
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
        h.card.setScaleX(1f);
        h.card.setScaleY(1f);
        h.icon.setImageDrawable(e.icon);
        h.label.setText(e.label);
        h.card.setContentDescription(e.label);

        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.card.getLayoutParams();
        float density = ctx.getResources().getDisplayMetrics().density;
        int top = (position % dockSize) % 2 == 1 ? (int) (STAGGER_DP * density) : 0;
        if (lp.topMargin != top) {
            lp.topMargin = top;
            h.card.setLayoutParams(lp);
        }

        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(i);
            } catch (ActivityNotFoundException ignored) {
            }
            return true;
        });
    }

    void launch(Entry e) {
        if (!stockHome.isEmpty() && e.pkg.equals(stockHome)) {
            HomeRedirectService.allowStockHome();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(e.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(home);
            } catch (Exception ex) {
                Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            prefs().edit().putString(KEY_LAST, e.component.flattenToString()).apply();
        } catch (Exception ex) {
            Toast.makeText(ctx, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    /** The last app opened from this launcher, if it is still installed. */
    Entry lastLaunched() {
        String last = prefs().getString(KEY_LAST, null);
        if (last == null) return null;
        for (Entry e : items) {
            if (e.component.flattenToString().equals(last)) return e;
        }
        return null;
    }

    /** The first real app in the list (the stock home tile is skipped). */
    Entry firstApp() {
        for (Entry e : items) {
            if (stockHome.isEmpty() || !e.pkg.equals(stockHome)) return e;
        }
        return null;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView icon;
        final TextView label;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);

            final float density = v.getResources().getDisplayMetrics().density;
            final float radius = density * 14f;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
