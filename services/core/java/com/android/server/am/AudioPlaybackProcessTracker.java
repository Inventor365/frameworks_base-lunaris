/*
 * Copyright (C) 2026 The Lunaris Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.am;

import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_UI_VISIBILITY;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseLongArray;

import java.util.List;

/**
 * Keeps processes that are playing audible audio out of the cached and frozen states.
 *
 * <p>Android only protects background playback when the player holds a mediaPlayback foreground
 * service. Without one (START_FOREGROUND app op denied, FGS start refused from the background,
 * or an app bug), the process drops to a cached adj once the screen turns off, the cached app
 * freezer stops it about 10s later and the music dies mid-song with dead headset controls.
 *
 * <p>This tracker watches AudioService's playback configurations and flags every process with a
 * started player that it did not mute itself. OomAdjuster then ranks it like a media playback
 * FGS ("playing-audio"). The flag is dropped {@link #STOP_GRACE_MS} after the last audible
 * player stops, so pauses, buffering and track changes don't make the process bounce.
 */
final class AudioPlaybackProcessTracker {
    private static final String TAG = "AudioPlaybackTracker";

    /** Set to false to turn the protection off; read once at boot. */
    private static final String PROP_ENABLED = "persist.sys.am.audio_playback_protect";

    /** How long a process stays protected after its last audible player stopped. */
    private static final long STOP_GRACE_MS = 30_000;

    /**
     * How often to re-read the players while any process is protected. AudioService sends no
     * callback when the last player in the system is released, so stops are also caught here.
     */
    private static final long RECHECK_INTERVAL_MS = 10_000;

    /** Mutes the app applied to its own player, or the user applied to the app. */
    private static final int SELF_OR_USER_MUTED =
            AudioPlaybackConfiguration.MUTED_BY_CLIENT_VOLUME
            | AudioPlaybackConfiguration.MUTED_BY_VOLUME_SHAPER
            | AudioPlaybackConfiguration.MUTED_BY_OP_PLAY_AUDIO;

    private static final int MSG_RECHECK = 1;

    private final ActivityManagerService mService;
    private final Handler mHandler;
    private AudioManager mAudioManager;

    // Only accessed on mHandler.
    private final SparseArray<ProcessRecord> mProtected = new SparseArray<>();
    private final SparseLongArray mLastAudibleTime = new SparseLongArray();

    AudioPlaybackProcessTracker(ActivityManagerService service, Looper looper) {
        mService = service;
        mHandler = new Handler(looper, msg -> {
            if (msg.what == MSG_RECHECK) {
                recheck();
            }
            return true;
        });
    }

    void start(Context context) {
        if (!SystemProperties.getBoolean(PROP_ENABLED, true)) {
            Slog.i(TAG, "Disabled by " + PROP_ENABLED);
            return;
        }
        mAudioManager = context.getSystemService(AudioManager.class);
        if (mAudioManager == null) {
            Slog.w(TAG, "No AudioManager, not tracking playback");
            return;
        }
        // AudioService only reports client pids to callers holding MODIFY_AUDIO_ROUTING, and
        // checks that against the calling identity, which may be an app's binder call here.
        final long token = Binder.clearCallingIdentity();
        try {
            mAudioManager.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    update(configs);
                }
            }, mHandler);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        // Pick up anything that started playing before the callback was registered.
        mHandler.post(this::recheck);
    }

    private void recheck() {
        update(mAudioManager.getActivePlaybackConfigurations());
    }

    private void update(List<AudioPlaybackConfiguration> configs) {
        final long now = SystemClock.uptimeMillis();
        for (int i = configs.size() - 1; i >= 0; i--) {
            final AudioPlaybackConfiguration apc = configs.get(i);
            if (!isAudible(apc)) {
                continue;
            }
            final int pid = apc.getClientPid();
            final int uid = apc.getClientUid();
            if (pid <= 0 || UserHandle.getAppId(uid) < Process.FIRST_APPLICATION_UID) {
                continue;
            }
            final ProcessRecord app;
            synchronized (mService.mPidsSelfLocked) {
                app = mService.mPidsSelfLocked.get(pid);
            }
            if (app == null || app.uid != uid) {
                continue;
            }
            final ProcessRecord old = mProtected.get(pid);
            if (old != app) {
                if (old != null) {
                    // The pid was reused by a new process.
                    setAudible(old, false);
                }
                setAudible(app, true);
                mProtected.put(pid, app);
                Slog.i(TAG, "Protecting " + app.processName + " (pid " + pid
                        + ") while it plays audio");
            }
            mLastAudibleTime.put(pid, now);
        }

        for (int i = mProtected.size() - 1; i >= 0; i--) {
            final int pid = mProtected.keyAt(i);
            if (now - mLastAudibleTime.get(pid) < STOP_GRACE_MS) {
                continue;
            }
            final ProcessRecord app = mProtected.valueAt(i);
            setAudible(app, false);
            mProtected.removeAt(i);
            mLastAudibleTime.delete(pid);
            Slog.i(TAG, "Released " + app.processName + " (pid " + pid + "), playback stopped");
        }

        mHandler.removeMessages(MSG_RECHECK);
        if (mProtected.size() > 0) {
            mHandler.sendEmptyMessageDelayed(MSG_RECHECK, RECHECK_INTERVAL_MS);
        }
    }

    private static boolean isAudible(AudioPlaybackConfiguration apc) {
        if (apc.getPlayerState() != AudioPlaybackConfiguration.PLAYER_STATE_STARTED
                || (apc.getMutedBy() & SELF_OR_USER_MUTED) != 0) {
            return false;
        }
        // Sustained listening only; notification and UI sounds are short-lived.
        switch (apc.getAudioAttributes().getUsage()) {
            case AudioAttributes.USAGE_MEDIA:
            case AudioAttributes.USAGE_UNKNOWN:
            case AudioAttributes.USAGE_GAME:
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
                return true;
            default:
                return false;
        }
    }

    private void setAudible(ProcessRecord app, boolean audible) {
        synchronized (mService) {
            if (!mService.mProcessStateController.setHasActiveAudioPlayback(app, audible)) {
                return;
            }
            final boolean alive;
            synchronized (mService.mPidsSelfLocked) {
                alive = mService.mPidsSelfLocked.get(app.getPid()) == app;
            }
            if (alive) {
                mService.mProcessStateController.runUpdate(app, OOM_ADJ_REASON_UI_VISIBILITY);
            }
        }
    }
}
