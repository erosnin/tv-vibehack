package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * For TVs (Fire OS) where the stock launcher can't be replaced or disabled.
 *
 * 1. Home button: the press is caught before the system sees it and this launcher is opened directly, so the
 *    stock home never appears. Hold Home (0.8 s) to go to the stock home instead. If the launcher doesn't show up
 *    within a second, the normal system Home is sent so the remote never gets stuck.
 * 2. Fallback: whenever the stock home screen comes to the front anyway (Back from an app, or if Fire OS doesn't
 *    pass the Home key to accessibility services), this launcher is started on top of it.
 *
 * The "Amazon Home" tile calls {@link #allowStockHome()} to let the stock home through once; it stays open until
 * another app takes the foreground. Inactive unless config.xml sets stock_home_package.
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String TAG = "TvHomeRedirect";
    private static final long LONG_PRESS_MS = 800;
    private static final long CONFIRM_MS = 1000;
    private static volatile boolean allowStock;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** A 1x1 transparent overlay that keeps this app counted as having a visible window (see onServiceConnected). */
    private View anchor;
    private long homeDownAt = -1;
    private String foregroundPkg = "";
    private boolean launchConfirmed;

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
        // Android 10+ (Fire OS 8 is Android 11) blocks activity starts from apps in the background. One of the
        // exemptions is "the app has a visible window". An accessibility service may add an accessibility overlay
        // without any extra permission, so a 1x1 transparent one is kept on screen while the redirect is on.
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
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
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (anchor != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(anchor);
            } catch (Exception ignored) {
            }
            anchor = null;
        }
        super.onDestroy();
    }

    // ---- 1. the Home button -----------------------------------------------------------------------

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME || !active()) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) homeDownAt = event.getEventTime();
            return true;    // swallow it: the system never starts the stock home
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            long held = homeDownAt < 0 ? 0 : event.getEventTime() - homeDownAt;
            homeDownAt = -1;
            if (held >= LONG_PRESS_MS) {
                Log.i(TAG, "Home held: opening the stock home");
                allowStock = true;
                performGlobalAction(GLOBAL_ACTION_HOME);
            } else {
                openLauncherNow();
            }
        }
        return true;
    }

    private void openLauncherNow() {
        boolean alreadyInFront = getPackageName().equals(foregroundPkg);
        launchConfirmed = false;
        try {
            startActivity(launcherIntent());
            Log.i(TAG, "Home pressed: launcher started");
        } catch (Exception e) {
            Log.w(TAG, "Home pressed: launcher start failed", e);
        }
        if (alreadyInFront) return;
        // Safety net: if the launcher didn't come up (e.g. a Fire OS update blocks it), send the normal Home
        // so the remote keeps working; the stock-home fallback below then redirects as before.
        handler.postDelayed(() -> {
            if (!launchConfirmed && !launcherInFront()) {
                Log.w(TAG, "launcher did not appear, sending the system Home");
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
        }, CONFIRM_MS);
    }

    /** True while this app's activity is on top (its process is then IMPORTANCE_FOREGROUND, not just bound). */
    private static boolean launcherInFront() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private Intent launcherIntent() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(new ComponentName(this, MainActivity.class))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    // ---- 2. the stock home appearing anyway ---------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        if (!active()) return;
        String stock = getString(R.string.stock_home_package).trim();

        boolean systemUi = pkg.equals("android") || pkg.startsWith("com.android.")
                || pkg.startsWith("com.amazon.tv.quicksettings");
        if (!systemUi) foregroundPkg = pkg;
        if (pkg.equals(getPackageName())) launchConfirmed = true;

        if (pkg.equals(stock)) {
            CharSequence cls = event.getClassName();
            String hint = getString(R.string.stock_home_class_hint).trim();
            // An empty hint redirects every screen of the stock home package.
            if (allowStock || (!hint.isEmpty() && (cls == null || !cls.toString().contains(hint)))) return;
            Log.i(TAG, "stock home in front (" + cls + "), redirecting");
            // Right after Home, Android holds activity starts from background apps for 5 seconds unless the sender has
            // STOP_APP_SWITCHES. An alarm's PendingIntent is sent by the system, which does, so it starts at once.
            PendingIntent pi = PendingIntent.getActivity(this, 0, launcherIntent(), PendingIntent.FLAG_UPDATE_CURRENT);
            ((AlarmManager) getSystemService(ALARM_SERVICE))
                    .setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1, pi);
        } else if (!systemUi) {
            allowStock = false;     // another app (or this launcher) is in front: the stock home is redirected again
        }
    }

    @Override
    public void onInterrupt() {
    }
}
