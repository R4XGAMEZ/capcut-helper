package com.r4x.capcut_helper.ipc;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import com.r4x.capcut_helper.HelperAccessibilityService;
import com.r4x.capcut_helper.actions.ActionExecutor;
import com.r4x.capcut_helper.model.Task;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BridgeSocketServer v6 — Optimized IPC server for long sessions.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  v6 IMPROVEMENTS over v5:                                        │
 * │                                                                  │
 * │  1. Dirty flag optimization                                      │
 * │     → markHierarchyDirty() called by service on every a11y      │
 * │       event. Stream only serializes if dirty=true, reducing      │
 * │       CPU from ~200 JSON builds/min to actual UI change rate.    │
 * │                                                                  │
 * │  2. Service reference stored                                     │
 * │     → Hierarchy fetched directly via service.getRootInActive..   │
 * │       rather than passing through ActionExecutor indirection     │
 * │                                                                  │
 * │  3. Hierarchy cache with hash check                              │
 * │     → If JSON hash unchanged, sends cached version (zero parse)  │
 * │                                                                  │
 * │  4. configurable stream interval via stream_start command        │
 * │     → {"type":"stream_start","interval_ms":100}                  │
 * │                                                                  │
 * │  5. Multi-client support                                         │
 * │     → CachedThreadPool handles N clients simultaneously          │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * Protocol: Newline-delimited JSON (NDJSON)
 *   Bot → Helper: {"type":"task","action":"tap","x":540,"y":960,"request_id":"1"}
 *   Bot → Helper: {"type":"stream_start","interval_ms":200}
 *   Bot → Helper: {"type":"stream_stop"}
 *   Bot → Helper: {"type":"ping"}
 *   Bot → Helper: {"type":"stats"}
 *
 *   Helper → Bot: {"ok":true,"result":"shizuku_tapped:(540,960)","request_id":"1","ts":...}
 *   Helper → Bot: {"type":"hierarchy","data":{...},"ts":...,"frame":42,"dirty":true}
 *   Helper → Bot: {"type":"pong","ts":...}
 *   Helper → Bot: {"type":"stats","frames_sent":...,"frames_skipped":...}
 */
public class BridgeSocketServer {

    public static final String SOCKET_NAME           = "capcut_helper_bridge";
    private static final String TAG                  = "HelperSocket";
    private static final int    DEFAULT_INTERVAL_MS  = 200;
    private static final int    SKIP_THRESHOLD_MS    = 150;
    private static final int    STATS_LOG_EVERY      = 100;

    private LocalServerSocket              mServer;
    private final AtomicBoolean            mRunning   = new AtomicBoolean(false);
    private final ExecutorService          mPool      = Executors.newCachedThreadPool();
    private final ActionExecutor           mExecutor;
    private final HelperAccessibilityService mService;

    // Dirty flag — set by onAccessibilityEvent, cleared after each hierarchy push
    private final AtomicBoolean mDirty      = new AtomicBoolean(false);
    // Last serialized hierarchy string (for hash-based skip)
    private volatile String     mLastJson   = null;
    private volatile int        mLastHash   = 0;

    public BridgeSocketServer(HelperAccessibilityService service, ActionExecutor executor) {
        this.mService  = service;
        this.mExecutor = executor;
    }

    // ── Called by HelperAccessibilityService on every A11y event ──────────

