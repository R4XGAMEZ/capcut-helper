package com.r4x.capcut_helper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.util.TypedValue;

import com.r4x.capcut_helper.shizuku.ShizukuManager;

/**
 * SettingsActivity v5 — Launcher screen showing hybrid status.
 *
 * Shows:
 *   • AccessibilityService: ON/OFF
 *   • Shizuku: Not installed / Installed (not authorized) / Ready
 *   • Overlay: Granted / Denied
 *   • Quick buttons: Open Accessibility, Request Shizuku, Open Overlay
 *
 * Auto-refreshes every 2 seconds — live status update.
 */
public class SettingsActivity extends Activity {

    private static final int BG     = Color.parseColor("#0A0A12");
    private static final int CARD   = Color.parseColor("#14141E");
    private static final int GREEN  = Color.parseColor("#33E573");
    private static final int YELLOW = Color.parseColor("#FFD933");
    private static final int RED    = Color.parseColor("#FF4444");
    private static final int BLUE   = Color.parseColor("#4DA8FF");
    private static final int TEAL   = Color.parseColor("#33D9BF");
    private static final int WHITE  = Color.parseColor("#E5E5F0");
    private static final int DIM    = Color.parseColor("#7A7A88");
    private static final int ORANGE = Color.parseColor("#FF8C33");

    private TextView mA11yDot, mA11yText;
    private TextView mShizukuDot, mShizukuText;
    private TextView mOverlayDot, mOverlayText;
    private TextView mModeText;

