/*
 * Copyright (C) 2026 The Lunaris Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lunaris;

import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.server.SystemService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Catches native daemons stuck in a busy loop and gets them out of it.
 *
 * PowerLab capture 20261109_091915_kb3l: wificond sat in state R at 99 % of a
 * core for the whole 43 min session (and ~3.5 h before it). Being 100 % busy,
 * WALT kept it on the prime core at 2.9-3.0 GHz, so the phone drew 4.4 W
 * playing YouTube, and it logged nothing. Turning Wi-Fi off then hung the
 * Wi-Fi service for good (every IWifiManager call took 4 s) because wificond,
 * a single-threaded looper, never answered. The NXP NFC HAL has the same
 * failure shape (a client thread looping on its queue, 2026-10-28), and so
 * far the only cure for either was a toggle or a reboot.
 *
 * Power: no wakelock, no alarm. The sampling runs on a Handler, whose delays
 * count uptime, so it never wakes the device and the device suspends with it
 * pending. Every minute of awake time with the screen on (every 5 min of
 * awake time with it off) it reads utime+stime of the native processes from
 * /proc - a few ms of CPU. A process above HOT_CORE_FRACTION of a core for
 * RUNAWAY_MS of awake time in a row is a runaway:
 *  - its busiest thread is logged, and for the daemons in RESTARTABLE a native
 *    backtrace goes to /data/anr/runaway_<name>_<time>.txt (debuggerd, as for
 *    an ANR), then init restarts the service. Both have their own recovery
 *    for this: the Wi-Fi service cleans up and re-initialises when wificond
 *    dies, and the NFC stack restarts with its HAL, as a toggle does.
 *  - anything else is only reported (a codec or a game server can be
 *    legitimately busy): one log line per runaway, no backtrace, since
 *    dumping pauses the process.
 * Restarts back off (RESTART_MIN_GAP_MS) and stop after MAX_RESTARTS per
 * service per boot; the report continues.
 *
 * sys.yuki.runaway.last / .count publish what was found for PowerLab;
 * persist.sys.yuki.runaway_guard=false turns the guard off.
 */
public final class RunawayDaemonGuard extends SystemService {
    private static final String TAG = "RunawayDaemonGuard";

    private static final String PROP_ENABLED = "persist.sys.yuki.runaway_guard";
    private static final String PROP_LAST = "sys.yuki.runaway.last";
    private static final String PROP_COUNT = "sys.yuki.runaway.count";

    private static final long SAMPLE_MS = 60_000L;
    private static final long SAMPLE_SCREEN_OFF_MS = 5 * 60_000L;
    private static final float HOT_CORE_FRACTION = 0.85f;
    private static final long RUNAWAY_MS = 3 * 60_000L;
    /** A reported (not restarted) runaway is logged again after this long. */
    private static final long REPORT_AGAIN_MS = 30 * 60_000L;
    private static final long RESTART_MIN_GAP_MS = 15 * 60_000L;
    private static final int MAX_RESTARTS = 3;
    private static final int BACKTRACE_TIMEOUT_S = 10;
    private static final long THREAD_SAMPLE_MS = 1000L;
    private static final int MAX_DUMP_FILES = 8;
    private static final File ANR_DIR = new File("/data/anr");

    /**
     * Executable basename (cmdline argv[0]) -> init service. Only daemons whose
     * clients recover from a restart on their own belong here.
     */
    private static final Map<String, String> RESTARTABLE = new ArrayMap<>();
    static {
        RESTARTABLE.put("wificond", "wificond");
        RESTARTABLE.put("android.hardware.nfc-service.nxp", "vendor.nfc_hal_service");
    }

    private static final class Proc {
        long ticks;
        long hotMs;
        long reportedAt;
        String name;
    }

    private final Map<Integer, Proc> mProcs = new ArrayMap<>();
    private final Map<String, Integer> mRestarts = new ArrayMap<>();
    private final Map<String, Long> mLastRestart = new ArrayMap<>();
    private final long mTicksPerSec = Os.sysconf(OsConstants._SC_CLK_TCK);
    private final int mSelfPid = Process.myPid();
    private Handler mHandler;
    private PowerManager mPowerManager;
    private long mLastSampleUptime;
    private int mActions;