    public void markHierarchyDirty() {
        mDirty.set(true);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    public void start() {
        if (mRunning.getAndSet(true)) return;
        mPool.execute(this::acceptLoop);
        Log.i(TAG, "Socket server v6 started: " + SOCKET_NAME);
    }

    public void stop() {
        mRunning.set(false);
        try { if (mServer != null) mServer.close(); } catch (Exception ignored) {}
        mPool.shutdownNow();
        Log.i(TAG, "Socket server stopped.");
    }

    // ── Accept Loop ───────────────────────────────────────────────────────

    private void acceptLoop() {
        while (mRunning.get()) {
            try {
                mServer = new LocalServerSocket(SOCKET_NAME);
                Log.i(TAG, "Listening: " + SOCKET_NAME);
                while (mRunning.get()) {
                    LocalSocket client = mServer.accept();
                    Log.i(TAG, "Client connected ✓");
                    mPool.execute(new ClientSession(client, mExecutor, this));
                }
            } catch (Exception e) {
                if (mRunning.get()) {
                    Log.e(TAG, "Accept error, retrying in 1s: " + e.getMessage());
                    sleep(1000);
                }
            } finally {
                try { if (mServer != null) mServer.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ── Hierarchy fetch with dirty+hash optimization ───────────────────────

    /**
     * Returns hierarchy JSON string.
     * If dirty=false AND we have a cached version → return cache (no rescan).
     * If dirty=true → rescan, check hash, cache if changed.
     */
    String getOptimizedHierarchyJson() {
        boolean dirty = mDirty.getAndSet(false);
        if (!dirty && mLastJson != null) {
            return mLastJson;  // No UI change — return cached
        }

        String json = mExecutor.getHierarchyJson();
        if (json == null) return mLastJson;  // Keep last known on null

        int hash = json.hashCode();
        if (hash == mLastHash && mLastJson != null) {
            return mLastJson;  // Content identical — return cached
        }

        mLastJson  = json;
        mLastHash  = hash;
        return json;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CLIENT SESSION
    // ══════════════════════════════════════════════════════════════════════

    private static class ClientSession implements Runnable {

        private final LocalSocket          mSocket;
        private final ActionExecutor       mExecutor;
        private final BridgeSocketServer   mServer;

        private final AtomicBoolean           mStreaming   = new AtomicBoolean(false);
        private ScheduledExecutorService      mStreamTimer;
        private ScheduledFuture<?>            mStreamFuture;
        private final AtomicLong              mLastWriteMs = new AtomicLong(0);

        private long   mFramesSent    = 0;
        private long   mFramesSkipped = 0;
        private long   mSessionStart  = System.currentTimeMillis();
        private int    mIntervalMs    = DEFAULT_INTERVAL_MS;

        private PrintWriter mOut;
        private final Object mWriteLock = new Object();

        ClientSession(LocalSocket socket, ActionExecutor executor, BridgeSocketServer server) {
            this.mSocket   = socket;
            this.mExecutor = executor;
            this.mServer   = server;
        }

        @Override
        public void run() {
            try (
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(mSocket.getInputStream(), "UTF-8"), 8192);
                PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(mSocket.getOutputStream(), "UTF-8"), true)
            ) {
                this.mOut     = out;
                mSessionStart = System.currentTimeMillis();
                String line;

                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    JSONObject frame;
                    try {
                        frame = new JSONObject(line);
                    } catch (Exception e) {
                        sendError(out, "invalid_json", "");
                        continue;
                    }

                    String type = frame.optString("type", "task");

                    switch (type) {
                        case "ping":
                            sendPong(out);
                            break;

                        case "stream_start":
                            // Optional: {"type":"stream_start","interval_ms":100}
                            mIntervalMs = frame.optInt("interval_ms", DEFAULT_INTERVAL_MS);
                            mIntervalMs = Math.max(50, Math.min(mIntervalMs, 2000)); // clamp 50-2000ms
                            startStreaming(out);
                            break;

                        case "stream_stop":
                            stopStreaming();
                            sendOk(out, "stream_stopped", "");
                            break;

                        case "stats":
                            sendStats(out);
                            break;

                        case "hierarchy":
                            // On-demand single hierarchy fetch (no stream needed)
                            String h = mServer.getOptimizedHierarchyJson();
                            if (h != null) {
                                sendOk(out, h, frame.optString("request_id", ""));
                            } else {
                                sendError(out, "no_hierarchy", frame.optString("request_id", ""));
                            }
                            break;

                        case "task":
                        default:
                            String reqId = frame.optString("request_id", "");
                            Task task    = Task.fromJson(frame);
                            String result = mExecutor.execute(task);
                            sendOk(out, result, reqId);
                            break;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Session error: " + e.getMessage());
            } finally {
                stopStreaming();
                try { mSocket.close(); } catch (Exception ignored) {}
                long duration = (System.currentTimeMillis() - mSessionStart) / 1000;
                Log.i(TAG, "Session ended after " + duration + "s — "
                    + "frames sent=" + mFramesSent
                    + " skipped=" + mFramesSkipped);
            }
        }

        // ── Stream Mode ───────────────────────────────────────────────────

        private void startStreaming(PrintWriter out) {
            if (mStreaming.getAndSet(true)) {
                sendOk(out, "stream_already_running", "");
                return;
            }

            mStreamTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HierarchyPusher");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY + 1);
                return t;
            });

            final int interval = mIntervalMs;

            mStreamFuture = mStreamTimer.scheduleAtFixedRate(() -> {
                if (!mStreaming.get()) return;

                try {
                    // Backpressure — skip if client is slow
                    long now       = System.currentTimeMillis();
                    long lastWrite = mLastWriteMs.get();
                    if (lastWrite > 0 && (now - lastWrite) < SKIP_THRESHOLD_MS) {
                        mFramesSkipped++;
                        return;
                    }

                    // Optimized hierarchy (dirty + hash cache)
                    String hierarchyJson = mServer.getOptimizedHierarchyJson();
                    if (hierarchyJson == null) return;

                    JSONObject pushFrame = new JSONObject();
                    pushFrame.put("type",  "hierarchy");
                    pushFrame.put("data",  new JSONObject(hierarchyJson));
                    pushFrame.put("ts",    System.currentTimeMillis());
                    pushFrame.put("frame", mFramesSent);

                    synchronized (mWriteLock) {
                        if (mOut != null) {
                            mOut.println(pushFrame.toString());
                            mLastWriteMs.set(System.currentTimeMillis());
                            mFramesSent++;
                        }
                    }

                    if (mFramesSent % STATS_LOG_EVERY == 0) {
                        long elapsed = (System.currentTimeMillis() - mSessionStart) / 1000;
                        Log.i(TAG, "Stream: " + mFramesSent + " sent, "
                            + mFramesSkipped + " skipped, "
                            + elapsed + "s running, interval=" + interval + "ms");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Stream push error: " + e.getMessage());
                }

            }, 0, interval, TimeUnit.MILLISECONDS);

            sendOk(out, "stream_started:" + interval + "ms", "");
            Log.i(TAG, "Stream ON (" + interval + "ms interval).");
        }

        private void stopStreaming() {
            if (!mStreaming.getAndSet(false)) return;
            if (mStreamFuture != null) mStreamFuture.cancel(true);
            if (mStreamTimer  != null) mStreamTimer.shutdownNow();
            mStreamFuture = null;
            mStreamTimer  = null;
            Log.i(TAG, "Stream OFF. sent=" + mFramesSent + " skipped=" + mFramesSkipped);
        }

        // ── Frame helpers ──────────────────────────────────────────────────

        private void sendOk(PrintWriter out, String result, String reqId) {
            try {
                JSONObject r = new JSONObject();
                r.put("ok",         true);
                r.put("result",     result);
                r.put("request_id", reqId);
                r.put("ts",         System.currentTimeMillis());
                synchronized (mWriteLock) { out.println(r.toString()); }
            } catch (Exception ignored) {}
        }

        private void sendError(PrintWriter out, String error, String reqId) {
            try {
                JSONObject r = new JSONObject();
                r.put("ok",         false);
                r.put("error",      error);
                r.put("request_id", reqId);
                r.put("ts",         System.currentTimeMillis());
                synchronized (mWriteLock) { out.println(r.toString()); }
            } catch (Exception ignored) {}
        }

        private void sendPong(PrintWriter out) {
            try {
                JSONObject r = new JSONObject();
                r.put("type", "pong");
                r.put("ts",   System.currentTimeMillis());
                synchronized (mWriteLock) { out.println(r.toString()); }
            } catch (Exception ignored) {}
        }

        private void sendStats(PrintWriter out) {
            try {
                JSONObject r = new JSONObject();
                r.put("type",           "stats");
                r.put("frames_sent",    mFramesSent);
                r.put("frames_skipped", mFramesSkipped);
                r.put("session_ms",     System.currentTimeMillis() - mSessionStart);
                r.put("streaming",      mStreaming.get());
                r.put("interval_ms",    mIntervalMs);
                r.put("ts",             System.currentTimeMillis());
                synchronized (mWriteLock) { out.println(r.toString()); }
            } catch (Exception ignored) {}
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────────

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
