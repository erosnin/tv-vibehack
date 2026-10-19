package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * For TVs (Fire OS) where the stock launcher can't be replaced or disabled: when the stock home screen comes
 * to the front, start this launcher instead. The "Amazon Home" tile calls {@link #allowStockHome()} to let the
 * stock home through once; it stays open until another app takes the foreground.
 * Inactive unless config.xml sets stock_home_package.
 */
public class HomeRedirectService extends AccessibilityService {

    private static volatile boolean allowStock;

    static void allowStockHome() {
        allowStock = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();
        String stock = getString(R.string.stock_home_package).trim();
        if (stock.isEmpty()) return;

        if (pkg.equals(stock)) {
            CharSequence cls = event.getClassName();
            String hint = getString(R.string.stock_home_class_hint).trim();
            // An empty hint redirects every screen of the stock home package.
            if (allowStock || (!hint.isEmpty() && (cls == null || !cls.toString().contains(hint)))) return;
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(new ComponentName(this, MainActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            // Right after Home, Android holds activity starts from background apps for 5 seconds unless the sender has
            // STOP_APP_SWITCHES. An alarm's PendingIntent is sent by the system, which does, so it starts at once.
            PendingIntent pi = PendingIntent.getActivity(this, 0, home, PendingIntent.FLAG_UPDATE_CURRENT);
            ((AlarmManager) getSystemService(ALARM_SERVICE))
                    .setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1, pi);
        } else if (!pkg.equals("android") && !pkg.startsWith("com.android.")
                && !pkg.startsWith("com.amazon.tv.quicksettings")) {
            allowStock = false;     // another app (or this launcher) is in front: the stock home is redirected again
        }
    }

    @Override
    public void onInterrupt() {
    }
}
