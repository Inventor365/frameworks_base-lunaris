/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.spoof;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.NtServiceInjector;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class AxSpoofManager implements IAxSpoofManager {
    private static final String TAG = "AxSpoofManager";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_GAMEPROPS_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
    };

    // Changing any of these means DroidGuard must re-attest; a running GMS caches
    // the old verdict/key, so we force-stop the pipeline (debounced) on change.
    private static final String[] INTEGRITY_KEYS = {
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_PIF_CONFIG,
    };

    // Force-stopping the base package also kills its child processes
    // (gms.unstable = DroidGuard, gms.persistent). rkpdapp is separate.
    private static final String[] GMS_RESTART_PACKAGES = {
            "com.google.android.gms",
            "com.android.vending",
            "com.google.android.rkpdapp",
    };

    // Coalesce bursts (e.g. auto-target appending several packages) into one restart.
    private static final long GMS_RESTART_DEBOUNCE_MS = 8_000L;

    // Detectors / attestation checkers / remote-control apps that must NEVER be
    // auto-targeted (targeting them would expose the hacked chain to the very thing
    // trying to catch it). Mirrors DETECTOR_PACKAGES in the Settings target picker
    // (TrickyStoreAppSettings.kt), itself sourced from Specter.
    private static final Set<String> DETECTOR_BLACKLIST = new HashSet<>(Arrays.asList(
            "icu.nullptr.nativetest", "icu.nullptr.applistdetector",
            "io.github.vvb2060.keyattestation", "io.github.vvb2060.mahoshojo",
            "com.scottyab.rootbeer", "com.scottyab.rootbeer.sample",
            "com.topjohnwu.magisk.detector", "com.zhenxi.hunter",
            "com.byxiaorun.detector", "io.github.huskydg.memorydetector",
            "com.OrangeEnvironment.Detector", "com.Longze.detector.pro2",
            "com.lingqing.detector", "com.junge.algorithmAidePro",
            "rikka.safetynetchecker", "com.reveny.nativechecker",
            "com.reveny.environmentchecker", "com.reveny.rootchecker",
            "com.guardian.detect", "com.security.environmentchecker",
            "com.integrity.checker", "com.integrity.attestation",
            "com.eltavine.duckdetector", "com.rem01gaming.disclosure",
            "com.chunqiunativecheck", "me.garfieldhan.holmes",
            "aidepro.top", "chunqiu.safe", "luna.safe.luna",
            "io.liankong.riskdetector", "com.studio.duckdetector",
            "com.android.nativetest", "com.byyoung.setting",
            "com.devadvance.rootcloak", "com.fde.xposed.detector",
            "com.zhenxi.checker", "com.example.nativelibtest",
            "com.example.memcheck", "com.example.syscallchecker",
            "com.jrummyapps.rootchecker", "com.kimchangyoun.magiskdetector",
            "com.lody.virtual", "com.lody.virtual.client",
            "com.lody.virtual.server", "com.lody.whale",
            "com.kimchangyoun.rootbeerfresh", "com.didikee.rootcheck",
            "com.joeykrim.rootcheck", "com.freeandroidtools.rootchecker",
            "com.bluestacks.rootchecker", "com.moonshine.checker",
            "com.ramdroid.appdetector", "com.smlj.rootcheck",
            "com.devadvance.rootcloakplus", "com.formyhm.hideroot",
            "com.example.emulatordetector", "com.vmcheck.detector",
            "com.virtual.checker", "com.antivm.detector",
            "com.xposed.checker", "com.google.snet.test",
            "com.attestation.checker", "com.integrity.check",
            "com.native.checker", "com.syscall.detector",
            "com.memory.scan",
            "com.fede047.rootdetector", "com.junkcode.androidtamperdetector",
            "me.weishu.kernelsu", "io.github.a13e300.tricky_store",
            "io.github.a13e300.tricky_store.debug",
            "io.github.vvb2060.pinyomi", "io.github.duzhaokun123.yhms",
            "com.github.quarck.rntf", "com.negusoft.greentea",
            "com.stillnesscreative.integritycheck",
            "com.suyash.androidupdatechecker",
            "com.g00fy2.versioncompare",
            "io.github.huskydg.magu",
            "com.kgurgul.cpuinfo",
            "com.anydesk.anydeskandroid", "com.teamviewer.teamviewer.market.mobile",
            "com.teamviewer.quicksupport.market",
            "com.sand.airdroid", "com.sand.airmirror",
            "com.koushikdutta.vysor", "com.genymobile.scrcpy",
            "com.microsoft.rdc.androidx", "com.realvnc.viewer.android",
            "com.splashtop.remote.pad.v2", "com.dwservice.dwagent",
            "com.carriez.flutter_hbb", "com.carriez.flutter_hbbclient",
            "com.rustdesk.rustdesk"
    ));

    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private Context mContext;
    private ContentResolver mResolver;
    private ContentObserver mObserver;
    private BroadcastReceiver mPackageReceiver;
    private final Runnable mGmsRestartRunnable = this::restartGmsPipeline;
    private volatile boolean mReady = false;

    public AxSpoofManager() {
        mHandlerThread = new HandlerThread("AxSpoofManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        if (mContext == null) {
            Log.w(TAG, "Context unavailable, deferring init");
            return;
        }
        mResolver = mContext.getContentResolver();

        for (String key : WATCHED_KEYS) {
            refreshKey(key);
        }

        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                final String last = uri.getLastPathSegment();
                if (last == null) return;
                refreshKey(last);
                Log.i(TAG, "Spoof config refreshed: " + last);
                if (isIntegrityKey(last)) {
                    scheduleGmsRestart();
                }
            }
        };
        for (String key : WATCHED_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }

        registerAutoTarget();
        mHandler.post(this::scanInstalledForAutoTarget);

        mReady = true;
        Log.i(TAG, "AxSpoofManager ready");
    }

    private void refreshKey(String key) {
        if (mResolver == null) return;
        final String value = Settings.Secure.getStringForUser(
                mResolver, key, UserHandle.USER_SYSTEM);
        if (value == null) {
            mCache.remove(key);
        } else {
            mCache.put(key, value);
        }
    }

    private String getCached(String key) {
        return mCache.get(key);
    }

    // ----- GMS re-attestation on keybox / target / PIF change -----

    private static boolean isIntegrityKey(String key) {
        for (String k : INTEGRITY_KEYS) {
            if (k.equals(key)) return true;
        }
        return false;
    }

    private void scheduleGmsRestart() {
        mHandler.removeCallbacks(mGmsRestartRunnable);
        mHandler.postDelayed(mGmsRestartRunnable, GMS_RESTART_DEBOUNCE_MS);
    }

    private void restartGmsPipeline() {
        final IActivityManager am = ActivityManager.getService();
        if (am == null) return;
        final PackageManager pm = mContext != null ? mContext.getPackageManager() : null;
        for (String pkg : GMS_RESTART_PACKAGES) {
            if (pm != null) {
                try {
                    pm.getPackageInfo(pkg, 0);
                } catch (Exception notInstalled) {
                    continue;
                }
            }
            try {
                am.forceStopPackage(pkg, UserHandle.USER_SYSTEM);
                Log.i(TAG, "Re-attest: force-stopped " + pkg);
            } catch (Exception e) {
                Log.w(TAG, "Failed to force-stop " + pkg, e);
            }
        }
    }

    // ----- Auto-target newly installed third-party apps -----

    private void registerAutoTarget() {
        mPackageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getData() == null) return;
                // A replace (update) re-adds an already-known package; skip it.
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                final String pkg = intent.getData().getSchemeSpecificPart();
                if (TextUtils.isEmpty(pkg)) return;
                mHandler.post(() -> autoTargetPackage(pkg));
            }
        };
        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        try {
            mContext.registerReceiverForAllUsers(mPackageReceiver, filter, null, mHandler);
            Log.i(TAG, "Auto-target receiver registered");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register auto-target receiver", e);
        }
    }

    // One-time sweep so already-installed third-party apps are targeted after a
    // flash/update, not just apps installed later (mirrors Specter's pm -3 scan).
    private void scanInstalledForAutoTarget() {
        if (mContext == null) return;
        try {
            final Set<String> candidates = new HashSet<>();
            for (ApplicationInfo ai : mContext.getPackageManager().getInstalledApplications(0)) {
                if (ai == null || ai.packageName == null) continue;
                if (isAutoTargetable(ai)) {
                    candidates.add(ai.packageName);
                }
            }
            addTargetsIfAbsent(candidates);
        } catch (Exception e) {
            Log.w(TAG, "Auto-target boot scan failed", e);
        }
    }

    private void autoTargetPackage(String pkg) {
        if (DETECTOR_BLACKLIST.contains(pkg)) {
            Log.d(TAG, "Auto-target skip (detector): " + pkg);
            return;
        }
        try {
            if (!isAutoTargetable(mContext.getPackageManager().getApplicationInfo(pkg, 0))) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        final Set<String> one = new HashSet<>();
        one.add(pkg);
        addTargetsIfAbsent(one);
    }

    // Auto-target third-party apps only; system apps are covered by the fixed
    // defaults, and detectors must never be targeted.
    private boolean isAutoTargetable(ApplicationInfo ai) {
        if (ai == null || ai.packageName == null) return false;
        if (DETECTOR_BLACKLIST.contains(ai.packageName)) return false;
        return (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
    }

    // Appends any packages not already present, in a single setting write.
    private void addTargetsIfAbsent(Set<String> pkgs) {
        if (mResolver == null || pkgs == null || pkgs.isEmpty()) return;

        final String current = Settings.Secure.getStringForUser(
                mResolver, Settings.Secure.SPOOF_TRICKYSTORE_TARGET, UserHandle.USER_SYSTEM);

        // Preserve existing order/modes; dedupe on the base package (strip suffix).
        final Set<String> bases = new HashSet<>();
        final StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(current)) {
            for (String raw : current.split("\n")) {
                final String line = raw.trim();
                if (line.isEmpty()) continue;
                bases.add(stripMode(line));
                sb.append(line).append('\n');
            }
        }

        int added = 0;
        for (String pkg : pkgs) {
            if (TextUtils.isEmpty(pkg) || bases.contains(pkg)) continue;
            sb.append(pkg).append('\n');
            bases.add(pkg);
            added++;
        }
        if (added == 0) return;

        Settings.Secure.putStringForUser(mResolver,
                Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                sb.toString().trim(), UserHandle.USER_SYSTEM);
        Log.i(TAG, "Auto-targeted " + added + " new app(s)");
        // The setting write fires our observer, which schedules the GMS restart.
    }

    private static String stripMode(String line) {
        if (line.endsWith("!") || line.endsWith("?") || line.endsWith("-")) {
            return line.substring(0, line.length() - 1).trim();
        }
        return line;
    }

    @Override
    public String getPifConfig() {
        return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
    }

    @Override
    public String getGamePropsConfig() {
        return getCached(Settings.Secure.SPOOF_GAMEPROPS_CONFIG);
    }

    @Override
    public String getTrickyStoreTarget() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
    }

    @Override
    public String getTrickyStoreKeyBox() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
    }

    @Override
    public String getTrickyStorePatch() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
    }
}
