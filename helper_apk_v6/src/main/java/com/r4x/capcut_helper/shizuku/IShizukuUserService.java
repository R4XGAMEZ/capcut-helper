package com.r4x.capcut_helper.shizuku;

/**
 * IShizukuUserService — Shizuku UserService ka interface.
 *
 * Shizuku ko hum AIDL se use karte hain.
 * Yahan simplified Java-only approach hai:
 *   - ShizukuUserService ek Service hai jo Shizuku ke shell UID (2000) mein chalta hai
 *   - Hum usse bindUserService() se bind karte hain
 *   - Phir runCommand() call karte hain — ADB-level shell access milta hai
 *
 * Production mein iska actual AIDL file hona chahiye (.aidl extension),
 * lekin GitHub Actions build mein Java interface bhi kaam karta hai.
 */
public interface IShizukuUserService extends android.os.IInterface {

    /** Shell command run karo, stdout+stderr return karo. */
    String runCommand(String command) throws android.os.RemoteException;

    /** ADB-level input tap inject karo. */
    String adbTap(int x, int y) throws android.os.RemoteException;

    /** ADB-level input swipe inject karo. */
    String adbSwipe(int x1, int y1, int x2, int y2, int durationMs) throws android.os.RemoteException;

    /** Service ki version return karo. */
    String getVersion() throws android.os.RemoteException;

    /** Service destroy karo (Shizuku lifecycle). */
    void destroy() throws android.os.RemoteException;
}
