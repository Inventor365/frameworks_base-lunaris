/*
 * Copyright (C) 2026 The LineageOS Project
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
package org.lineageos.settings.power;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import androidx.preference.PreferenceManager;

import com.android.systemui.res.R;

/**
 * Single source of truth for Power Profile state AND behavior inside SystemUI.
 */
public final class PowerProfileUtils {
    private static final String TAG = "PowerProfileUtils";
    private static final String NOTIFICATION_CHANNEL_ID = "PowerProfileTileService";

    // SharedPreferences keys
    public static final String POWER_ENABLED_KEY = "power_enabled";
    public static final String POWER_PROFILE_PREF_KEY = "saved_power_profile";
    public static final String PREV_POWER_PROFILE_PREF_KEY = "prev_power_profile";
    public static final String AUTO_BATTERY_SYNC_KEY = "power_auto_battery_sync";
    public static final String AUTO_RESTORE_ON_CHARGE_KEY = "power_auto_restore_on_charge";

    public static final String SHAREDHTSR = "touch_sampling_shared_prefs";
    public static final String HTSR_STATE = "htsr_state";

    public static final String BATTERY_PROFILE_ENGAGE_SAVER_KEY = "battery_profile_engage_saver";
    public static final String BATTERY_PROFILE_REFRESH_RATE_KEY = "battery_profile_refresh_rate";

    public static final String BATTERY_REFRESH_RATE_AUTO = "auto";
    public static final String BATTERY_REFRESH_RATE_60 = "60";
    public static final String BATTERY_REFRESH_RATE_120 = "120";

    private static final String RR_OVERRIDE_ACTIVE_KEY = "battery_profile_rr_override_active";
    private static final String RR_SAVED_PEAK_KEY = "battery_profile_rr_saved_peak";
    private static final String RR_SAVED_MIN_KEY = "battery_profile_rr_saved_min";

    private static final String KEY_PEAK_REFRESH_RATE = "peak_refresh_rate";
    private static final String KEY_MIN_REFRESH_RATE = "min_refresh_rate";

    private static final float REFRESH_RATE_UNSET = -1f;
    private static final float FALLBACK_PANEL_MAX_REFRESH_RATE = 120f;
    private static final String SAVER_SELF_WRITE_GUARD_KEY = "power_saver_self_write_guard";

    public static final String ACTION_PROFILE_CHANGED =
            "org.lineageos.settings.power.ACTION_PROFILE_CHANGED";
    public static final String ACTION_SET_POWER_PROFILE =
            "org.lineageos.settings.power.ACTION_SET_POWER_PROFILE";
    public static final String ACTION_SET_HTSR =
            "org.lineageos.settings.power.ACTION_SET_HTSR";
    public static final String POWER_PROFILE_SETTING = "power_profile";
    public static final String EXTRA_PROFILE = "org.lineageos.settings.power.extra.PROFILE";
    public static final String EXTRA_FROM_SYSTEMUI = "org.lineageos.settings.power.extra.FROM_SYSTEMUI";
    public static final String EXTRA_HTSR_STATE = "org.lineageos.settings.power.extra.HTSR_STATE";

    private static final String CHARGING_BOOST_SCONFIG_VALUE = "27";
    public static final int PERFORMANCE_NOTIFICATION_ID = 1001;

    public enum PowerProfile {
        DEFAULT(0, com.android.systemui.res.R.string.powerprofile_default,
                com.android.systemui.res.R.string.powerprofile_default_desc,
                com.android.systemui.res.R.drawable.ic_power_default, "1"),
        BATTERY(1, com.android.systemui.res.R.string.powerprofile_battery,
                com.android.systemui.res.R.string.powerprofile_battery_desc,
                com.android.systemui.res.R.drawable.ic_power_battery_saver, "0"),
        PERFORMANCE(6, com.android.systemui.res.R.string.powerprofile_performance,
                com.android.systemui.res.R.string.powerprofile_performance_desc,
                com.android.systemui.res.R.drawable.ic_power_performance, "2"),
        GAMING(18, com.android.systemui.res.R.string.powerprofile_gaming,
                com.android.systemui.res.R.string.powerprofile_gaming_desc,
                com.android.systemui.res.R.drawable.ic_power_gaming, "3"),
        AUTO(-3, com.android.systemui.res.R.string.powerprofile_auto,
                com.android.systemui.res.R.string.powerprofile_auto_desc,
                com.android.systemui.res.R.drawable.ic_power_auto, "1"),
        UNKNOWN(-1, com.android.systemui.res.R.string.powerprofile_unknown,
                com.android.systemui.res.R.string.powerprofile_default_desc,
                com.android.systemui.res.R.drawable.ic_power_default, "1");

        private final int value;
        private final int nameResId;
        private final int descResId;
        private final int iconResId;
        private final String sysPropValue;

        PowerProfile(int value, int nameResId, int descResId, int iconResId, String sysPropValue) {
            this.value = value;
            this.nameResId = nameResId;
            this.descResId = descResId;
            this.iconResId = iconResId;
            this.sysPropValue = sysPropValue;
        }