    public RunawayDaemonGuard(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_BOOT_COMPLETED || mHandler != null) return;
        HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mPowerManager = getContext().getSystemService(PowerManager.class);
        mHandler.postDelayed(this::sample, SAMPLE_MS);
    }

    private void sample() {
        try {
            if (SystemProperties.getBoolean(PROP_ENABLED, true)) {
                scan();
            } else {
                mProcs.clear();
                mLastSampleUptime = 0;
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "scan failed", e);
        }
        boolean screenOn = mPowerManager == null || mPowerManager.isInteractive();
        mHandler.postDelayed(this::sample, screenOn ? SAMPLE_MS : SAMPLE_SCREEN_OFF_MS);
    }

    private void scan() {
        long now = SystemClock.uptimeMillis();
        long elapsedMs = mLastSampleUptime == 0 ? 0 : now - mLastSampleUptime;
        mLastSampleUptime = now;
        String[] pids = new File("/proc").list();
        if (pids == null) return;

        Map<Integer, Proc> seen = new ArrayMap<>();
        for (String entry : pids) {
            int pid = parsePid(entry);
            if (pid <= 0 || pid == mSelfPid) continue;
            int uid = uidOf(pid);
            // Apps have their own excessive-CPU handling; this is for daemons.
            if (uid < 0 || uid >= Process.FIRST_APPLICATION_UID) continue;
            long ticks = cpuTicks("/proc/" + pid + "/stat");
            if (ticks < 0) continue;

            Proc p = mProcs.get(pid);
            if (p == null) {
                p = new Proc();
            } else if (elapsedMs > 0) {
                float cores = (ticks - p.ticks) * 1000f / (mTicksPerSec * elapsedMs);
                p.hotMs = cores >= HOT_CORE_FRACTION ? p.hotMs + elapsedMs : 0;
                if (p.hotMs >= RUNAWAY_MS) {
                    onRunaway(pid, p, cores, now);
                }
            }
            p.ticks = ticks;
            seen.put(pid, p);
        }
        mProcs.clear();
        mProcs.putAll(seen);
    }

    private void onRunaway(int pid, Proc p, float cores, long now) {
        if (p.name == null) p.name = processName(pid);
        String service = RESTARTABLE.get(p.name);
        int pct = Math.round(cores * 100);
        String hot = hottestThread(pid);

        if (service == null || !restartAllowed(service, now)) {
            if (p.reportedAt != 0 && now - p.reportedAt < REPORT_AGAIN_MS) return;
            p.reportedAt = now;
            Slog.w(TAG, "Runaway: " + p.name + " (pid " + pid + ") at " + pct
                    + "% of a core for " + p.hotMs / 60_000 + " min, hottest thread " + hot
                    + (service == null ? " - not restartable, reported only"
                            : " - restart limit reached for " + service));
            publish(p.name, pct, "reported");
            return;
        }

        String dump = backtrace(pid, p.name, pct, hot);
        int n = mRestarts.getOrDefault(service, 0) + 1;
        mRestarts.put(service, n);
        mLastRestart.put(service, now);
        Slog.w(TAG, "Runaway: " + p.name + " (pid " + pid + ") at " + pct + "% of a core for "
                + p.hotMs / 60_000 + " min, hottest thread " + hot + " - backtrace "
                + (dump != null ? dump : "failed") + ", restarting " + service + " (#" + n
                + " this boot)");
        SystemProperties.set("ctl.restart", service);
        publish(p.name, pct, "restarted");
        p.hotMs = 0;
    }

    private boolean restartAllowed(String service, long now) {
        if (mRestarts.getOrDefault(service, 0) >= MAX_RESTARTS) return false;
        Long last = mLastRestart.get(service);
        return last == null || now - last >= RESTART_MIN_GAP_MS;
    }

    private void publish(String name, int pct, String action) {
        mActions++;
        String when = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        try {
            SystemProperties.set(PROP_LAST, name + " " + pct + "% " + action + " " + when);
            SystemProperties.set(PROP_COUNT, Integer.toString(mActions));
        } catch (RuntimeException e) {
            Slog.w(TAG, "publish failed", e);
        }
    }

    /** Native backtrace into /data/anr, like an ANR's. Returns the file, or null. */
    private String backtrace(int pid, String name, int pct, String hot) {
        pruneDumps();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(ANR_DIR, "runaway_" + name.replaceAll("[^A-Za-z0-9._-]", "_")
                + "_" + stamp + ".txt");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(("Runaway " + name + " pid " + pid + ": " + pct
                    + "% of a core for " + RUNAWAY_MS / 60_000 + "+ min, hottest thread " + hot
                    + "\n\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Slog.w(TAG, "cannot create " + f, e);
            return null;
        }
        return Debug.dumpNativeBacktraceToFileTimeout(pid, f.getPath(), BACKTRACE_TIMEOUT_S)
                ? f.getPath() : null;
    }

    private static void pruneDumps() {
        File[] old = ANR_DIR.listFiles((dir, n) -> n.startsWith("runaway_"));
        if (old == null || old.length < MAX_DUMP_FILES) return;
        Arrays.sort(old, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i <= old.length - MAX_DUMP_FILES; i++) old[i].delete();
    }

    /** "name tid N% " of the busiest thread over THREAD_SAMPLE_MS. */
    private String hottestThread(int pid) {
        String dir = "/proc/" + pid + "/task";
        String[] tids = new File(dir).list();
        if (tids == null) return "?";
        long[] before = new long[tids.length];
        for (int i = 0; i < tids.length; i++) before[i] = cpuTicks(dir + "/" + tids[i] + "/stat");
        SystemClock.sleep(THREAD_SAMPLE_MS);
        int best = -1;
        long bestDelta = -1;
        for (int i = 0; i < tids.length; i++) {
            long after = cpuTicks(dir + "/" + tids[i] + "/stat");
            if (before[i] < 0 || after < 0) continue;
            if (after - before[i] > bestDelta) {
                bestDelta = after - before[i];
                best = i;
            }
        }
        if (best < 0) return "?";
        int pct = Math.round(bestDelta * 100f * 1000f / (mTicksPerSec * THREAD_SAMPLE_MS));
        return "'" + commOf(dir + "/" + tids[best] + "/stat") + "' tid " + tids[best]
                + " " + pct + "%";
    }

    private static int parsePid(String s) {
        if (s.isEmpty() || s.charAt(0) < '0' || s.charAt(0) > '9') return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int uidOf(int pid) {
        try {
            return Os.stat("/proc/" + pid).st_uid;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readSmall(String path) {
        try {
            return new String(Files.readAllBytes(new File(path).toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** utime + stime from a /proc stat line; comm may hold spaces and ')'. */
    private static long cpuTicks(String statPath) {
        String s = readSmall(statPath);
        if (s == null) return -1;
        int close = s.lastIndexOf(')');
        if (close < 0) return -1;
        String[] f = s.substring(close + 2).split(" ");
        // After "pid (comm) ": state=0 ... utime=11 stime=12
        if (f.length < 13) return -1;
        try {
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String commOf(String statPath) {
        String s = readSmall(statPath);
        if (s == null) return "?";
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        return open >= 0 && close > open ? s.substring(open + 1, close) : "?";
    }

    /** argv[0]'s basename (comm is cut at 15 characters), else comm. */
    private static String processName(int pid) {
        String cmd = readSmall("/proc/" + pid + "/cmdline");
        if (cmd != null && !cmd.isEmpty()) {
            int end = cmd.indexOf('\0');
            String arg0 = end >= 0 ? cmd.substring(0, end) : cmd;
            int slash = arg0.lastIndexOf('/');
            String base = slash >= 0 ? arg0.substring(slash + 1) : arg0;
            if (!base.isEmpty()) return base;
        }
        return commOf("/proc/" + pid + "/stat");
    }
}
