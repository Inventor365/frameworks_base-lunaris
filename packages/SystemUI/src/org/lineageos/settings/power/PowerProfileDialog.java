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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import org.lineageos.settings.power.PowerProfileUtils.PowerProfile;

/**
 * SystemUI-native floating panel for Power Profile selection,
 * matching Android 16/QPR2 Internet panel behavior.
 */
public class PowerProfileDialog extends SystemUIDialog {
    private static final String TAG = "PowerProfileDialog";

    private static final PowerProfile[] PROFILES = new PowerProfile[] {
            PowerProfile.DEFAULT,
            PowerProfile.BATTERY,
            PowerProfile.PERFORMANCE,
            PowerProfile.GAMING,
            PowerProfile.AUTO
    };

    private final Context mContext;
    private LinearLayout mProfilesContainer;
    private LinearLayout mHtsrToggleButton;
    private ImageView mHtsrIcon;
    private TextView mHtsrText;
    private Button mDoneButton;

    private final BroadcastReceiver mProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Broadcast received in SystemUI: " + intent.getAction());
            refreshProfiles();
        }
    };

    public PowerProfileDialog(@NonNull Context context) {
        super(context, R.style.Theme_PowerProfile_Dialog);
        mContext = context;
        Log.d(TAG, "PowerProfileDialog constructed in SystemUI");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        Window window = getWindow();
        if (window != null) {
            window.requestFeature(Window.FEATURE_NO_TITLE);
            window.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.CENTER);
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }

        View mDialogView = LayoutInflater.from(getContext()).inflate(R.layout.power_profile_dialog_layout, null);
        if (window != null) {
            window.setContentView(mDialogView);
        }

        setCanceledOnTouchOutside(true);
        mProfilesContainer = findViewById(R.id.profiles_container);
        mHtsrToggleButton = findViewById(R.id.htsr_toggle_button);
        mHtsrIcon = findViewById(R.id.htsr_icon);
        mHtsrText = findViewById(R.id.htsr_text);
        mDoneButton = findViewById(R.id.done_button);

        if (mDoneButton != null) {
            mDoneButton.setOnClickListener(v -> {
                Log.d(TAG, "Done button clicked -> dismissing SystemUI dialog");
                dismiss();
            });
        }

        refreshProfiles();
    }

    @Override
    protected void start() {
        super.start();
        Log.d(TAG, "start");
        IntentFilter filter = new IntentFilter();
        filter.addAction(PowerProfileUtils.ACTION_PROFILE_CHANGED);
        filter.addAction("org.lineageos.settings.touchsampling.ACTION_UPDATE_TILE");
        try {
            mContext.registerReceiver(
                    mProfileReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register profile receiver in SystemUI", e);
        }
        refreshProfiles();
    }

    @Override
    protected void stop() {
        super.stop();
        Log.d(TAG, "stop");
        try {
            mContext.unregisterReceiver(mProfileReceiver);
        } catch (Exception e) {
            Log.d(TAG, "Error unregistering receiver in SystemUI", e);
        }
    }

    @Override
    public void dismiss() {
        Log.d(TAG, "dismiss called");
        super.dismiss();
    }

    public void refreshProfiles() {
        if (mProfilesContainer == null) return;

        mProfilesContainer.removeAllViews();
        PowerProfile currentProfile = PowerProfileUtils.getCurrentProfile(mContext);
        LayoutInflater inflater = LayoutInflater.from(mContext);

        int activeOnContainerColor = resolveColorByName(mContext,
                "materialColorOnPrimaryContainer", android.R.attr.textColorPrimaryInverse, 0xFFFFFFFF);
        int inactiveTitleColor = resolveColorByName(mContext,
                "materialColorOnSurface", android.R.attr.textColorPrimary, 0xFFFFFFFF);
        int inactiveDescColor = resolveColorByName(mContext,
                "materialColorOnSurfaceVariant", android.R.attr.textColorSecondary, 0xAAFFFFFF);
        int accentColor = resolveColor(mContext,
                android.R.attr.colorAccent, 0xFF386540);

        for (PowerProfile profile : PROFILES) {
            View itemView = inflater.inflate(R.layout.power_profile_item_layout, mProfilesContainer, false);

            ImageView iconView = itemView.findViewById(R.id.profile_icon);
            TextView titleView = itemView.findViewById(R.id.profile_title);
            TextView descView = itemView.findViewById(R.id.profile_desc);
            ImageView checkView = itemView.findViewById(R.id.profile_check);

            boolean isActive = (profile == currentProfile);

            iconView.setImageResource(profile.getIconResId());
            titleView.setText(profile.getNameResId());
            descView.setText(profile.getDescResId());

            if (isActive) {
                itemView.setBackgroundResource(R.drawable.power_profile_item_bg_active);
                titleView.setTextColor(activeOnContainerColor);
                descView.setTextColor(activeOnContainerColor);
                iconView.setImageTintList(ColorStateList.valueOf(activeOnContainerColor));
                checkView.setVisibility(View.VISIBLE);
                checkView.setImageTintList(ColorStateList.valueOf(activeOnContainerColor));
            } else {
                itemView.setBackgroundResource(R.drawable.power_profile_item_bg_inactive);
                titleView.setTextColor(inactiveTitleColor);
                descView.setTextColor(inactiveDescColor);
                iconView.setImageTintList(ColorStateList.valueOf(accentColor));
                checkView.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                Log.d(TAG, "Profile clicked in SystemUI: " + profile);
                PowerProfileUtils.applyProfile(mContext, profile);
                refreshProfiles();
            });

            mProfilesContainer.addView(itemView);
        }

        updateHtsrButton(activeOnContainerColor, inactiveTitleColor, inactiveDescColor);
    }

    private void updateHtsrButton(int activeColor, int inactiveTitleColor, int inactiveDescColor) {
        if (mHtsrToggleButton == null || mHtsrIcon == null || mHtsrText == null) return;

        boolean isHtsrActive = PowerProfileUtils.isHtsrActive(mContext);

        if (isHtsrActive) {
            mHtsrToggleButton.setBackgroundResource(R.drawable.power_profile_htsr_bg_active);
            mHtsrText.setText(R.string.power_chip_htsr_on);
            mHtsrText.setTextColor(activeColor);
            mHtsrIcon.setImageTintList(ColorStateList.valueOf(activeColor));
        } else {
            mHtsrToggleButton.setBackgroundResource(R.drawable.power_profile_htsr_bg_inactive);
            mHtsrText.setText(R.string.power_chip_htsr_off);
            mHtsrText.setTextColor(inactiveTitleColor);
            mHtsrIcon.setImageTintList(ColorStateList.valueOf(inactiveDescColor));
        }

        mHtsrToggleButton.setOnClickListener(v -> {
            boolean currentHtsrState = PowerProfileUtils.isHtsrActive(mContext);
            boolean newHtsrState = !currentHtsrState;
            Log.d(TAG, "HTSR button clicked in SystemUI: " + currentHtsrState + " -> " + newHtsrState);
            boolean success = PowerProfileUtils.updateTouchSampling(mContext, newHtsrState);
            if (!success) {
                Toast.makeText(mContext, R.string.power_htsr_apply_failed, Toast.LENGTH_SHORT).show();
            }
            refreshProfiles();
        });
    }

    private static int resolveColorByName(Context context, String resName, int fallbackAttrResId, int defaultColor) {
        int resId = context.getResources().getIdentifier(resName, "color", "android");
        if (resId != 0) {
            try {
                return context.getColor(resId);
            } catch (Exception ignored) {
            }
        }
        return resolveColor(context, fallbackAttrResId, defaultColor);
    }

    private static int resolveColor(Context context, int attrResId, int defaultColor) {
        TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(attrResId, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data;
            }
            try {
                return context.getColor(typedValue.resourceId);
            } catch (Exception ignored) {
            }
        }
        return defaultColor;
    }
}
