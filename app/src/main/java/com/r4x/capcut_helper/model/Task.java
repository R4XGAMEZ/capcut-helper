package com.r4x.capcut_helper.model;

import org.json.JSONObject;

/**
 * Task v6 — Python Bot se aane wala ek command.
 *
 * JSON examples:
 *   {"type":"task","action":"tap","x":540,"y":960,"ms":50,"request_id":"1"}
 *   {"type":"task","action":"swipe","x":100,"y":800,"x2":100,"y2":200,"ms":300}
 *   {"type":"task","action":"multi_tap","x":540,"y":960,"count":2,"ms":80}
 *   {"type":"task","action":"pinch","x":400,"y":600,"x2":300,"y2":600,"fx2":600,"fy2":600,"fx3":700,"fy3":600,"ms":300}
 *   {"type":"task","action":"click_text","value":"Split"}
 *   {"type":"task","action":"find_node","value":"timeline"}
 *   {"type":"task","action":"scroll_forward","value":"com.lemon.lv:id/timeline"}
 *   {"type":"task","action":"back"}
 *   {"type":"task","action":"screenshot"}
 *   {"type":"task","action":"adb_tap","x":540,"y":960}
 *   {"type":"task","action":"adb_cmd","value":"wm size"}
 */
public class Task {

    // ── Action constants ──────────────────────────────────────────
    public static final String TAP             = "tap";
    public static final String LONG_TAP        = "long_tap";
    public static final String SWIPE           = "swipe";
    public static final String MULTI_TAP       = "multi_tap";      // v6: rapid N taps
    public static final String PINCH           = "pinch";          // v6: two-finger pinch
    public static final String TEXT            = "text";
    public static final String CLICK_TEXT      = "click_text";
    public static final String FIND_NODE       = "find_node";      // v6: returns bounds
    public static final String SCROLL_FORWARD  = "scroll_forward"; // v6
    public static final String SCROLL_BACKWARD = "scroll_backward";// v6
    public static final String BACK            = "back";
    public static final String HOME            = "home";
    public static final String RECENTS         = "recents";
    public static final String SCREENSHOT      = "screenshot";
    public static final String ADB_TAP         = "adb_tap";
    public static final String ADB_SWIPE       = "adb_swipe";
    public static final String ADB_CMD         = "adb_cmd";

    // ── Fields ────────────────────────────────────────────────────
    public final String  action;
    public final int     x, y, x2, y2;
    public final int     pinchX2, pinchY2, pinchX3, pinchY3; // v6: second finger coords
    public final int     durationMs;
    public final int     count;          // v6: multi_tap count
    public final String  value;
    public final String  requestId;
    public final boolean forceShizuku;

    private Task(String action,
                 int x, int y, int x2, int y2,
                 int pinchX2, int pinchY2, int pinchX3, int pinchY3,
                 int durationMs, int count,
                 String value, String requestId,
                 boolean forceShizuku) {
        this.action       = action;
        this.x            = x;
        this.y            = y;
        this.x2           = x2;
        this.y2           = y2;
        this.pinchX2      = pinchX2;
        this.pinchY2      = pinchY2;
        this.pinchX3      = pinchX3;
        this.pinchY3      = pinchY3;
        this.durationMs   = durationMs;
        this.count        = count;
        this.value        = value;
        this.requestId    = requestId;
        this.forceShizuku = forceShizuku;
    }

    public static Task fromJson(JSONObject j) {
        String action = j.optString("action", TAP);
        boolean forceShizuku = action.startsWith("adb_") || j.optBoolean("shizuku", false);
        return new Task(
            action,
            j.optInt("x",    0),
            j.optInt("y",    0),
            j.optInt("x2",   0),
            j.optInt("y2",   0),
            j.optInt("fx2",  0),  // pinch finger 2 start x
            j.optInt("fy2",  0),  // pinch finger 2 start y
            j.optInt("fx3",  0),  // pinch finger 2 end x
            j.optInt("fy3",  0),  // pinch finger 2 end y
            j.optInt("ms",   50),
            j.optInt("count", 1),
            j.optString("value",      ""),
            j.optString("request_id", ""),
            forceShizuku
        );
    }

    @Override
    public String toString() {
        return "Task{" + action
            + " x=" + x + " y=" + y
            + (x2 > 0 ? " x2=" + x2 + " y2=" + y2 : "")
            + (count > 1 ? " count=" + count : "")
            + (durationMs != 50 ? " ms=" + durationMs : "")
            + (value != null && !value.isEmpty() ? " val='" + value + "'" : "")
            + (forceShizuku ? " [SHIZUKU]" : "")
            + "}";
    }
}
