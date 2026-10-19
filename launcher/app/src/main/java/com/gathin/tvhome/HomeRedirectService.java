package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

/**
 * For TVs (Fire OS) where the stock launcher can't be replaced or disabled: when the stock home screen comes
 * to the front, start this launcher instead.
 *
 * Fire OS keeps the Home button to itself, so the stock home always starts first. To hide it, the moment the
 * stock home begins to appear a full-screen cover showing this launcher's background is switched on over
 * everything, and switched off as soon as this launcher is in front (or after a few seconds, whatever happens).
 * The cover window is created hidden when the service starts, so showing it costs one frame.
 *
 * The "Amazon Home" tile calls {@link #allowStockHome()} to let the stock home through once; it stays open until
 * another app takes the foreground. Inactive unless config.xml sets stock_home_package.
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String TAG = "TvHomeRedirect";
    /** The cover never stays longer than this, even if the launcher never shows up. */
    private static final long COVER_MAX_MS = 4000;
    /** Lets the launcher draw its first frame before the cover is lifted. */
    private static final long COVER_LIFT_DELAY_MS = 100;
    /** Stock-home events this soon after the launcher came to the front are part of the same switch. */
    private static final long SETTLE_MS = 1000;
    private static volatile boolean allowStock;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable liftCover = this::hideCover;
    private WindowManager wm;
    /** A 1x1 transparent overlay that keeps this app counted as having a visible window (see onServiceConnected). */
    private View anchor;
    /** Full-screen image of the launcher background, shown over the stock home while the launcher starts. */
    private ImageView cover;
    private boolean coverShown;
    /** Package of the last app that came to the front (system UI ignored). */
    private String lastFront = "";
    /** When the launcher was last started from here, to ignore the burst of events that follows. */
    private long lastTriggerAt;
    private static final long TRIGGER_GAP_MS = 3000;
    /** When the launcher last came to the front. */
    private long launcherFrontAt;

    static void allowStockHome() {
        allowStock = true;
    }

    private boolean active() {
        return !getString(R.string.stock_home_package).trim().isEmpty();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!active()) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        // Android 10+ (Fire OS 8 is Android 11) blocks activity starts from apps in the background. One of the
        // exemptions is "the app has a visible window". An accessibility service may add an accessibility overlay
        // without any extra permission, so a 1x1 transparent one is kept on screen while the redirect is on.
        try {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            View v = new View(this);
            v.setBackgroundColor(0x01000000);   // effectively invisible, but a real surface
            wm.addView(v, lp);
            anchor = v;
            Log.i(TAG, "overlay anchor added");
        } catch (Exception e) {
            Log.w(TAG, "overlay anchor failed", e);
        }
        prepareCover();
    }

    /** Decodes the background and adds the full-screen cover window once, hidden, so showing it is instant. */
    private void prepareCover() {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;     // half the memory; it's an opaque photo-like image
            Bitmap bg = BitmapFactory.decodeResource(getResources(), R.drawable.nocturne_bg, o);
            ImageView v = new ImageView(this);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setImageBitmap(bg);
            v.setVisibility(View.GONE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.windowAnimations = 0;
            wm.addView(v, lp);
            cover = v;
            Log.i(TAG, "cover prepared");
        } catch (Exception e) {
            Log.w(TAG, "cover prepare failed", e);
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (cover != null && wm != null) {
            try {
                wm.removeView(cover);
            } catch (Exception ignored) {
            }
            cover = null;
            coverShown = false;
        }
        if (anchor != null && wm != null) {
            try {
                wm.removeView(anchor);
            } catch (Exception ignored) {
            }
            anchor = null;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null || !active()) return;
        String pkg = pkgCs.toString();
        String stock = getString(R.string.stock_home_package).trim();
        boolean stateChange = event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        boolean systemUi = pkg.equals("android") || pkg.startsWith("com.android.")
                || pkg.startsWith("com.amazon.tv.quicksettings");
        String wasFront = lastFront;
        if (stateChange && !systemUi) lastFront = pkg;

        if (pkg.equals(getPackageName())) {
            // The launcher is in front: lift the cover once it has drawn.
            if (stateChange) {
                allowStock = false;
                launcherFrontAt = SystemClock.uptimeMillis();
                if (coverShown) {
                    handler.removeCallbacks(liftCover);
                    handler.postDelayed(liftCover, COVER_LIFT_DELAY_MS);
                }
            }
            return;
        }

        if (pkg.equals(stock)) {
            if (allowStock) return;
            if (stateChange) {
                CharSequence cls = event.getClassName();
                String hint = getString(R.string.stock_home_class_hint).trim();
                // An empty hint redirects every screen of the stock home package.
                if (!hint.isEmpty() && (cls == null || !cls.toString().contains(hint))) return;
                Log.i(TAG, "stock home in front (" + cls + ")");
            } else if (wasFront.equals(getPackageName())) {
                // Content/windows events from the stock home while the launcher is in front are leftovers of the
                // last redirect; only a real state change (Home pressed again) counts then.
                return;
            }
            // The first sign of the stock home (often a content or windows event, before the state change):
            // cover it and start the launcher.
            long now = SystemClock.uptimeMillis();
            if (now - lastTriggerAt < TRIGGER_GAP_MS || now - launcherFrontAt < SETTLE_MS) return;
            lastTriggerAt = now;
            showCover();
            startLauncher();
            return;
        }

        if (stateChange && !systemUi) {
            allowStock = false;     // another app is in front: the stock home is redirected again
            lastTriggerAt = 0;      // and the next Home press counts at once, however quick
            if (coverShown) hideCover();
        }
    }

    private void startLauncher() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(this, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Right after Home, Android holds activity starts from background apps for 5 seconds unless the sender has
        // STOP_APP_SWITCHES. An alarm's PendingIntent is sent by the system, which does, so it starts at once.
        PendingIntent pi = PendingIntent.getActivity(this, 0, home, PendingIntent.FLAG_UPDATE_CURRENT);
        ((AlarmManager) getSystemService(ALARM_SERVICE))
                .setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime(), pi);
        Log.i(TAG, "launcher start requested");
    }

    private void showCover() {
        if (coverShown || cover == null) return;
        cover.setVisibility(View.VISIBLE);
        coverShown = true;
        handler.removeCallbacks(liftCover);
        handler.postDelayed(liftCover, COVER_MAX_MS);
        Log.i(TAG, "cover shown");
    }

    private void hideCover() {
        handler.removeCallbacks(liftCover);
        if (!coverShown || cover == null) return;
        cover.setVisibility(View.GONE);
        coverShown = false;
        Log.i(TAG, "cover lifted");
    }

    @Override
    public void onInterrupt() {
    }
}
