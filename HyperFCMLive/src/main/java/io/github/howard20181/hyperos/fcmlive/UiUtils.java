package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;

/** Small shared UI helpers (open URL, px conversion, safe-area insets). */
public final class UiUtils {

    private UiUtils() {
    }

    public static void openUrl(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, url, Toast.LENGTH_LONG).show();
        }
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * Safe area at the top of the window.
     *
     * <p>Android 15 (API 35) enforces edge-to-edge for apps targeting 35+: the
     * window always extends behind the system bars, and the status/navigation
     * bar colour APIs have no effect. Every screen therefore has to apply its
     * own insets, which is what these helpers are for. The display cutout is
     * included so the title never slides under a notch in landscape.
     */
    public static int topInset(WindowInsets insets) {
        return insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.displayCutout()).top;
    }

    /**
     * Safe area at the bottom of the window — the union of the navigation-bar
     * and the gesture insets, so the value is right for both navigation modes:
     * with gesture navigation the navigation-bar inset alone can be zero and
     * the home-indicator area is only reported through {@code systemGestures},
     * which is exactly how the last list item ends up under the indicator.
     */
    public static int bottomInset(WindowInsets insets) {
        return insets.getInsets(WindowInsets.Type.systemBars()
                | WindowInsets.Type.systemGestures()).bottom;
    }

    /**
     * One tick of haptic feedback for a tap on a card.
     *
     * <p>{@code performHapticFeedback} rather than the vibrator service: it
     * needs no permission and is already gated on the user's own touch-feedback
     * setting, so switching haptics off in the system silences the app too. On
     * a device without a vibrator — or a ROM that ignores the request — it just
     * returns false, and there is nothing to clean up: no service handle, no
     * permission to revoke.
     */
    public static void tapFeedback(View view) {
        if (view == null) {
            return;
        }
        try {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Throwable ignored) {
            // No vibrator / no haptics on this device: a tap is still a tap.
        }
    }

    public static int statusBarHeight(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
    }
}