    private final Handler mHandler  = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = this::updateStatus;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(40));
        root.setBackgroundColor(BG);

        // Header
        addText(root, "CapCut Helper Bridge", 22, WHITE, Typeface.BOLD);
        addText(root, "v5.0  •  Accessibility + Shizuku Hybrid", 12, DIM, Typeface.NORMAL);
        addSpace(root, 24);

        // ── Status Cards ─────────────────────────────────────────────────

        // Accessibility
        LinearLayout a11yCard = makeCard(root);
        addText(a11yCard, "ACCESSIBILITY SERVICE", 10, DIM, Typeface.BOLD);
        addSpace(a11yCard, 6);
        LinearLayout a11yRow = makeRow(a11yCard);
        mA11yDot  = makeDot(a11yRow, RED);
        mA11yText = makeStatus(a11yRow, "Checking...", WHITE);
        addSpace(a11yCard, 10);
        Button btnA11y = makeButton(a11yCard, "Open Accessibility Settings", BLUE);
        btnA11y.setOnClickListener(v -> startActivity(
            new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        addSpace(root, 12);

        // Shizuku
        LinearLayout shizukuCard = makeCard(root);
        addText(shizukuCard, "SHIZUKU (ADB-LEVEL GESTURES)", 10, DIM, Typeface.BOLD);
        addText(shizukuCard, "Optional — timeline GL views ke liye", 11, DIM, Typeface.ITALIC);
        addSpace(shizukuCard, 6);
        LinearLayout shizukuRow = makeRow(shizukuCard);
        mShizukuDot  = makeDot(shizukuRow, RED);
        mShizukuText = makeStatus(shizukuRow, "Checking...", WHITE);
        addSpace(shizukuCard, 10);
        Button btnShizuku = makeButton(shizukuCard, "Request Shizuku Permission", TEAL);
        btnShizuku.setOnClickListener(v -> {
            ShizukuManager.getInstance().requestPermission();
        });
        Button btnShizukuInstall = makeButton(shizukuCard, "Install Shizuku (Play Store)", DIM);
        btnShizukuInstall.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=moe.shizuku.privileged.api")));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")));
            }
        });

        addSpace(root, 12);

        // Overlay
        LinearLayout overlayCard = makeCard(root);
        addText(overlayCard, "OVERLAY PERMISSION", 10, DIM, Typeface.BOLD);
        addText(overlayCard, "Status dot dikhane ke liye", 11, DIM, Typeface.ITALIC);
        addSpace(overlayCard, 6);
        LinearLayout overlayRow = makeRow(overlayCard);
        mOverlayDot  = makeDot(overlayRow, YELLOW);
        mOverlayText = makeStatus(overlayRow, "Checking...", WHITE);
        addSpace(overlayCard, 10);
        Button btnOverlay = makeButton(overlayCard, "Grant Overlay Permission", ORANGE);
        btnOverlay.setOnClickListener(v -> startActivity(
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));

        addSpace(root, 16);

        // Execution mode indicator
        LinearLayout modeCard = makeCard(root);
        addText(modeCard, "EXECUTION MODE", 10, DIM, Typeface.BOLD);
        addSpace(modeCard, 6);
        mModeText = makeStatus(modeCard, "Detecting...", WHITE);

        addSpace(root, 20);

        // Info
        addText(root,
            "1. Accessibility ON karo (zaroori)\n" +
            "2. Shizuku allow karo (optional, speed ke liye)\n" +
            "3. Bot APK kholo → auto-connect hoga",
            13, DIM, Typeface.NORMAL);

        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        mHandler.postDelayed(mRefresh, 2000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRefresh);
    }

    // ── Status Update ──────────────────────────────────────────────────────

    private void updateStatus() {
        // Accessibility
        boolean a11yOn = HelperAccessibilityService.isServiceRunning();
        setDot(mA11yDot, a11yOn ? GREEN : RED);
        mA11yText.setText(a11yOn
            ? "Service ON ✓ — UI scraping ready"
            : "Service OFF — Accessibility mein enable karo");
        mA11yText.setTextColor(a11yOn ? GREEN : RED);

        // Shizuku
        ShizukuManager shizuku = ShizukuManager.getInstance();
        boolean shizukuInstalled   = shizuku.isInstalled();
        boolean shizukuPermission  = shizuku.checkPermission();
        boolean shizukuReady       = shizuku.isAvailable();

        if (shizukuReady) {
            setDot(mShizukuDot, GREEN);
            mShizukuText.setText("Ready ✓ — ADB-level gestures active");
            mShizukuText.setTextColor(GREEN);
        } else if (shizukuPermission) {
            setDot(mShizukuDot, YELLOW);
            mShizukuText.setText("Permission OK — UserService connecting...");
            mShizukuText.setTextColor(YELLOW);
        } else if (shizukuInstalled) {
            setDot(mShizukuDot, YELLOW);
            mShizukuText.setText("Installed — 'Request Shizuku Permission' dabao");
            mShizukuText.setTextColor(YELLOW);
        } else {
            setDot(mShizukuDot, RED);
            mShizukuText.setText("Not installed (optional)");
            mShizukuText.setTextColor(DIM);
        }

        // Overlay
        boolean overlayGranted = Settings.canDrawOverlays(this);
        setDot(mOverlayDot, overlayGranted ? GREEN : YELLOW);
        mOverlayText.setText(overlayGranted
            ? "Granted ✓" : "Not granted (optional)");
        mOverlayText.setTextColor(overlayGranted ? GREEN : YELLOW);

        // Mode
        if (a11yOn && shizukuReady) {
            mModeText.setText("⚡ HYBRID — Shizuku + Accessibility\n" +
                "Taps: Shizuku (5-15ms)\n" +
                "UI Scrape: Accessibility");
            mModeText.setTextColor(GREEN);
        } else if (a11yOn) {
            mModeText.setText("🔵 ACCESSIBILITY ONLY\n" +
                "Taps: A11y gestures (30-80ms)\n" +
                "Shizuku add karo for speed boost");
            mModeText.setTextColor(BLUE);
        } else {
            mModeText.setText("❌ Service inactive — Accessibility enable karo");
            mModeText.setTextColor(RED);
        }

        // Schedule next refresh
        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, 2000);
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }

    private void addText(ViewGroup parent, String text, int sp, int color, int style) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTypeface(null, style);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(2), 0, dp(2));
        parent.addView(tv, lp);
    }

    private void addSpace(ViewGroup parent, int dp) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(dp)));
    }

    private LinearLayout makeCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        int r = dp(10);
        card.setBackground(makeRoundedBg(CARD, r));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        parent.addView(card, lp);
        return card;
    }

    private android.graphics.drawable.GradientDrawable makeRoundedBg(int color, int radius) {
        android.graphics.drawable.GradientDrawable d =
            new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private LinearLayout makeRow(ViewGroup parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView makeDot(ViewGroup parent, int color) {
        TextView dot = new TextView(this);
        dot.setText("●  ");
        dot.setTextColor(color);
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        dot.setTypeface(null, Typeface.BOLD);
        parent.addView(dot, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return dot;
    }

    private TextView makeStatus(ViewGroup parent, String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(tv);
        return tv;
    }

    private Button makeButton(ViewGroup parent, String label, int color) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setTypeface(null, Typeface.BOLD);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lp.setMargins(0, dp(4), 0, 0);
        parent.addView(btn, lp);
        return btn;
    }

    private void setDot(TextView dot, int color) {
        dot.setTextColor(color);
    }
}
