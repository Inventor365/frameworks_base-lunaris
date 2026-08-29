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
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import com.android.systemui.res.R;

/**
 * Single source of truth for Power Profile state AND behavior inside SystemUI.
 */
public final class PowerProfileUtils {
    private static final String TAG = "PowerProfileUtils";
    private static final String NOTIFICATION_CHANNEL_ID = "PowerProfileTileService";

    // Sysfs / sysprop surfaces
    public static final String SCONFIG_NODE = "/sys/class/thermal/thermal_message/sconfig";
    public static final String SYS_PERF_PROP = "sys.perf_mode_active";
    public static final String HTSR_NODE = "/sys/class/touch/touch_dev/click_touch_dialog";

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

    private static void startAutoProfileService(Context context) {
        try {
            Intent serviceIntent = new Intent();
            serviceIntent.setClassName("org.lineageos.settings", "org.lineageos.settings.power.AutoProfileService");
            context.startForegroundService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed starting AutoProfileService", e);
        }
    }

    private static void stopAutoProfileService(Context context) {
        try {
            Intent serviceIntent = new Intent();
            serviceIntent.setClassName("org.lineageos.settings", "org.lineageos.settings.power.AutoProfileService");
            context.stopService(serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed stopping AutoProfileService", e);
        }
    }

    public static boolean applyProfile(Context context, PowerProfile profile) {
        PowerProfile outgoing = getCurrentProfile(context);
        if (outgoing != PowerProfile.BATTERY) {
            savePreviousProfile(context, outgoing);
        }

        if (outgoing == PowerProfile.AUTO && profile != PowerProfile.AUTO) {
            stopAutoProfileService(context);
        }

        boolean success;
        if (profile == PowerProfile.AUTO) {
            startAutoProfileService(context);
            success = true;
        } else {
            success = writeProfile(profile);
            if (!success) {
                Log.e(TAG, "Failed to write power profile: " + profile);
            }
        }

        if (profile != PowerProfile.AUTO) {
            boolean htsrEnabled = profile == PowerProfile.PERFORMANCE || profile == PowerProfile.GAMING;
            updateTouchSampling(context, htsrEnabled);
        }

        boolean isCharging = isCharging(context);
        if (profile == PowerProfile.BATTERY) {
            if (isBatteryProfileSaverEnabled(context)) {
                setBatterySaver(context, !isCharging);
            }
        } else if (isAutoBatterySyncEnabled(context)) {
            setBatterySaver(context, false);
        }

        if (profile == PowerProfile.BATTERY) {
            applyBatteryProfileRefreshRate(context);
        } else {
            restoreRefreshRateOverride(context);
        }

        if (isPowerEnabled(context) && profile != PowerProfile.UNKNOWN) {
            showPerformanceNotification(context, profile);
        } else {
            cancelPerformanceNotification(context);
        }

        saveProfile(context, profile);
        context.sendBroadcast(new Intent(ACTION_PROFILE_CHANGED));

        return success;
    }

    public static PowerProfile getCurrentProfile(Context context) {
        if (getSavedProfile(context) == PowerProfile.AUTO) {
            return PowerProfile.AUTO;
        }
        String value = readOneLine(SCONFIG_NODE);
        if (value == null) return PowerProfile.UNKNOWN;
        String trimmed = value.trim();
        if (CHARGING_BOOST_SCONFIG_VALUE.equals(trimmed)) {
            return getSavedProfile(context);
        }
        try {
            return PowerProfile.fromValue(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return PowerProfile.UNKNOWN;
        }
    }

    public static boolean writeProfile(PowerProfile profile) {
        boolean success = writeLine(SCONFIG_NODE, String.valueOf(profile.getValue()));
        if (success) {
            try {
                SystemProperties.set(SYS_PERF_PROP, profile.getSysPropValue());
            } catch (Exception ignored) {
            }
        }
        return success;
    }

    public static boolean isHtsrActive(Context context) {
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
        boolean success = writeLine(HTSR_NODE, enable ? "1" : "0");
        if (!success) {
            Log.e(TAG, "Failed to write HTSR sysfs state");
            return false;
        }

        SharedPreferences htsrPrefs = context.getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE);
        htsrPrefs.edit().putBoolean(HTSR_STATE, enable).apply();

        try {
            Intent serviceIntent = new Intent();
            serviceIntent.setClassName("org.lineageos.settings", "org.lineageos.settings.touchsampling.TouchSamplingService");
            if (enable) {
                context.startService(serviceIntent);
            } else {
                context.stopService(serviceIntent);
            }
        } catch (Exception ignored) {
        }

        context.sendBroadcast(new Intent("org.lineageos.settings.touchsampling.ACTION_UPDATE_TILE"));
        return true;
    }

    private static void ensureNotificationChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.perf_mode_title),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setBlockable(true);
        manager.createNotificationChannel(channel);
    }

    public static void showPerformanceNotification(Context context, PowerProfile profile) {
        ensureNotificationChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int contentTextResId;
        switch (profile) {
            case GAMING:
                contentTextResId = R.string.gaming_mode_notification;
                break;
            case PERFORMANCE:
                contentTextResId = R.string.perf_mode_notification;
                break;
            case BATTERY:
                contentTextResId = R.string.battery_mode_notification;
                break;
            case AUTO:
                contentTextResId = R.string.auto_mode_notification;
                break;
            case DEFAULT:
            default:
                contentTextResId = R.string.balanced_mode_notification;
                break;
        }

        Notification notification = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(profile.getNameResId()))
                .setContentText(context.getString(contentTextResId))
                .setSmallIcon(profile.getIconResId())
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        manager.notify(PERFORMANCE_NOTIFICATION_ID, notification);
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
        prefs(context).edit().putInt(POWER_PROFILE_PREF_KEY, profile.getValue()).apply();
    }

    public static PowerProfile getSavedProfile(Context context) {
        int value = prefs(context).getInt(POWER_PROFILE_PREF_KEY, PowerProfile.AUTO.getValue());
        return PowerProfile.fromValue(value);
    }

    public static void savePreviousProfile(Context context, PowerProfile profile) {
        if (profile != PowerProfile.BATTERY) {
            prefs(context).edit().putInt(PREV_POWER_PROFILE_PREF_KEY, profile.getValue()).apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    private static String readOneLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean writeLine(String path, String value) {
        try (FileWriter fw = new FileWriter(path)) {
            fw.write(value);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
