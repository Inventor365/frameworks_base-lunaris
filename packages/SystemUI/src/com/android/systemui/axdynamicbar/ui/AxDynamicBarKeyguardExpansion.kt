/*
 * Copyright 2025-2026 AxionOS
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

package com.android.systemui.axdynamicbar.ui

import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class AxDynamicBarKeyguardExpansion
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val interactor: AxDynamicBarInteractor,
) {
    private val _intent = MutableStateFlow(false)

    private val hasChip =
        interactor.uiState
            .map { it.shouldShow && it.topEvent != null }
            .distinctUntilChanged()

    val canShow: StateFlow<Boolean> = run {
        val contextAndDoze =
            combine(
                interactor.isOnKeyguard,
                interactor.isDozing,
                interactor.dozeAmount.map { it > 0f }.distinctUntilChanged(),
                interactor.qsExpansion.map { it > 0f }.distinctUntilChanged(),
                interactor.isPanelExpanded,
            ) { onKg, dozing, dozeAmt, qs, panelExp ->
                onKg && !dozing && !dozeAmt && !qs && !panelExp
            }
        val shadeAndBouncer =
            combine(
                interactor.isBouncerShowing,
                interactor.legacyShadeExpansion.map { it >= 0.95f }.distinctUntilChanged(),
                hasChip,
            ) { bouncer, shadeFull, chip ->
                !bouncer && shadeFull && chip
            }
        combine(contextAndDoze, shadeAndBouncer) { a, b -> a && b }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)
    }

    val isExpanded: StateFlow<Boolean> =
        combine(_intent, canShow) { intent, show -> intent && show }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    private val _isContentVisible = MutableStateFlow(false)

    /**
     * Whether the keyguard host must keep its expanded layout: the panel is expanded or its exit
     * animation is still on screen. Dozing drops it at once so screen off never waits on a frame.
     */
    val isHostExpanded: StateFlow<Boolean> =
        combine(isExpanded, _isContentVisible, interactor.isDozing) { expanded, visible, dozing ->
            expanded || (visible && !dozing)
        }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    fun setContentVisible(visible: Boolean) {
        _isContentVisible.value = visible
    }

    init {
        // An expansion lives only while it can be shown. Doze/screen off, bouncer, shade, QS,
        // leaving keyguard or losing the chip end it instead of pausing it until they go away.
        canShow
            .onEach { if (!it) collapse() }
            .launchIn(applicationScope)
    }

    fun expand() {
        if (interactor.uiState.value.topEvent == null) return
        _intent.value = true
    }

    fun collapse() {
        _intent.value = false
    }

    fun toggle() {
        if (isExpanded.value) collapse() else expand()
    }
}
