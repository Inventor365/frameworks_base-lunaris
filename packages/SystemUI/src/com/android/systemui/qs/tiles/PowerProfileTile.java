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

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.lineageos.settings.power.PowerProfileDialog;
import org.lineageos.settings.power.PowerProfileUtils;
import org.lineageos.settings.power.PowerProfileUtils.PowerProfile;

import javax.inject.Inject;

public class PowerProfileTile extends QSTileImpl<QSTile.State> {

    public static final String TILE_SPEC = "power_profile";
    private static final String INTERACTION_JANK_TAG = "power_profile";

    private static final Intent SETTINGS_INTENT = new Intent()
            .setClassName("org.lineageos.settings", "org.lineageos.settings.power.PowerProfileActivity");

    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final KeyguardDismissUtil mKeyguardDismissUtil;
    private final KeyguardStateController mKeyguardStateController;

    private boolean mListening;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    @Inject
    public PowerProfileTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            DialogTransitionAnimator dialogTransitionAnimator,
            KeyguardDismissUtil keyguardDismissUtil,
            KeyguardStateController keyguardStateController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager,
                metricsLogger, statusBarStateController, activityStarter, qsLogger);
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mKeyguardStateController = keyguardStateController;
    }

    @Override
    public QSTile.State newTileState() {
        return new QSTile.State();
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.powerprofile_title);
    }

    @Override
    public Intent getLongClickIntent() {
        return SETTINGS_INTENT;
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (!PowerProfileUtils.isPowerEnabled(mContext)) {
            return;
        }

        mUiHandler.post(() -> mKeyguardDismissUtil.executeWhenUnlocked(() -> {
            PowerProfileDialog dialog = new PowerProfileDialog(mContext);
            if (expandable != null && !mKeyguardStateController.isShowing()) {
                DialogTransitionAnimator.Controller controller = expandable.dialogTransitionController(
                        new DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG));
                if (controller != null) {
                    mDialogTransitionAnimator.show(dialog, controller);
                } else {
                    dialog.show();
                }
            } else {
                dialog.show();
            }
            return false;
        }, false /* requiresShadeOpen */, true /* afterKeyguardDone */));
    }

    @Override
    protected void handleUpdateState(QSTile.State state, Object arg) {
        if (!PowerProfileUtils.isPowerEnabled(mContext)) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.label = mContext.getString(R.string.powerprofile_title);
            state.secondaryLabel = mContext.getString(R.string.power_tile_disabled_subtitle);
            state.icon = ResourceIcon.get(R.drawable.ic_power_default);
            return;
        }

        PowerProfile current = PowerProfileUtils.getCurrentProfile(mContext);
        state.state = Tile.STATE_ACTIVE;
        state.label = mContext.getString(R.string.powerprofile_title);
        state.secondaryLabel = mContext.getString(current.getNameResId());
        state.icon = ResourceIcon.get(current.getIconResId());
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            IntentFilter filter = new IntentFilter(PowerProfileUtils.ACTION_PROFILE_CHANGED);
            mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            refreshState();
        } else {
            try {
                mContext.unregisterReceiver(mReceiver);
            } catch (Exception ignored) {
            }
        }
    }
}
