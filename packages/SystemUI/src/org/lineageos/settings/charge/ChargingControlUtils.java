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

package org.lineageos.settings.charge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.res.R;

public final class ChargingControlUtils {
    private static final String TAG = "ChargingControlUtils";

    public static final String ACTION_CHARGING_CONTROL_CHANGED =
            "org.lineageos.settings.charge.ACTION_CHARGING_CONTROL_CHANGED";
    public static final String ACTION_SET_FAST_CHARGE_MODE =
            "org.lineageos.settings.charge.ACTION_SET_FAST_CHARGE_MODE";
    public static final String EXTRA_MODE = "org.lineageos.settings.charge.extra.MODE";
    public static final String FAST_CHARGE_MODE_SETTING = "fast_charge_mode";
    public static final String PROP_FASTCHARGE_MODE = "persist.sys.fastcharge.mode";

    public static class ChargingMode {
        public final String mode;
        public final int nameResId;
        public final int descResId;
        public final int iconResId;

        public ChargingMode(String mode, int nameResId, int descResId, int iconResId) {
            this.mode = mode;
            this.nameResId = nameResId;
            this.descResId = descResId;
            this.iconResId = iconResId;
        }
    }

    public static final ChargingMode[] MODES = new ChargingMode[] {
            new ChargingMode("0", R.string.charging_control_tile_slow,
                    R.string.charging_control_slow_desc, R.drawable.ic_charging_slow),
            new ChargingMode("1", R.string.charging_control_tile_fast,
                    R.string.charging_control_fast_desc, R.drawable.ic_charging_fast),
            new ChargingMode("2", R.string.charging_control_tile_superfast,
                    R.string.charging_control_superfast_desc, R.drawable.ic_charging_superfast)
    };

    private ChargingControlUtils() {
    }

    public static String getFastChargeMode(Context context) {
        try {
            String setting = Settings.System.getString(context.getContentResolver(), FAST_CHARGE_MODE_SETTING);
            if (setting != null && !setting.isEmpty()) {
                return setting;
            }
        } catch (Exception ignored) {
        }
        String prop = SystemProperties.get(PROP_FASTCHARGE_MODE, "");
        if (!prop.isEmpty()) {
            return prop;
        }
        return "1";
    }

    public static boolean setFastChargeMode(Context context, String mode) {
        try {
            Settings.System.putString(context.getContentResolver(), FAST_CHARGE_MODE_SETTING, mode);
        } catch (Exception ignored) {
        }

        Intent intent = new Intent(ACTION_SET_FAST_CHARGE_MODE);
        intent.setComponent(new ComponentName("org.lineageos.settings",
                "org.lineageos.settings.charge.ChargingControlReceiver"));
        intent.putExtra(EXTRA_MODE, mode);
        intent.putExtra("mode", mode);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            context.sendBroadcastAsUser(intent, UserHandle.ALL);
        } catch (Exception e) {
            try {
                context.sendBroadcast(intent);
            } catch (Exception ex) {
                Log.e(TAG, "Failed to send charging mode intent from SystemUI", ex);
                return false;
            }
        }
        return true;
    }
}
