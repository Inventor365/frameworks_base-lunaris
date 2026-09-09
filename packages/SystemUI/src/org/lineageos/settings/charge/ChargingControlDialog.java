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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import org.lineageos.settings.charge.ChargingControlUtils.ChargingMode;

/**
 * SystemUI-native floating panel for Yuki Charging Service selection,
 * matching Android 16/QPR2 Internet panel behavior and DialogTransitionAnimator.
 */
public class ChargingControlDialog extends SystemUIDialog {
    private static final String TAG = "ChargingControlDialog";

    private final Context mContext;
    private View mDialogRoot;
    private View mHeader;
    private ScrollView mModesScrollView;
    private LinearLayout mModesContainer;
    private View mFooter;
    private Button mDoneButton;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshModes();
        }
    };

    private final ContentObserver mObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            refreshModes();
        }
    };

    public ChargingControlDialog(@NonNull Context context) {
        super(context, R.style.Theme_PowerProfile_Dialog, DEFAULT_DISMISS_ON_DEVICE_LOCK,
                com.android.systemui.Dependency.get(com.android.systemui.statusbar.phone.SystemUIDialogManager.class),
                com.android.systemui.Dependency.get(com.android.systemui.broadcast.BroadcastDispatcher.class),
                com.android.systemui.Dependency.get(com.android.systemui.animation.DialogTransitionAnimator.class),
                new com.android.systemui.statusbar.phone.DialogDelegate<SystemUIDialog>() {},
                /* shouldAcsdDismissDialog= */ false);
        mContext = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        View dialogView = LayoutInflater.from(getContext()).inflate(R.layout.charging_control_dialog_layout, null);
        Window window = getWindow();
        if (window != null) {
            window.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);
            window.setGravity(Gravity.CENTER);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setContentView(dialogView);
        }

        setCanceledOnTouchOutside(true);
        mDialogRoot = findViewById(R.id.charging_control_dialog_root);
        mHeader = findViewById(R.id.dialog_header);
        mModesScrollView = findViewById(R.id.modes_scroll_view);
        mModesContainer = findViewById(R.id.modes_container);
        mFooter = findViewById(R.id.dialog_footer);
        mDoneButton = findViewById(R.id.done_button);

        if (mDoneButton != null) {
            mDoneButton.setOnClickListener(v -> dismiss());
        }

        if (window != null) {
            View decorView = window.getDecorView();
            if (decorView != null) {
                decorView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                        updateDialogDimensions();
                    }
                });
            }
        }

        refreshModes();
        updateDialogDimensions();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateDialogDimensions();
    }

    @Override
    protected void start() {
        super.start();
        IntentFilter filter = new IntentFilter(ChargingControlUtils.ACTION_CHARGING_CONTROL_CHANGED);
        try {
            mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register charging receiver in SystemUI", e);
        }
        try {
            mContext.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(ChargingControlUtils.FAST_CHARGE_MODE_SETTING),
                    false,
                    mObserver);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register charging observer in SystemUI", e);
        }
        refreshModes();
    }

    @Override
    protected void stop() {
        super.stop();
        try {
            mContext.unregisterReceiver(mReceiver);
        } catch (Exception ignored) {
        }
        try {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        } catch (Exception ignored) {
        }
    }

    public void refreshModes() {
        if (mModesContainer == null) return;

        final int scrollY = mModesScrollView != null ? mModesScrollView.getScrollY() : 0;
        mModesContainer.removeAllViews();
        String currentMode = ChargingControlUtils.getFastChargeMode(mContext);
        LayoutInflater inflater = LayoutInflater.from(mContext);

        int activeOnContainerColor = resolveColorByName(mContext,
                "materialColorOnPrimaryContainer", android.R.attr.textColorPrimaryInverse, 0xFFFFFFFF);
        int inactiveTitleColor = resolveColorByName(mContext,
                "materialColorOnSurface", android.R.attr.textColorPrimary, 0xFFFFFFFF);
        int inactiveDescColor = resolveColorByName(mContext,
                "materialColorOnSurfaceVariant", android.R.attr.textColorSecondary, 0xAAFFFFFF);
        int accentColor = resolveColor(mContext,
                android.R.attr.colorAccent, 0xFF386540);

        for (ChargingMode modeItem : ChargingControlUtils.MODES) {
            View itemView = inflater.inflate(R.layout.power_profile_item_layout, mModesContainer, false);

            ImageView iconView = itemView.findViewById(R.id.profile_icon);
            TextView titleView = itemView.findViewById(R.id.profile_title);
            TextView descView = itemView.findViewById(R.id.profile_desc);
            ImageView checkView = itemView.findViewById(R.id.profile_check);

            boolean isActive = modeItem.mode.equals(currentMode);

            iconView.setImageResource(modeItem.iconResId);
            titleView.setText(modeItem.nameResId);
            descView.setText(modeItem.descResId);

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
                Log.d(TAG, "Charging mode clicked in SystemUI: " + modeItem.mode);
                ChargingControlUtils.setFastChargeMode(mContext, modeItem.mode);
                refreshModes();
            });

            mModesContainer.addView(itemView);
        }

        updateDialogDimensions();

        if (mModesScrollView != null && scrollY > 0) {
            mModesScrollView.post(() -> mModesScrollView.scrollTo(0, scrollY));
        }
    }

    private void updateDialogDimensions() {
        if (mModesScrollView == null) return;

        Context context = mContext != null ? mContext : getContext();
        Configuration config = context.getResources().getConfiguration();
        boolean isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int screenHeightPx = dm.heightPixels;
        float density = dm.density;

        if (isLandscape) {
            int availableHeightPx = screenHeightPx;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                WindowManager wm = context.getSystemService(WindowManager.class);
                if (wm != null) {
                    try {
                        WindowMetrics wmBounds = wm.getCurrentWindowMetrics();
                        Rect bounds = wmBounds.getBounds();
                        Insets insets = wmBounds.getWindowInsets().getInsetsIgnoringVisibility(
                                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                        int heightWithoutInsets = bounds.height() - insets.top - insets.bottom;
                        if (heightWithoutInsets > 0) {
                            availableHeightPx = heightWithoutInsets;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            // Reserve 24dp vertical margin (12dp top + 12dp bottom) to ensure a floating panel appearance
            int marginVerticalPx = Math.round(24 * density);
            int maxDialogHeightPx = availableHeightPx - marginVerticalPx;

            int rootPaddingPx = mDialogRoot != null
                    ? (mDialogRoot.getPaddingTop() + mDialogRoot.getPaddingBottom())
                    : Math.round(36 * density);

            int headerHeightPx = 0;
            if (mHeader != null) {
                mHeader.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                headerHeightPx = mHeader.getMeasuredHeight();
            }
            if (headerHeightPx <= 0) {
                headerHeightPx = Math.round(60 * density);
            }

            int footerHeightPx = 0;
            if (mFooter != null) {
                mFooter.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                ViewGroup.MarginLayoutParams footerLp = (ViewGroup.MarginLayoutParams) mFooter.getLayoutParams();
                int footerMarginTop = footerLp != null ? footerLp.topMargin : Math.round(16 * density);
                footerHeightPx = mFooter.getMeasuredHeight() + footerMarginTop;
            }
            if (footerHeightPx <= 0) {
                footerHeightPx = Math.round(54 * density);
            }

            int nonScrollableHeightPx = rootPaddingPx + headerHeightPx + footerHeightPx;
            int maxScrollHeightPx = maxDialogHeightPx - nonScrollableHeightPx;

            int minScrollHeightPx = Math.round(130 * density);
            if (maxScrollHeightPx < minScrollHeightPx) {
                maxScrollHeightPx = minScrollHeightPx;
            }

            ViewGroup.LayoutParams lp = mModesScrollView.getLayoutParams();
            if (lp != null && lp.height != maxScrollHeightPx) {
                lp.height = maxScrollHeightPx;
                mModesScrollView.setLayoutParams(lp);
            }
        } else {
            ViewGroup.LayoutParams lp = mModesScrollView.getLayoutParams();
            if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                mModesScrollView.setLayoutParams(lp);
            }
        }
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
