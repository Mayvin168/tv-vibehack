package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The hands of the phone remote. Once the user enables this service (Settings, Accessibility),
 * it can move an on-screen pointer, tap and scroll anywhere via gestures, press Back/Home
 * globally, move D-pad focus through the accessibility tree, and type into the focused field.
 */
public class TvAccessibilityService extends AccessibilityService {

    private static volatile TvAccessibilityService instance;

    public static TvAccessibilityService get() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    private WindowManager wm;
    private PointerView pointer;
    private WindowManager.LayoutParams pointerParams;
    private boolean pointerShown;
    private float px, py;
    private int screenW, screenH;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hidePointer = this::removePointer;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        screenW = size.x;
        screenH = size.y;
        px = screenW / 2f;
        py = screenH / 2f;
        RemoteServer.start(this);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        removePointer();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events are only the binding's heartbeat; control comes from the phone.
    }

    @Override
    public void onInterrupt() {
    }

    // ---- pointer overlay ---------------------------------------------------------------------

    private void showPointer() {
        if (pointer == null) {
            pointer = new PointerView(this);
            float d = getResources().getDisplayMetrics().density;
            pointerParams = new WindowManager.LayoutParams(
                    (int) (30 * d), (int) (30 * d),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            pointerParams.gravity = Gravity.TOP | Gravity.START;
        }
        pointerParams.x = (int) px;
        pointerParams.y = (int) py;
        try {
            if (!pointerShown) {
                wm.addView(pointer, pointerParams);
                pointerShown = true;
            } else {
                wm.updateViewLayout(pointer, pointerParams);
            }
        } catch (Exception ignored) {
        }
        handler.removeCallbacks(hidePointer);
        handler.postDelayed(hidePointer, 6000);
    }

    private void removePointer() {
        if (pointerShown && pointer != null) {
            try {
                wm.removeView(pointer);
            } catch (Exception ignored) {
            }
            pointerShown = false;
        }
    }

    // ---- remote actions (called from the server thread) ----------------------------------------

    public void movePointer(float dx, float dy) {
        handler.post(() -> {
            double mag = Math.hypot(dx, dy);
            double gain = 1.25 + Math.min(2.1, mag / 34.0);
            px = clamp(px + (float) (dx * gain), 0, screenW - 1);
            py = clamp(py + (float) (dy * gain), 0, screenH - 1);
            showPointer();
        });
    }

    public void tapPointer() {
        handler.post(() -> {
            showPointer();
            gesture((int) px, (int) py, (int) px, (int) py, 70);
        });
    }

    public void scrollPointer(float dx, float dy) {
        handler.post(() -> {
            showPointer();
            int x2 = (int) clamp(px + dx, 0, screenW - 1);
            int y2 = (int) clamp(py + dy, 0, screenH - 1);
            gesture((int) px, (int) py, x2, y2, 170);
        });
    }

    private void gesture(int x1, int y1, int x2, int y2, int durationMs) {
        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build();
            dispatchGesture(gesture, null, null);
        } catch (Exception ignored) {
        }
    }

    public void pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    public void pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** D-pad navigation through the accessibility tree (best effort; web content ignores it,
     *  which is what the touchpad is for). */
    public void pressDpad(int direction) {
        handler.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                AccessibilityNodeInfo from = focused != null ? focused : root;
                AccessibilityNodeInfo target = from.focusSearch(direction);
                if (target != null) {
                    target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                }
            } catch (Exception ignored) {
            }
        });
    }

    public void pressOk() {
        handler.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo focused = null;
            if (root != null) {
                try {
                    focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
                    if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                } catch (Exception ignored) {
                }
            }
            if (focused != null && focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            if (focused != null) {
                Rect r = new Rect();
                focused.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    gesture(r.centerX(), r.centerY(), r.centerX(), r.centerY(), 70);
                    return;
                }
            }
            tapPointer();
        });
    }

    /** Replaces the text of the currently focused input field with what the phone typed. */
    public void setText(String text) {
        handler.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                AccessibilityNodeInfo input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (input != null && input.isEditable()) {
                    Bundle args = new Bundle();
                    args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** Current text of the focused input, so the phone can backspace accurately. */
    public String focusedText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            AccessibilityNodeInfo input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (input != null && input.isEditable() && input.getText() != null) {
                return input.getText().toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