        public int getValue() { return value; }
        public int getNameResId() { return nameResId; }
        public int getDescResId() { return descResId; }
        public int getIconResId() { return iconResId; }
        public String getSysPropValue() { return sysPropValue; }

        public static PowerProfile fromValue(int value) {
            for (PowerProfile profile : values()) {
                if (profile.value == value) return profile;
            }
            return UNKNOWN;
        }

        public PowerProfile getNext() {
            switch (this) {
                case DEFAULT: return BATTERY;
                case BATTERY: return PERFORMANCE;
                case PERFORMANCE: return GAMING;
                case GAMING: return AUTO;
                case AUTO:
                case UNKNOWN:
                default: return DEFAULT;
            }
        }
    }

    private PowerProfileUtils() {
    }

    public static boolean applyProfile(Context context, PowerProfile profile) {
        saveProfile(context, profile);

        boolean htsrEnabled = profile == PowerProfile.PERFORMANCE || profile == PowerProfile.GAMING;
        try {
            Settings.System.putInt(context.getContentResolver(), "htsr_state", htsrEnabled ? 1 : 0);
        } catch (Exception ignored) {
        }
        context.getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE)
                .edit().putBoolean(HTSR_STATE, htsrEnabled).apply();

        Intent intent = new Intent(ACTION_SET_POWER_PROFILE);
        intent.setComponent(new ComponentName("org.lineageos.settings",
                "org.lineageos.settings.power.PowerProfileReceiver"));
        intent.putExtra(EXTRA_PROFILE, profile.getValue());
        intent.putExtra("profile", profile.getValue());
        intent.putExtra(EXTRA_FROM_SYSTEMUI, true);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
        } catch (Exception e) {
            try {
                context.sendBroadcast(intent);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to send profile intent from SystemUI", ex);
                return false;
            }
        }

        return true;
    }

    public static PowerProfile getCurrentProfile(Context context) {
        PowerProfile saved = getSavedProfile(context);
        return (saved != PowerProfile.UNKNOWN) ? saved : PowerProfile.AUTO;
    }

    public static boolean isHtsrActive(Context context) {
        try {
            int val = Settings.System.getInt(context.getContentResolver(), "htsr_state", -1);
            if (val != -1) return val == 1;
        } catch (Exception ignored) {
        }
        return context.getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE)
                .getBoolean(HTSR_STATE, false);
    }

    public static boolean isCharging(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        if (batteryStatus == null) return false;
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    public static void setBatterySaver(Context context, boolean enable) {
        setBatterySaver(context, enable, false);
    }

    public static void setBatterySaver(Context context, boolean enable, boolean suppressProfileSync) {
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager == null) return;
        try {
            boolean currentState = powerManager.isPowerSaveMode();
            if (currentState != enable) {
                if (suppressProfileSync) {
                    prefs(context).edit().putBoolean(SAVER_SELF_WRITE_GUARD_KEY, true).commit();
                }
                Settings.Global.putInt(context.getContentResolver(),
                        Settings.Global.LOW_POWER_MODE, enable ? 1 : 0);
                Log.d(TAG, "Battery saver " + (enable ? "enabled" : "disabled"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to toggle battery saver", e);
        }
    }

    public static boolean updateTouchSampling(Context context, boolean enable) {
        try {
            Settings.System.putInt(context.getContentResolver(), "htsr_state", enable ? 1 : 0);
        } catch (Exception ignored) {
        }

        SharedPreferences htsrPrefs = context.getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE);
        htsrPrefs.edit().putBoolean(HTSR_STATE, enable).apply();

        Intent intent = new Intent(ACTION_SET_HTSR);
        intent.setComponent(new ComponentName("org.lineageos.settings",
                "org.lineageos.settings.power.PowerProfileReceiver"));
        intent.putExtra(EXTRA_HTSR_STATE, enable);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
            return true;
        } catch (Exception e) {
            try {
                context.sendBroadcast(intent);
                return true;
            } catch (Exception ex) {
                Log.e(TAG, "Failed to send HTSR intent from SystemUI", ex);
                return false;
            }
        }
    }

    public static void showPerformanceNotification(Context context, PowerProfile profile) {
        // Notification is solely managed by org.lineageos.settings backend.
        // Clean up any legacy notification that might have been posted from SystemUI.
        cancelPerformanceNotification(context);
    }

    public static void cancelPerformanceNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(PERFORMANCE_NOTIFICATION_ID);
        }
    }

    public static boolean isPowerEnabled(Context context) {
        return prefs(context).getBoolean(POWER_ENABLED_KEY, true);
    }

    public static boolean isAutoBatterySyncEnabled(Context context) {
        return prefs(context).getBoolean(AUTO_BATTERY_SYNC_KEY, true);
    }

    public static boolean isBatteryProfileSaverEnabled(Context context) {
        return prefs(context).getBoolean(BATTERY_PROFILE_ENGAGE_SAVER_KEY, true);
    }

    public static String getBatteryProfileRefreshRate(Context context) {
        return prefs(context).getString(BATTERY_PROFILE_REFRESH_RATE_KEY, BATTERY_REFRESH_RATE_AUTO);
    }

    public static void applyBatteryProfileRefreshRate(Context context) {
        String target = getBatteryProfileRefreshRate(context);
        if (BATTERY_REFRESH_RATE_AUTO.equals(target)) {
            restoreRefreshRateOverride(context);
            return;
        }

        float targetPeak;
        try {
            targetPeak = Float.parseFloat(target);
        } catch (NumberFormatException e) {
            return;
        }

        ContentResolver resolver = context.getContentResolver();
        SharedPreferences prefs = prefs(context);

        if (!prefs.getBoolean(RR_OVERRIDE_ACTIVE_KEY, false)) {
            prefs.edit()
                    .putFloat(RR_SAVED_PEAK_KEY,
                            Settings.System.getFloat(resolver, KEY_PEAK_REFRESH_RATE, REFRESH_RATE_UNSET))
                    .putFloat(RR_SAVED_MIN_KEY,
                            Settings.System.getFloat(resolver, KEY_MIN_REFRESH_RATE, REFRESH_RATE_UNSET))
                    .putBoolean(RR_OVERRIDE_ACTIVE_KEY, true)
                    .apply();
        }

        Settings.System.putFloat(resolver, KEY_PEAK_REFRESH_RATE, targetPeak);

        float currentMin = Settings.System.getFloat(resolver, KEY_MIN_REFRESH_RATE, REFRESH_RATE_UNSET);
        if (currentMin != REFRESH_RATE_UNSET && currentMin > targetPeak) {
            Settings.System.putFloat(resolver, KEY_MIN_REFRESH_RATE, targetPeak);
        }
    }

    public static void restoreRefreshRateOverride(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(RR_OVERRIDE_ACTIVE_KEY, false)) return;

        float savedPeak = prefs.getFloat(RR_SAVED_PEAK_KEY, REFRESH_RATE_UNSET);
        float savedMin = prefs.getFloat(RR_SAVED_MIN_KEY, REFRESH_RATE_UNSET);
        ContentResolver resolver = context.getContentResolver();

        Settings.System.putFloat(resolver, KEY_PEAK_REFRESH_RATE,
                savedPeak == REFRESH_RATE_UNSET ? getPanelMaxRefreshRate(context) : savedPeak);
        Settings.System.putFloat(resolver, KEY_MIN_REFRESH_RATE,
                savedMin == REFRESH_RATE_UNSET ? 0f : savedMin);

        prefs.edit()
                .putBoolean(RR_OVERRIDE_ACTIVE_KEY, false)
                .remove(RR_SAVED_PEAK_KEY)
                .remove(RR_SAVED_MIN_KEY)
                .apply();
    }

    public static float getPanelMaxRefreshRate(Context context) {
        try {
            DisplayManager dm = context.getSystemService(DisplayManager.class);
            Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (display != null) {
                float max = 0f;
                for (Display.Mode mode : display.getSupportedModes()) {
                    max = Math.max(max, mode.getRefreshRate());
                }
                if (max > 0f) return max;
            }
        } catch (Exception e) {
            Log.w(TAG, "Couldn't query supported display modes", e);
        }
        return FALLBACK_PANEL_MAX_REFRESH_RATE;
    }

    public static void saveProfile(Context context, PowerProfile profile) {
        try {
            Settings.System.putInt(context.getContentResolver(), POWER_PROFILE_SETTING, profile.getValue());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save profile to Settings.System", e);
        }
        prefs(context).edit().putInt(POWER_PROFILE_PREF_KEY, profile.getValue()).apply();
    }

    public static PowerProfile getSavedProfile(Context context) {
        try {
            int value = Settings.System.getInt(context.getContentResolver(), POWER_PROFILE_SETTING, Integer.MIN_VALUE);
            if (value != Integer.MIN_VALUE) {
                PowerProfile profile = PowerProfile.fromValue(value);
                if (profile != PowerProfile.UNKNOWN) {
                    return profile;
                }
            }
        } catch (Exception ignored) {
        }
        int value = prefs(context).getInt(POWER_PROFILE_PREF_KEY, PowerProfile.AUTO.getValue());
        return PowerProfile.fromValue(value);
    }

    public static void savePreviousProfile(Context context, PowerProfile profile) {
        if (profile != PowerProfile.BATTERY) {
            prefs(context).edit().putInt(PREV_POWER_PROFILE_PREF_KEY, profile.getValue()).apply();
        }
    }

    public static boolean isFirstBoot(Context context) {
        try {
            int value = Settings.System.getInt(context.getContentResolver(), POWER_PROFILE_SETTING, Integer.MIN_VALUE);
            if (value != Integer.MIN_VALUE) {
                return false;
            }
        } catch (Exception ignored) {
        }
        return !prefs(context).contains(POWER_PROFILE_PREF_KEY);
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }
}
