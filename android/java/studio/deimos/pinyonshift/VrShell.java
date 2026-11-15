package studio.deimos.pinyonshift;

import android.app.Activity;
import android.app.Presentation;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shows an activity's own Android views (the setup and first-start build
 * screens) on a curved screen in VR, through libfhvr_shell.so
 * (src/quest/vr_shell.cpp): the views are drawn into a private virtual
 * display whose surface is a SurfaceTexture on the native thread's GL
 * context; the native side copies each new frame into a standard OpenXR
 * swapchain and reports the controller's pointer, which arrives here as
 * touch, hover and scroll events.
 *
 * Needed on PICO, whose system treats every activity of a VR app as
 * immersive (so a flat window never shows), and used on Meta Quest too, so
 * both run the same screens. The activities carry the VR intent categories
 * in the manifest for this. If the panel can't start, the activity shows its
 * views as a normal window instead.
 */
final class VrShell {
    private static final String TAG = "FHVR-Shell";
    static final int WIDTH = 1920;
    static final int HEIGHT = 1200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong FRAMES = new AtomicLong();
    private static boolean libraryLoaded;

    private static Activity activity;
    private static View content;
    private static VirtualDisplay virtualDisplay;
    private static Presentation presentation;
    private static Surface panelSurface;
    private static boolean started;
    private static int gen;

    // Pointer state
    private static boolean pressed;
    private static long downTime;
    private static View touchTarget;
    private static View hoverTarget;

    private static native void nativeStart(Activity activity, int width, int height, int gen);

    private static native void nativeStop();

    private VrShell() {
    }

    /** Whether the panel is showing [content] (else it is the activity's normal window). */
    static boolean active() {
        return content != null;
    }

