package com.android.systemui.keyguard.ui.view.layout.sections

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.android.axion.compose.host.AxComposeView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.compose.AxDynamicBarKeyguardChip
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.shared.model.ClockSize
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.media.MediaViewController
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.util.WallpaperDepthUtils
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val CHIP_ABOVE_LOCK_MARGIN_DP = 12f
private const val EXPANDED_BOTTOM_PROTECTION_DP = 16f
private const val UNSET = -1

private const val HIDDEN_VIEWS_FADE_DURATION_START_MS = 50L
private const val HIDDEN_VIEWS_FADE_DURATION_END_MS = 250L

private fun extraBottomMarginPx(context: Context): Int =
    context.resources.getDimensionPixelSize(R.dimen.ax_dynamic_bar_keyguard_chip_extra_bottom_margin)

private val HIDDEN_VIEW_IDS = listOf(
    R.id.keyguard_weather,
    R.id.default_weather_image,
    R.id.default_weather_text,
    R.id.clock_ls,
    R.id.keyguard_info_widgets,
    R.id.keyguard_widgets,
    R.id.shared_notification_container,
    R.id.notificationShelf,
    R.id.bc_smartspace_view,
    R.id.smartspace_card_pager,
    R.id.smartspace_page_indicator,
    R.id.keyguard_slice_view,
    R.id.keyguard_weather_area,
    R.id.now_playing_view,
)

private fun Float.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density + 0.5f).toInt()

