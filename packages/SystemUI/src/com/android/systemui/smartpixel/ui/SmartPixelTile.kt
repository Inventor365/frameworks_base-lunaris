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

package com.android.systemui.smartpixel.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import androidx.annotation.Nullable
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.UserSettingObserver
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import com.google.android.material.materialswitch.MaterialSwitch
import javax.inject.Inject

class SmartPixelTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val secureSettings: SecureSettings,
    userTracker: UserTracker,
) : QSTileImpl<BooleanState>(
    host,
    uiEventLogger,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {
    companion object {
        const val TILE_SPEC = "smart_pixels"
        private val SMART_PIXELS_SETTINGS =
            Intent("android.settings.SMART_PIXELS_SETTINGS").setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$SmartPixelsActivity",
                )
            )
    }

    private val setting =
        object : UserSettingObserver(
            secureSettings,
            mHandler,
            Settings.Secure.SMART_PIXEL_FILTER_ENABLED,
            userTracker.userId,
        ) {
            override fun handleValueChanged(value: Int, observedChange: Boolean) {
                handleRefreshState(value)
            }
        }

    override fun handleDestroy() {
        super.handleDestroy()
        setting.setListening(false)
    }

    override fun newTileState(): BooleanState {
        val state = BooleanState()
        state.handlesLongClick = true
        return state
    }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        setting.setListening(listening)
    }

    override fun handleUserSwitch(newUserId: Int) {
        setting.setUserId(newUserId)
        handleRefreshState(setting.getValue())
    }

    override fun handleClick(expandable: Expandable?) {
        val newState = !mState.value
        setting.setValue(if (newState) 1 else 0)
    }

    override fun getLongClickIntent(): Intent = SMART_PIXELS_SETTINGS

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_smart_pixels_label)

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val value = if (arg is Int) arg else setting.getValue()
        val enabled = value != 0
        state.value = enabled
        state.label = getTileLabel()
        state.secondaryLabel = mContext.getString(
            if (enabled) R.string.quick_settings_smart_pixels_on
            else R.string.quick_settings_smart_pixels_off,
        )
        state.contentDescription = state.label
        state.expandedAccessibilityClassName = MaterialSwitch::class.java.name
        state.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.icon = ResourceIcon.get(
            if (enabled) R.drawable.qs_smart_pixels_icon_on
            else R.drawable.qs_smart_pixels_icon_off
        )
    }

    override fun isAvailable(): Boolean = true

    override fun getMetricsCategory(): Int = MetricsEvent.QS_PANEL
}
