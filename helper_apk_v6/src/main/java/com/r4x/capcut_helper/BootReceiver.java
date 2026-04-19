package com.r4x.capcut_helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BootReceiver — Device restart ke baad user ko remind karo.
 *
 * Note: AccessibilityService auto-start nahi hoti boot pe —
 * Android security requirement hai ki user manually enable kare har boot pe.
 * (Ya Accessibility Settings mein once enable kare — phir system remember karta hai)
 *
 * Yeh receiver sirf log karta hai — koi action nahi.
 * System Accessibility service khud restart karta hai agar pehle enabled thi.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HelperBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.i(TAG, "Boot detected — Accessibility service will auto-start if previously enabled.");
            // Android automatically restarts enabled AccessibilityServices after boot.
            // No manual start needed.
        }
    }
}