class AxDynamicBarKeyguardChipSection
@Inject
constructor(
    @ShadeDisplayAware private val context: Context,
    private val viewModel: AxDynamicBarChipViewModel,
    private val indicationController: KeyguardIndicationController,
    private val clockInteractor: KeyguardClockInteractor,
) : KeyguardSection() {

    private val chipViewId = R.id.ax_dynamic_bar_keyguard_chip
    private var bindHandle: DisposableHandle? = null
    private var expansionHandle: DisposableHandle? = null
    private var enforceListener: ViewTreeObserver.OnPreDrawListener? = null
    private var enforceObserver: ViewTreeObserver? = null
    private var enforceTargets: List<View> = emptyList()
    // Views this section hid; only these are shown again on collapse.
    private val hiddenViews = mutableSetOf<View>()
    // The enforcer leaves views alone until the hide fade has had its time.
    private var enforceAfterUptimeMs = 0L

    override fun addViews(constraintLayout: ConstraintLayout) {
        val composeView = AxComposeView(context).apply { id = chipViewId }
        constraintLayout.addView(composeView)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val composeView: AxComposeView = constraintLayout.requireViewById(chipViewId)

        indicationController.setSuppressIndication(viewModel.shouldSuppressKeyguardIndication.value)

        composeView.setContent {
            PlatformTheme {
                AxDynamicBarKeyguardChip(viewModel = viewModel)
            }
        }

        bindHandle = composeView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.shouldSuppressKeyguardIndication.collect {
                    indicationController.setSuppressIndication(it)
                }
            }
        }

        expansionHandle = composeView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                val scope = this
                scope.launch {
                    viewModel.keyguardExpansion.isHostExpanded.collect { hostExpanded ->
                        if (!hostExpanded) {
                            applyCollapsedLp(composeView, viewModel.isLowUdfps.value)
                            restoreHiddenViews(animate = true)
                        }
                    }
                }
                scope.launch {
                    viewModel.isKeyguardExpanded.collectLatest { expanded ->
                        if (expanded) {
                            clockInteractor.clockSize.collect { size ->
                                if (size != ClockSize.SMALL) {
                                    clockInteractor.setClockSize(ClockSize.SMALL)
                                }
                            }
                        }
                    }
                }
                scope.launch {
                    combine(viewModel.isKeyguardExpanded, viewModel.chipState) { expanded, chipState ->
                        expanded && chipState?.event is IslandEvent.Media
                    }.distinctUntilChanged().collect { expandedMusicOpen ->
                        com.android.systemui.media.MediaViewController.getOrNull()
                            ?.setExpandedMusicOpen(expandedMusicOpen)
                    }
                }
                combine(viewModel.isKeyguardExpanded, viewModel.isLowUdfps) { expanded, lowUdfps ->
                    expanded to lowUdfps
                }.collect { (expanded, lowUdfps) ->
                    onExpandedStateChanged(constraintLayout, composeView, expanded, lowUdfps)
                }
            }
        }
    }

    private fun onExpandedStateChanged(
        constraintLayout: ConstraintLayout,
        composeView: View,
        expanded: Boolean,
        lowUdfps: Boolean,
    ) {
        WallpaperDepthUtils.get()?.setDynamicBarExpanded(expanded)
        rebindPreDrawAction(constraintLayout, expanded)
        TransitionManager.endTransitions(constraintLayout)
        if (expanded) {
            hideViews(constraintLayout)
            applyExpandedLp(composeView)
        }
    }

    private fun rebindPreDrawAction(constraintLayout: ConstraintLayout, expanded: Boolean) {
        removePreDrawAction()
        if (!expanded) return
        // ScrimUtils' keyguard pre-draw hook is never attached, so watch the view tree directly.
        val listener = ViewTreeObserver.OnPreDrawListener {
            enforceHidden()
            true
        }
        enforceObserver = constraintLayout.viewTreeObserver.apply { addOnPreDrawListener(listener) }
        enforceListener = listener
    }

    private fun removePreDrawAction() {
        val listener = enforceListener ?: return
        enforceObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        enforceObserver = null
        enforceListener = null
    }

    private fun hiddenTargets(constraintLayout: ConstraintLayout): List<View> =
        HIDDEN_VIEW_IDS.mapNotNull { constraintLayout.rootView.findViewById<View>(it) }

    private fun hideViews(constraintLayout: ConstraintLayout) {
        enforceTargets = hiddenTargets(constraintLayout)
        enforceAfterUptimeMs = SystemClock.uptimeMillis() +
            (HIDDEN_VIEWS_FADE_DURATION_END_MS * ValueAnimator.getDurationScale()).toLong()
        // Views that are GONE/INVISIBLE belong to their own controllers and are left alone.
        enforceTargets.filter { it.visibility == View.VISIBLE }.forEach { v ->
            hiddenViews.add(v)
            v.animate().cancel()
            v.animate()
                .alpha(0f)
                .setDuration(HIDDEN_VIEWS_FADE_DURATION_END_MS)
                .withEndAction {
                    if (v.visibility == View.VISIBLE) v.visibility = View.INVISIBLE
                }
                .start()
        }
        WallpaperDepthUtils.get()?.hideDepthWallpaper()
    }

    private fun restoreHiddenViews(animate: Boolean) {
        hiddenViews.forEach { v ->
            v.animate().cancel()
            // Still hidden by us; if the owner set GONE meanwhile, it stays that way.
            if (v.visibility == View.INVISIBLE) {
                if (animate) v.alpha = 0f
                v.visibility = View.VISIBLE
            }
            if (v.visibility != View.VISIBLE) return@forEach
            if (animate) {
                v.animate()
                    .alpha(1f)
                    .setDuration(HIDDEN_VIEWS_FADE_DURATION_START_MS)
                    .withEndAction(null)
                    .start()
            } else {
                v.alpha = 1f
            }
        }
        hiddenViews.clear()
        enforceTargets = emptyList()
        WallpaperDepthUtils.get()?.updateDepthWallpaperVisibility()
    }

    // Owners (clock, smartspace, Now Playing, depth wallpaper...) may show their views again while
    // expanded; keep them hidden until collapse restores them.
    private fun enforceHidden() {
        WallpaperDepthUtils.get()?.hideDepthWallpaper()
        if (SystemClock.uptimeMillis() < enforceAfterUptimeMs) return
        enforceTargets.forEach { v ->
            if (v.visibility == View.VISIBLE) {
                hiddenViews.add(v)
                v.visibility = View.INVISIBLE
            }
        }
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val expanded = viewModel.keyguardExpansion.isHostExpanded.value
        val lowUdfps = viewModel.isLowUdfps.value
        val bottomProtectionPx = EXPANDED_BOTTOM_PROTECTION_DP.dpToPx(context)
        val chipAboveLockPx = CHIP_ABOVE_LOCK_MARGIN_DP.dpToPx(context)
        val extraBottomPx = extraBottomMarginPx(context)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        constraintSet.apply {
            when {
                expanded -> {
                    constrainWidth(chipViewId, ConstraintSet.MATCH_CONSTRAINT)
                    constrainHeight(chipViewId, ConstraintSet.MATCH_CONSTRAINT)
                    connect(chipViewId, ConstraintSet.TOP, ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL, ConstraintSet.BOTTOM)
                    connect(chipViewId, ConstraintSet.BOTTOM, R.id.device_entry_icon_view, ConstraintSet.TOP, bottomProtectionPx)
                    connect(chipViewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    connect(chipViewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                lowUdfps -> {
                    constrainWidth(chipViewId, wrap)
                    constrainHeight(chipViewId, wrap)
                    connect(chipViewId, ConstraintSet.BOTTOM, R.id.device_entry_icon_view, ConstraintSet.TOP, chipAboveLockPx + extraBottomPx)
                    connect(chipViewId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    connect(chipViewId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                else -> {
                    constrainWidth(chipViewId, wrap)
                    constrainHeight(chipViewId, wrap)
                    connect(chipViewId, ConstraintSet.BOTTOM, R.id.start_button, ConstraintSet.BOTTOM, extraBottomPx)
                    connect(chipViewId, ConstraintSet.START, R.id.start_button, ConstraintSet.END)
                    connect(chipViewId, ConstraintSet.END, R.id.end_button, ConstraintSet.START)
                }
            }
        }
    }

    private fun applyExpandedLp(composeView: View) {
        val lp = composeView.layoutParams as ConstraintLayout.LayoutParams
        val bottomProtectionPx = EXPANDED_BOTTOM_PROTECTION_DP.dpToPx(context)
        lp.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        lp.height = 0
        lp.topToBottom = ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL
        lp.bottomToTop = R.id.device_entry_icon_view
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        lp.topMargin = 0
        lp.bottomMargin = bottomProtectionPx
        lp.topToTop = UNSET
        lp.bottomToBottom = UNSET
        lp.startToEnd = UNSET
        lp.endToStart = UNSET
        composeView.layoutParams = lp
    }

    private fun applyCollapsedLp(composeView: View, lowUdfps: Boolean) {
        val lp = composeView.layoutParams as ConstraintLayout.LayoutParams
        val extraBottomPx = extraBottomMarginPx(context)
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.topMargin = 0
        lp.topToTop = UNSET
        lp.topToBottom = UNSET
        if (lowUdfps) {
            lp.bottomToTop = R.id.device_entry_icon_view
            lp.bottomToBottom = UNSET
            lp.bottomMargin = CHIP_ABOVE_LOCK_MARGIN_DP.dpToPx(context) + extraBottomPx
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.startToEnd = UNSET
            lp.endToStart = UNSET
        } else {
            lp.bottomToBottom = R.id.start_button
            lp.bottomToTop = UNSET
            lp.bottomMargin = extraBottomPx
            lp.startToEnd = R.id.start_button
            lp.endToStart = R.id.end_button
            lp.startToStart = UNSET
            lp.endToEnd = UNSET
        }
        composeView.layoutParams = lp
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        TransitionManager.endTransitions(constraintLayout)
        removePreDrawAction()
        restoreHiddenViews(animate = false)
        WallpaperDepthUtils.get()?.setDynamicBarExpanded(false)
        MediaViewController.getOrNull()?.setExpandedMusicOpen(false)
        expansionHandle?.dispose()
        expansionHandle = null
        bindHandle?.dispose()
        bindHandle = null
        indicationController.setSuppressIndication(false)
        constraintLayout.removeView(chipViewId)
    }
}
