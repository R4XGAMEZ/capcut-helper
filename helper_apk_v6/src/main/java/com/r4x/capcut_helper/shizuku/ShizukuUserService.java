package com.r4x.capcut_helper.shizuku;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * ShizukuUserService — Shizuku ke shell user (UID 2000) mein chalne wali service.
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  ARCHITECTURE                                                    ║
 * ║                                                                  ║
 * ║  HelperAccessibilityService (UID: app)                          ║
 * ║    │                                                             ║
 * ║    ├── bindUserService(ShizukuUserService) ───► Shizuku         ║
 * ║    │                                              │              ║
 * ║    │                                   ShizukuUserService        ║
 * ║    │                                   (UID: shell = 2000)       ║
 * ║    │                                              │              ║
 * ║    │◄──── IShizukuUserService binder ────────────┘              ║
 * ║    │                                                             ║
 * ║    └── adbTap(x,y) → Runtime.exec("input tap x y") ← ADB level ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * Kyun zaruri hai yeh?
 *   - AccessibilityService gesture: sirf standard Android views pe kaam karta hai
 *   - CapCut ka timeline/canvas: custom GL/SurfaceView — gesture block hoti hai
 *   - Shizuku shell tap: kernel-level input inject → GL view mein bhi kaam karta hai
 *   - Dono milake: 100% coverage — koi bhi screen, koi bhi element
 *
 * IMPORTANT: Yeh service directly instantiate mat karo.
 * Shizuku.bindUserService() ke through hi access karo.
 */
public class ShizukuUserService extends Service {

    private static final String TAG = "ShizukuUserService";
    private static final int    CMD_TIMEOUT_MS = 5000;

    // ── Binder Stub ──────────────────────────────────────────────────────────

    private final android.os.Binder mBinder = new android.os.Binder() {

        @Override
        protected boolean onTransact(int code, android.os.Parcel data,
                                     android.os.Parcel reply, int flags)
                throws android.os.RemoteException {

            // Simple transact protocol — code == 1: runCommand
            // Yeh simplified approach hai. Production mein AIDL use karo.
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface("com.r4x.capcut_helper.shizuku.IShizukuUserService");
                String cmd = data.readString();
                String result = execShell(cmd);
                if (reply != null) {
                    reply.writeNoException();
                    reply.writeString(result);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    // ── Service Lifecycle ────────────────────────────────────────────────────

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "ShizukuUserService bound — UID: " + android.os.Process.myUid());
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ShizukuUserService created — shell UID: " + android.os.Process.myUid());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "ShizukuUserService destroyed.");
    }

    // ── Shell Execution ──────────────────────────────────────────────────────

    /**
     * Shell command run karo (shell UID 2000 ke roop mein).
     *
     * Shell UID 2000 ke capabilities:
     *   - input tap x y         → touch inject (any view, including GL)
     *   - input swipe ...       → swipe inject
     *   - input keyevent N      → key inject
     *   - input text "..."      → text inject
     *   - am start -n ...       → app launch
     *   - am force-stop ...     → app kill
     *   - pm list packages      → installed apps
     *   - dumpsys window        → window info
     *   - screencap -p file     → screenshot
     *   - wm size               → screen resolution
     *
     * NOTE: Root nahi chahiye — shell UID hi kaafi hai sab ke liye.
     */
    public static String execShell(String command) {
        try {
            Process proc = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", command}
            );

            // Stdout read karo
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append("\n");
                }
            }

            // Stderr bhi capture karo
            StringBuilder err = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    err.append(line).append("\n");
                }
            }

            // Timeout ke saath wait karo
            boolean done = proc.waitFor(CMD_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
                return "error:timeout";
            }

            int exitCode = proc.exitValue();
            String output = out.toString().trim();
            String errStr = err.toString().trim();

            if (exitCode != 0 && output.isEmpty()) {
                return "error:exit(" + exitCode + ")" + (errStr.isEmpty() ? "" : ":" + errStr);
            }

            Log.d(TAG, "$ " + command + " → [" + exitCode + "] " + output);
            return output.isEmpty() ? "ok" : output;

        } catch (Exception e) {
            Log.e(TAG, "execShell error: " + e.getMessage());
            return "error:" + e.getMessage();
        }
    }

    // ── Convenience Methods ──────────────────────────────────────────────────

    public static String adbTap(int x, int y) {
        return execShell("input tap " + x + " " + y);
    }

    public static String adbSwipe(int x1, int y1, int x2, int y2, int ms) {
        return execShell("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
    }

    public static String adbKeyEvent(int keycode) {
        return execShell("input keyevent " + keycode);
    }

    public static String adbText(String text) {
        // Spaces ko %s se replace karo (input text ka requirement)
        String safe = text.replace(" ", "%s");
        return execShell("input text \"" + safe + "\"");
    }
}
