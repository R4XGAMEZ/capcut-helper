package com.r4x.capcut_helper.shizuku;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * ShizukuManager — Shizuku permission + UserService lifecycle manager.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  SHIZUKU FLOW:                                                   │
 * │                                                                  │
 * │  1. Shizuku app installed + running hona chahiye               │
 * │  2. requestPermission() → user allow kare                       │
 * │  3. bindUserService() → ShizukuUserService start hoti hai       │
 * │  4. mServiceBound = true                                         │
 * │  5. adbTap() / adbSwipe() → shell UID mein execute hota hai     │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * Fallback:
 *   Agar Shizuku nahi hai ya permission deny → isAvailable() = false
 *   ActionExecutor tab Accessibility gesture use karta hai (automatic fallback)
 *
 * Thread safety:
 *   mServiceBound aur mService volatile + synchronized methods se protect hain.
 */
public class ShizukuManager {

    private static final String TAG = "ShizukuManager";
    private static final int    REQUEST_CODE = 0x5A;  // 'Z' for shiZuku

    // Singleton
    private static volatile ShizukuManager sInstance;

    private volatile boolean mServiceBound = false;
    private volatile boolean mPermissionGranted = false;

    // ── Shizuku permission + service listeners ─────────────────────────

    private final Shizuku.OnRequestPermissionResultListener mPermissionListener =
        (requestCode, grantResult) -> {
            mPermissionGranted = (grantResult == PackageManager.PERMISSION_GRANTED);
            Log.i(TAG, "Permission: " + (mPermissionGranted ? "GRANTED ✓" : "DENIED ✗"));
            if (mPermissionGranted) {
                bindService();
            }
        };

    private final Shizuku.OnBinderReceivedListener mBinderListener = () -> {
        Log.i(TAG, "Shizuku binder received.");
        if (checkPermission()) {
            bindService();
        } else {
            requestPermission();
        }
    };

    private final Shizuku.OnBinderDeadListener mBinderDeadListener = () -> {
        Log.w(TAG, "Shizuku binder dead — service restart ka wait karo.");
        mServiceBound = false;
        mPermissionGranted = false;
    };

    // Shizuku UserService connection
    private final Shizuku.UserServiceArgs mUserServiceArgs =
        new Shizuku.UserServiceArgs(
            new ComponentName("com.r4x.capcut_helper",
                              "com.r4x.capcut_helper.shizuku.ShizukuUserService")
        )
        .daemon(false)
        .processNameSuffix("shizuku_service")
        .debuggable(true)
        .version(5);

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mServiceBound = (binder != null && binder.pingBinder());
            Log.i(TAG, "ShizukuUserService connected: " + mServiceBound);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mServiceBound = false;
            Log.w(TAG, "ShizukuUserService disconnected.");
        }
    };

    // ── Singleton access ──────────────────────────────────────────────────

    public static ShizukuManager getInstance() {
        if (sInstance == null) {
            synchronized (ShizukuManager.class) {
                if (sInstance == null) sInstance = new ShizukuManager();
            }
        }
        return sInstance;
    }

    private ShizukuManager() {}

    // ── Init / Destroy ────────────────────────────────────────────────────

    /**
     * HelperAccessibilityService.onServiceConnected() mein call karo.
     */
    public void init() {
        Shizuku.addBinderReceivedListener(mBinderListener);
        Shizuku.addBinderDeadListener(mBinderDeadListener);
        Shizuku.addRequestPermissionResultListener(mPermissionListener);

        // Agar Shizuku pehle se bind hai
        if (Shizuku.pingBinder()) {
            mBinderListener.onBinderReceived();
        }

        Log.i(TAG, "ShizukuManager initialized.");
    }

    /**
     * HelperAccessibilityService.onDestroy() mein call karo.
     */
    public void destroy() {
        try {
            Shizuku.removeBinderReceivedListener(mBinderListener);
            Shizuku.removeBinderDeadListener(mBinderDeadListener);
            Shizuku.removeRequestPermissionResultListener(mPermissionListener);
            if (mServiceBound) {
                Shizuku.unbindUserService(mUserServiceArgs, mServiceConnection, true);
            }
        } catch (Exception e) {
            Log.e(TAG, "destroy error: " + e.getMessage());
        }
        mServiceBound = false;
        Log.i(TAG, "ShizukuManager destroyed.");
    }

    // ── Permission ────────────────────────────────────────────────────────

    public boolean checkPermission() {
        try {
            if (Shizuku.isPreV11()) return false;
            mPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            return mPermissionGranted;
        } catch (Exception e) {
            return false;
        }
    }

    public void requestPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku v11 se pehle ka version — permission support nahi");
                return;
            }
            if (!checkPermission()) {
                Shizuku.requestPermission(REQUEST_CODE);
                Log.i(TAG, "Shizuku permission dialog dikhaya.");
            }
        } catch (Exception e) {
            Log.e(TAG, "requestPermission error: " + e.getMessage());
        }
    }

    // ── Service Binding ───────────────────────────────────────────────────

    private void bindService() {
        try {
            if (!mServiceBound) {
                Shizuku.bindUserService(mUserServiceArgs, mServiceConnection);
                Log.i(TAG, "Binding ShizukuUserService...");
            }
        } catch (Exception e) {
            Log.e(TAG, "bindService error: " + e.getMessage());
        }
    }

    // ── Availability ──────────────────────────────────────────────────────

    /**
     * Kya Shizuku ready hai ADB commands chalane ke liye?
     *
     * ActionExecutor is method se check karta hai before choosing execution path:
     *   true  → ShizukuUserService.adbTap() use karo (fast, GL-safe)
     *   false → AccessibilityService.dispatchGesture() use karo (slower, node-based)
     */
    public boolean isAvailable() {
        try {
            return Shizuku.pingBinder() && mPermissionGranted && mServiceBound;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Shizuku installed hai ya nahi — UI mein status dikhane ke liye.
     */
    public boolean isInstalled() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    // ── Direct ADB execution (fallback without UserService) ───────────────

    /**
     * Agar UserService bind nahi hua lekin permission hai to
     * direct shell execution use karo via rish.
     *
     * Rish Shizuku ka bundled shell binary hai:
     *   /data/user_de/0/moe.shizuku.privileged.api/rish
     *
     * Yeh slower hai UserService se (~5-15ms extra) lekin reliable fallback hai.
     */
    public String runDirectShell(String command) {
        // Permission check
        if (!checkPermission()) return "error:no_shizuku_permission";

        // Try rish
        String[] rishPaths = {
            "/data/user_de/0/moe.shizuku.privileged.api/rish",
            "/data/user_de/0/moe.shizuku.privileged.api/files/rish",
        };

        for (String rishPath : rishPaths) {
            if (new java.io.File(rishPath).exists()) {
                return ShizukuUserService.execShell(rishPath + " -c \"" + command + "\"");
            }
        }

        // rish nahi mila — direct try karo (works on some ROMs)
        return ShizukuUserService.execShell(command);
    }
}
