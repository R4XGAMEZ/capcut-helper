package com.r4x.capcut_helper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.r4x.capcut_helper.actions.ActionExecutor;
import com.r4x.capcut_helper.ipc.BridgeSocketServer;
import com.r4x.capcut_helper.shizuku.ShizukuManager;

/**
 * HelperAccessibilityService v6 — Main entry point.
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  HelperAccessibilityService (this)                           │
 * │       │                                                      │
 * │       ├── ShizukuManager.init()   ← Shizuku permission +    │
 * │       │     └── ShizukuUserService    UserService bind       │
 * │       │                                                      │
 * │       ├── ActionExecutor(this)   ← Hybrid execution engine   │
 * │       │     ├── Shizuku path:  5-15ms  (GL-safe)            │
 * │       │     └── A11y path:    30-80ms  (node-based)         │
 * │       │                                                      │
 * │       └── BridgeSocketServer(this, executor)                 │
 * │             ├── LocalSocket IPC (abstract namespace)         │
 * │             ├── Task dispatch → ActionExecutor               │
 * │             └── 200ms hierarchy stream (ScheduledExecutor)   │
 * └──────────────────────────────────────────────────────────────┘
 *
 * v6 fixes vs v5:
 *   - BridgeSocketServer now receives service reference for live hierarchy
 *   - Foreground notification on Android 13+ (prevents OS kill on long sessions)
 *   - onAccessibilityEvent updated to also notify socket server of UI change
 *   - service info set only once on connect (was re-set on every event in some builds)
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class HelperAccessibilityService extends AccessibilityService {

    private static final String TAG              = "HelperService";
    private static final String NOTIF_CHANNEL_ID = "helper_bridge";
    private static final int    NOTIF_ID         = 1001;

    /** SettingsActivity aur BootReceiver ke liye live check. */
    private static volatile HelperAccessibilityService sInstance;
    public  static HelperAccessibilityService getInstance() { return sInstance; }
    public  static boolean isServiceRunning() { return sInstance != null; }

    private ActionExecutor     mExecutor;
    private BridgeSocketServer mSocketServer;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "HelperAccessibilityService v6 connected ✓");

        // ── Accessibility config ─────────────────────────────────────────
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
            AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags =
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS             |
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
            AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.notificationTimeout = 50;
        setServiceInfo(info);

        // ── Foreground notification (Android 13+ kill prevention) ────────
        createNotificationChannel();
        startForegroundIfNeeded();

        // ── Shizuku init (background thread) ─────────────────────────────
        new Thread(() -> {
            try {
                ShizukuManager.getInstance().init();
                Log.i(TAG, "Shizuku manager initialized.");
            } catch (Exception e) {
                Log.w(TAG, "Shizuku init failed (optional): " + e.getMessage());
            }
        }, "ShizukuInit").start();

        // ── ActionExecutor + Socket Server ───────────────────────────────
        mExecutor     = new ActionExecutor(this);
        mSocketServer = new BridgeSocketServer(this, mExecutor);
        mSocketServer.start();

        Log.i(TAG, "Bridge ready. Bot ka wait kar rahe hain...");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // Socket server ko notify karo taki next hierarchy pull fresh ho
        if (mSocketServer != null) {
            mSocketServer.markHierarchyDirty();
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            Log.v(TAG, "Foreground: " + (pkg != null ? pkg : "?"));
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;

        try { ShizukuManager.getInstance().destroy(); } catch (Exception e) {
            Log.w(TAG, "Shizuku destroy error: " + e.getMessage());
        }
        if (mSocketServer != null) mSocketServer.stop();

        Log.i(TAG, "Service destroyed.");
    }

    // ── Notification (foreground service keep-alive) ──────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                NOTIF_CHANNEL_ID,
                "CapCut Helper Bridge",
                NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("Active — listening for bot commands");
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification notif = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("CapCut Helper Bridge")
                .setContentText("Bot se connected — listening on socket")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
            startForeground(NOTIF_ID, notif);
        }
    }
}