    /**
     * Shows [view] on the VR panel, or as the activity's window when the
     * panel library is missing. Call from onCreate; then resume/pause/detach
     * from the activity's lifecycle methods.
     */
    static void attach(Activity owner, View view) {
        if (!libraryLoaded) {
            try {
                System.loadLibrary("fhvr_shell");
                libraryLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "VR panel library missing, using a flat window: " + e.getMessage());
                owner.setContentView(view);
                return;
            }
        }
        if (activity != null && activity != owner) detach(activity);
        activity = owner;
        content = view;
        // The activity's own window stays black behind the VR layer.
        FrameLayout black = new FrameLayout(owner);
        black.setBackgroundColor(Color.BLACK);
        owner.setContentView(black);
    }

    static void resume(Activity owner) {
        if (owner != activity || started) return;
        started = true;
        gen++;
        nativeStart(owner, WIDTH, HEIGHT, gen);
    }

    static void pause(Activity owner) {
        if (owner != activity || !started) return;
        started = false;
        gen++;
        endTouch();
        if (virtualDisplay != null) virtualDisplay.setSurface(null);
        if (panelSurface != null) panelSurface.release();
        panelSurface = null;
        nativeStop();  // releases the SurfaceTexture on the GL thread
    }

    static void detach(Activity owner) {
        if (owner != activity) return;
        pause(owner);
        if (presentation != null) presentation.dismiss();
        presentation = null;
        if (virtualDisplay != null) virtualDisplay.release();
        virtualDisplay = null;
        if (content != null && content.getParent() instanceof ViewGroup)
            ((ViewGroup) content.getParent()).removeView(content);
        activity = null;
        content = null;
    }

    // ---- Called from the native OpenXR thread ----

    /** GL thread, context current: the UI frames land in texId (GL_TEXTURE_EXTERNAL_OES). */
    static SurfaceTexture createPanelTexture(int texId, int width, int height) {
        FRAMES.set(0);
        SurfaceTexture st = new SurfaceTexture(texId);
        st.setDefaultBufferSize(width, height);
        st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture texture) {
                FRAMES.incrementAndGet();
            }
        }, MAIN);
        return st;
    }

    /** GL thread, every frame: latches the newest UI frame; the counter changes with each new frame. */
    static long latchPanelTexture(SurfaceTexture st, float[] matrix) {
        long n = FRAMES.get();
        try {
            st.updateTexImage();
            st.getTransformMatrix(matrix);
            return n;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static void releasePanelTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(null);
        st.release();
    }

    static void onSurfaces(final int g, final SurfaceTexture panel) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (g != gen || !started) return;
                if (panelSurface != null) panelSurface.release();
                panelSurface = new Surface(panel);
                showPanel(panelSurface);
            }
        });
    }

    /** The headset's OpenXR runtime can't run the panel: show the flat window. */
    static void onFailed(final int g) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (g != gen || !started) return;
                Log.e(TAG, "VR panel failed to start, using a flat window");
                Activity owner = activity;
                View view = content;
                detach(owner);
                if (owner != null && view != null) owner.setContentView(view);
            }
        });
    }

    static void onPointer(final int g, int hand, final boolean valid, final float u, final float v,
                          final boolean isPressed, final float scroll) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (g == gen && started) handlePointer(valid, u * WIDTH, v * HEIGHT, isPressed, scroll);
            }
        });
    }

    /** B / Y: nothing, so the screens are never left by accident. */
    static void onBack(int g) {
    }

    // ---- Display ----

    private static final class PanelPresentation extends Presentation {
        PanelPresentation(Activity owner, Display display) {
            super(owner, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (content.getParent() instanceof ViewGroup)
                ((ViewGroup) content.getParent()).removeView(content);
            setContentView(content);
        }
    }

    private static void showPanel(Surface surface) {
        if (activity == null || content == null) return;
        if (virtualDisplay != null) {
            virtualDisplay.setSurface(surface);
            return;
        }
        DisplayManager dm = activity.getSystemService(DisplayManager.class);
        int dpi = activity.getResources().getDisplayMetrics().densityDpi;
        virtualDisplay = dm.createVirtualDisplay("ForzaHorizon-VR panel", WIDTH, HEIGHT, dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        presentation = new PanelPresentation(activity, virtualDisplay.getDisplay());
        presentation.show();
        Log.i(TAG, "panel shown on virtual display " + virtualDisplay.getDisplay().getDisplayId()
                + " (" + dpi + " dpi)");
    }

    // ---- Input ----

    private static View baseWindow() {
        return presentation != null && presentation.getWindow() != null
                ? presentation.getWindow().getDecorView() : null;
    }

    private static void dispatchTouch(View target, int action, float x, float y, long time) {
        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        MotionEvent ev = MotionEvent.obtain(downTime, time, action, x - loc[0], y - loc[1], 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        target.dispatchTouchEvent(ev);
        ev.recycle();
    }

    private static void dispatchMouse(View target, int action, float x, float y, float scroll) {
        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x - loc[0];
        coords.y = y - loc[1];
        if (scroll != 0f) coords.setAxisValue(MotionEvent.AXIS_VSCROLL, scroll);
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(now, now, action, 1,
                new MotionEvent.PointerProperties[] {props}, new MotionEvent.PointerCoords[] {coords},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        target.dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private static void handlePointer(boolean valid, float x, float y, boolean isPressed, float scroll) {
        long now = SystemClock.uptimeMillis();
        if (pressed) {
            View target = touchTarget;
            if (target != null) {
                if (!isPressed) {
                    dispatchTouch(target, MotionEvent.ACTION_UP, x, y, now);
                    touchTarget = null;
                    pressed = false;
                } else if (!valid) {
                    dispatchTouch(target, MotionEvent.ACTION_CANCEL, x, y, now);
                    touchTarget = null;
                    pressed = false;
                } else {
                    dispatchTouch(target, MotionEvent.ACTION_MOVE, x, y, now);
                }
            } else if (!isPressed) {
                pressed = false;
            }
            return;
        }
        if (!valid) {
            if (hoverTarget != null) dispatchMouse(hoverTarget, MotionEvent.ACTION_HOVER_EXIT, x, y, 0f);
            hoverTarget = null;
            return;
        }
        View target = baseWindow();
        if (target == null) return;
        if (isPressed) {
            if (hoverTarget != null) dispatchMouse(hoverTarget, MotionEvent.ACTION_HOVER_EXIT, x, y, 0f);
            hoverTarget = null;
            pressed = true;
            downTime = now;
            touchTarget = target;
            dispatchTouch(target, MotionEvent.ACTION_DOWN, x, y, now);
            return;
        }
        if (target != hoverTarget) {
            if (hoverTarget != null) dispatchMouse(hoverTarget, MotionEvent.ACTION_HOVER_EXIT, x, y, 0f);
            dispatchMouse(target, MotionEvent.ACTION_HOVER_ENTER, x, y, 0f);
            hoverTarget = target;
        }
        dispatchMouse(target, MotionEvent.ACTION_HOVER_MOVE, x, y, 0f);
        if (scroll != 0f) dispatchMouse(target, MotionEvent.ACTION_SCROLL, x, y, scroll * 0.25f);
    }

    private static void endTouch() {
        if (touchTarget != null)
            dispatchTouch(touchTarget, MotionEvent.ACTION_CANCEL, 0f, 0f, SystemClock.uptimeMillis());
        touchTarget = null;
        hoverTarget = null;
        pressed = false;
    }
}
