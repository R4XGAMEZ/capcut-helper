package com.r4x.capcut_helper.actions;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.RequiresApi;

import com.r4x.capcut_helper.model.Task;
import com.r4x.capcut_helper.shizuku.ShizukuManager;
import com.r4x.capcut_helper.shizuku.ShizukuUserService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ActionExecutor v6 — Hybrid Shizuku + Accessibility execution engine.
 *
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║  v6 IMPROVEMENTS:                                                    ║
 * ║                                                                      ║
 * ║  1. Node recycling pool                                              ║
 * ║     → nodeToJson now tracks all created nodes in a list and         ║
 * ║       recycles them all at the end (prevents AccessibilityNodeInfo   ║
 * ║       pool exhaustion on 1-hour sessions)                           ║
 * ║                                                                      ║
 * ║  2. Multi-tap action (Task.MULTI_TAP)                                ║
 * ║     → Rapid-fire N taps at same location (e.g., double-tap to cut) ║
 * ║                                                                      ║
 * ║  3. Pinch gesture (Task.PINCH)                                       ║
 * ║     → Two-finger pinch/zoom using A11y multi-stroke                 ║
 * ║                                                                      ║
 * ║  4. scroll_forward / scroll_backward actions                         ║
 * ║     → AccessibilityNodeInfo.ACTION_SCROLL_FORWARD/BACKWARD          ║
 * ║                                                                      ║
 * ║  5. find_node action                                                 ║
 * ║     → Returns bounds of a node matching text/resourceId             ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class ActionExecutor {

    private static final String TAG             = "ActionExecutor";
    private static final int    GESTURE_TIMEOUT = 3000;
    private static final int    MAX_DEPTH       = 12;
    private static final int    MAX_CHILDREN    = 30;

    private volatile AccessibilityService mService;
    private final ShizukuManager          mShizuku;

    public ActionExecutor(AccessibilityService service) {
        this.mService = service;
        this.mShizuku = ShizukuManager.getInstance();
    }

    public void setService(AccessibilityService service) {
        this.mService = service;
    }

    // ══════════════════════════════════════════════════════════════
    //  MAIN DISPATCH
    // ══════════════════════════════════════════════════════════════

    public String execute(Task task) {
        Log.d(TAG, "Execute: " + task);

        switch (task.action) {
            case Task.TAP:
                return tap(task.x, task.y, task.durationMs, task.forceShizuku);

            case Task.LONG_TAP:
                return tap(task.x, task.y, Math.max(task.durationMs, 600), task.forceShizuku);

            case Task.SWIPE:
                return swipe(task.x, task.y, task.x2, task.y2,
                             task.durationMs, task.forceShizuku);

            case Task.MULTI_TAP:
                return multiTap(task.x, task.y, task.count, task.durationMs);

            case Task.PINCH:
                return pinch(task.x, task.y, task.x2, task.y2,
                             task.pinchX2, task.pinchY2, task.pinchX3, task.pinchY3,
                             task.durationMs);

            // ── Force Shizuku path ──────────────────────────────
            case Task.ADB_TAP:
                return shizukuTap(task.x, task.y);

            case Task.ADB_SWIPE:
                return shizukuSwipe(task.x, task.y, task.x2, task.y2, task.durationMs);

            case Task.ADB_CMD:
                return ShizukuUserService.execShell(task.value);

            // ── Node-based actions ──────────────────────────────
            case Task.TEXT:
                return setText(task.value);

            case Task.CLICK_TEXT:
                return clickByText(task.value);

            case Task.FIND_NODE:
                return findNodeBounds(task.value);

            case Task.SCROLL_FORWARD:
                return scrollByResourceId(task.value, true);

            case Task.SCROLL_BACKWARD:
                return scrollByResourceId(task.value, false);

            // ── Global actions ──────────────────────────────────
            case Task.BACK:
                if (mService != null) {
                    mService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    return "back_ok";
                }
                return shizukuKeyEvent(4);

            case Task.HOME:
                if (mService != null) {
                    mService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    return "home_ok";
                }
                return shizukuKeyEvent(3);

            case Task.RECENTS:
                if (mService != null) {
                    mService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                }
                return "recents_ok";

            case Task.SCREENSHOT:
                String h = getHierarchyJson();
                return h != null ? h : "error:no_hierarchy";

            default:
                return "error:unknown_action_" + task.action;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HYBRID TAP
    // ══════════════════════════════════════════════════════════════

    private String tap(int x, int y, int ms, boolean forceShizuku) {
        if (forceShizuku || mShizuku.isAvailable()) {
            String r = shizukuTap(x, y);
            if (!r.startsWith("error")) return r;
            Log.w(TAG, "Shizuku tap failed, falling back: " + r);
        }
        return accessibilityTap(x, y, ms);
    }

    private String shizukuTap(int x, int y) {
        String result = ShizukuUserService.adbTap(x, y);
        return result.startsWith("error") ? result : "shizuku_tapped:(" + x + "," + y + ")";
    }

    private String accessibilityTap(int x, int y, int ms) {
        if (mService == null) return "error:service_null";
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(ms, 1)))
                .build();
            return dispatchGesture(g) ? "a11y_tapped:(" + x + "," + y + ")" : "error:tap_cancelled";
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MULTI-TAP (rapid N taps at same point)
    // ══════════════════════════════════════════════════════════════

    private String multiTap(int x, int y, int count, int intervalMs) {
        if (count <= 0) return "error:count_zero";
        int actualInterval = Math.max(intervalMs, 30);

        if (mShizuku.isAvailable()) {
            // Shizuku path — fastest
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String r = ShizukuUserService.adbTap(x, y);
                if (r.startsWith("error")) return r;
                if (i < count - 1) {
                    try { Thread.sleep(actualInterval); } catch (InterruptedException ignored) {}
                }
            }
            return "multi_tapped:" + count + "x(" + x + "," + y + ")";
        }

        // A11y multi-stroke path
        if (mService == null) return "error:service_null";
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            for (int i = 0; i < Math.min(count, 10); i++) {  // A11y max 10 strokes
                Path path = new Path();
                path.moveTo(x, y);
                builder.addStroke(new GestureDescription.StrokeDescription(
                    path, (long) i * (actualInterval + 10), 10));
            }
            boolean ok = dispatchGesture(builder.build());
            return ok ? "a11y_multi_tapped:" + count + "x" : "error:multi_tap_cancelled";
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  PINCH GESTURE (two-finger zoom)
    // ══════════════════════════════════════════════════════════════

    /**
     * Two-finger pinch.
     * Finger 1: (x1,y1) → (x2,y2)
     * Finger 2: (fx2,fy2) → (fx3,fy3)
     *
     * Pinch in (zoom out): fingers move toward center
     * Pinch out (zoom in): fingers move away from center
     */
    private String pinch(int x1, int y1, int x2, int y2,
                         int fx2, int fy2, int fx3, int fy3,
                         int durationMs) {
        if (mService == null) return "error:service_null";
        try {
            Path finger1 = new Path();
            finger1.moveTo(x1, y1);
            finger1.lineTo(x2, y2);

            Path finger2 = new Path();
            finger2.moveTo(fx2, fy2);
            finger2.lineTo(fx3, fy3);

            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(finger1, 0, durationMs))
                .addStroke(new GestureDescription.StrokeDescription(finger2, 0, durationMs))
                .build();

            return dispatchGesture(g) ? "pinch_ok" : "error:pinch_cancelled";
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HYBRID SWIPE
    // ══════════════════════════════════════════════════════════════

    private String swipe(int x1, int y1, int x2, int y2, int ms, boolean forceShizuku) {
        if (forceShizuku || mShizuku.isAvailable()) {
            String r = shizukuSwipe(x1, y1, x2, y2, ms);
            if (!r.startsWith("error")) return r;
            Log.w(TAG, "Shizuku swipe failed, falling back: " + r);
        }
        return accessibilitySwipe(x1, y1, x2, y2, ms);
    }

    private String shizukuSwipe(int x1, int y1, int x2, int y2, int ms) {
        String result = ShizukuUserService.adbSwipe(x1, y1, x2, y2, ms);
        return result.startsWith("error") ? result
            : "shizuku_swiped:(" + x1 + "," + y1 + ")→(" + x2 + "," + y2 + ")";
    }

    private String accessibilitySwipe(int x1, int y1, int x2, int y2, int ms) {
        if (mService == null) return "error:service_null";
        try {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, ms))
                .build();
            return dispatchGesture(g)
                ? "a11y_swiped:(" + x1 + "," + y1 + ")→(" + x2 + "," + y2 + ")"
                : "error:swipe_cancelled";
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GESTURE DISPATCH HELPER
    // ══════════════════════════════════════════════════════════════

    private boolean dispatchGesture(GestureDescription g) throws InterruptedException {
        if (mService == null) return false;
        CountDownLatch latch  = new CountDownLatch(1);
        final boolean[] ok    = {false};
        mService.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g2) {
                ok[0] = true; latch.countDown();
            }
            @Override public void onCancelled(GestureDescription g2) {
                latch.countDown();
            }
        }, null);
        return latch.await(GESTURE_TIMEOUT, TimeUnit.MILLISECONDS) && ok[0];
    }

    // ══════════════════════════════════════════════════════════════
    //  SHIZUKU KEY EVENTS
    // ══════════════════════════════════════════════════════════════

    private String shizukuKeyEvent(int keycode) {
        return ShizukuUserService.adbKeyEvent(keycode);
    }

    // ══════════════════════════════════════════════════════════════
    //  NODE-BASED ACTIONS
    // ══════════════════════════════════════════════════════════════

    private String setText(String text) {
        if (mService == null) return ShizukuUserService.adbText(text);
        AccessibilityNodeInfo root = mService.getRootInActiveWindow();
        if (root == null) return ShizukuUserService.adbText(text);
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) return ShizukuUserService.adbText(text);
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ? "text_set" : "error:set_text_failed";
        } finally {
            root.recycle();
        }
    }

    private String clickByText(String query) {
        if (mService == null) return "error:service_null";
        AccessibilityNodeInfo root = mService.getRootInActiveWindow();
        if (root == null) return "error:no_root";
        List<AccessibilityNodeInfo> pool = new ArrayList<>();
        try {
            AccessibilityNodeInfo target = findByText(root, query.toLowerCase().trim(), pool);
            if (target == null) return "not_found:" + query;

            AccessibilityNodeInfo node = target;
            while (node != null && !node.isClickable()) {
                AccessibilityNodeInfo parent = node.getParent();
                if (parent == null) break;
                pool.add(node);
                node = parent;
            }

            if (node != null && node.isClickable() &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "clicked:" + query;
            }

            // Fallback: gesture tap at node center
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            return tap(bounds.centerX(), bounds.centerY(), 50, false);

        } finally {
            root.recycle();
            for (AccessibilityNodeInfo n : pool) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private String findNodeBounds(String query) {
        if (mService == null) return "error:service_null";
        AccessibilityNodeInfo root = mService.getRootInActiveWindow();
        if (root == null) return "error:no_root";
        List<AccessibilityNodeInfo> pool = new ArrayList<>();
        try {
            AccessibilityNodeInfo target = findByText(root, query.toLowerCase().trim(), pool);
            if (target == null) return "not_found:" + query;
            Rect b = new Rect();
            target.getBoundsInScreen(b);
            return "bounds:" + b.left + "," + b.top + "," + b.right + "," + b.bottom
                + " cx=" + b.centerX() + " cy=" + b.centerY();
        } finally {
            root.recycle();
            for (AccessibilityNodeInfo n : pool) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
        }
    }

    private String scrollByResourceId(String resourceId, boolean forward) {
        if (mService == null) return "error:service_null";
        AccessibilityNodeInfo root = mService.getRootInActiveWindow();
        if (root == null) return "error:no_root";
        try {
            AccessibilityNodeInfo target = resourceId != null && !resourceId.isEmpty()
                ? findByResourceId(root, resourceId)
                : findScrollable(root);
            if (target == null) return "not_found:scrollable";
            int action = forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            return target.performAction(action) ? "scrolled_ok" : "error:scroll_failed";
        } finally {
            root.recycle();
        }
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo node,
                                              String q,
                                              List<AccessibilityNodeInfo> pool) {
        if (node == null) return null;
        String t  = node.getText() != null ? node.getText().toString().toLowerCase() : "";
        String cd = node.getContentDescription() != null
                    ? node.getContentDescription().toString().toLowerCase() : "";
        if (t.contains(q) || cd.contains(q)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            pool.add(c);
            AccessibilityNodeInfo r = findByText(c, q, pool);
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findByResourceId(AccessibilityNodeInfo node, String id) {
        if (node == null) return null;
        String rid = node.getViewIdResourceName();
        if (rid != null && rid.contains(id)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo r = findByResourceId(c, id);
            if (r != null) return r;
            c.recycle();
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c == null) continue;
            AccessibilityNodeInfo r = findScrollable(c);
            if (r != null) return r;
            c.recycle();
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    //  UI HIERARCHY SERIALIZATION
    //  NODE POOL RECYCLING — prevents AccessibilityNodeInfo pool
    //  exhaustion on 1-hour sessions (18000+ hierarchy fetches)
    // ══════════════════════════════════════════════════════════════

    public String getHierarchyJson() {
        if (mService == null) return null;
        AccessibilityNodeInfo root = mService.getRootInActiveWindow();
        if (root == null) return null;

        // Pool tracks ALL child nodes created during traversal
        // All recycled in finally — zero leaks regardless of exceptions
        List<AccessibilityNodeInfo> nodePool = new ArrayList<>(256);

        try {
            return nodeToJson(root, 0, nodePool).toString();
        } catch (Exception e) {
            Log.e(TAG, "getHierarchyJson error: " + e.getMessage());
            return null;
        } finally {
            // Recycle all tracked child nodes
            for (AccessibilityNodeInfo n : nodePool) {
                try { n.recycle(); } catch (Exception ignored) {}
            }
            // Recycle root
            root.recycle();
        }
    }

    private JSONObject nodeToJson(AccessibilityNodeInfo n,
                                   int depth,
                                   List<AccessibilityNodeInfo> pool) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("text",        safe(n.getText()));
        obj.put("contentDesc", safe(n.getContentDescription()));
        obj.put("className",   safe(n.getClassName()));
        obj.put("resourceId",  safe(n.getViewIdResourceName()));
        obj.put("clickable",   n.isClickable());
        obj.put("visible",     n.isVisibleToUser());
        obj.put("enabled",     n.isEnabled());
        obj.put("scrollable",  n.isScrollable());
        obj.put("focusable",   n.isFocusable());
        obj.put("checkable",   n.isCheckable());
        obj.put("checked",     n.isChecked());
        obj.put("editable",    n.isEditable());

        Rect b = new Rect();
        n.getBoundsInScreen(b);
        obj.put("x",      b.left);
        obj.put("y",      b.top);
        obj.put("width",  b.width());
        obj.put("height", b.height());

        JSONArray children = new JSONArray();
        if (depth < MAX_DEPTH) {
            int count = Math.min(n.getChildCount(), MAX_CHILDREN);
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) {
                    pool.add(child);  // Track for recycling
                    children.put(nodeToJson(child, depth + 1, pool));
                }
            }
        }
        obj.put("children", children);
        return obj;
    }

    private String safe(CharSequence cs) {
        return cs != null ? cs.toString() : "";
    }
}
