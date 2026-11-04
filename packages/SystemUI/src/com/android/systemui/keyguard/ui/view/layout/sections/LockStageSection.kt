/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import com.android.systemui.rr.stage.BatteryRingView
import com.android.systemui.rr.stage.NextAlarmView
import com.android.systemui.rr.nightstand.NightstandController
import com.android.systemui.rr.stage.PageDotsView
import com.android.systemui.rr.stage.StageSpec
import com.android.systemui.rr.stage.StageTile
import com.android.systemui.rr.stage.TogglesView
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.NextAlarmController
import dagger.Lazy
import javax.inject.Inject

/**
 * Lock Stage: the layout Remixer's editor saved. Every tile becomes a box of percent guidelines
 * on the keyguard root; the clock, glance, weather, Now Bar and shortcuts move into theirs, and
 * the complications (battery ring, next alarm, toggles) are added here. Comes after the other
 * sections, so its constraints win.
 */
class LockStageSection
@Inject
constructor(
    private val context: Context,
    private val statusBarStateController: StatusBarStateController,
    private val batteryController: BatteryController,
    private val nextAlarmController: NextAlarmController,
    private val flashlightController: FlashlightController,
    private val activityStarter: ActivityStarter,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection() {
    private val handler = Handler(Looper.getMainLooper())
    private var spec: StageSpec? = null
    private val complications = HashMap<String, View>()
    private val guidelines = HashMap<String, Int>()
    private var observing = false
    private var dozing = false
    /** Nightstand is on: the night stage, and every tile stays on the always-on display. */
    private var night = false
    /** The page on show; the always-on display goes back to the first. */
    private var page = 0
    private var dots: PageDotsView? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = rebuild()
    }

    /** Re-reads the layout and rebuilds this section's views; a plain refresh keeps the old spec. */
    private fun rebuild() {
        keyguardBlueprintInteractor.get().refreshBlueprint(
            IntraBlueprintTransition.Config(
                IntraBlueprintTransition.Type.DefaultTransition,
                rebuildSections = listOf(this),
            )
        )
    }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) {
            dozing = isDozing
            if (isDozing && page != 0) {
                page = 0
                rebuild()
                return
            }
            applyDozing()
        }
    }

    /** A sideways swipe on the lock screen: the next or previous page, if there is one. */
    fun swipe(direction: Int) {
        val stage = spec ?: return
        val next = (page + direction).coerceIn(0, stage.pages.size - 1)
        if (next == page || dozing) return
        page = next
        rebuild()
        handler.post { dots?.show() }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!observing) {
            for (key in arrayOf(StageSpec.KEY, NightstandController.KEY_STAGE,
                    NightstandController.KEY_ACTIVE)) {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL)
            }
            statusBarStateController.addCallback(stateListener)
            dozing = statusBarStateController.isDozing
            observing = true
        }
        night = Settings.System.getIntForUser(context.contentResolver,
            NightstandController.KEY_ACTIVE, 0, UserHandle.USER_CURRENT) == 1
        spec = StageSpec.parse(
            Settings.System.getStringForUser(context.contentResolver,
                if (night) NightstandController.KEY_STAGE else StageSpec.KEY,
                UserHandle.USER_CURRENT))
        removeComplications(constraintLayout)
        current = this
        val stage = spec ?: return
        if (page >= stage.pages.size) page = 0
        if (stage.pages.size > 1) {
            val view = PageDotsView(context).apply {
                id = View.generateViewId()
                pages = stage.pages.size
                current = page
            }
            dots = view
            constraintLayout.addView(view)
        }
        for (tile in stage.page(page)) {
            val view: View = when (tile.id) {
                StageSpec.BATTERY -> BatteryRingView(context, batteryController)
                StageSpec.ALARM -> NextAlarmView(context, nextAlarmController)
                StageSpec.TOGGLES -> TogglesView(context, flashlightController, activityStarter)
                else -> continue
            }
            view.id = View.generateViewId()
            complications[tile.id] = view
            constraintLayout.addView(view)
        }
        applyDozing()
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val stage = spec ?: return
        dots?.let { view ->
            constraintSet.constrainWidth(view.id, ConstraintSet.WRAP_CONTENT)
            constraintSet.constrainHeight(view.id, ConstraintSet.WRAP_CONTENT)
            constraintSet.connect(view.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            constraintSet.connect(view.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constraintSet.connect(view.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM,
                (120 * context.resources.displayMetrics.density).toInt())
        }
        for (tile in stage.page(page)) {
            val box = box(constraintSet, stage, tile)
            when (tile.id) {
                StageSpec.CLOCK -> place(constraintSet, R.id.clock_ls, box, wrapHeight = true)
                StageSpec.GLANCE -> glance(constraintSet, box)
                StageSpec.WEATHER -> place(constraintSet, R.id.keyguard_weather, box, wrapHeight = true)
                StageSpec.NOW_BAR -> place(constraintSet, R.id.now_bar, box, wrapHeight = true)
                StageSpec.SHORTCUTS -> shortcuts(constraintSet, box)
                else -> complications[tile.id]?.let { place(constraintSet, it.id, box, wrapHeight = false) }
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        removeComplications(constraintLayout)
    }

    private fun removeComplications(constraintLayout: ConstraintLayout) {
        dots?.let { constraintLayout.removeView(it) }
        dots = null
        for (view in complications.values) constraintLayout.removeView(view)
        complications.clear()
    }

    /** Complications that are not for the always-on display hide while dozing. */
    private fun applyDozing() {
        val stage = spec ?: return
        for ((id, view) in complications) {
            val tile = stage.tile(id, page) ?: continue
            view.visibility = if (dozing && !tile.aod && !night) View.GONE else View.VISIBLE
            when (view) {
                is BatteryRingView -> view.dozing = dozing
                is NextAlarmView -> view.dozing = dozing
            }
        }
    }

    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Four percent guidelines around the tile, reused across rebuilds by their position. */
    private fun box(set: ConstraintSet, stage: StageSpec, tile: StageTile): Box {
        fun guideline(key: String, orientation: Int, percent: Float): Int {
            val id = guidelines.getOrPut(key) { View.generateViewId() }
            set.create(id, orientation)
            set.setGuidelinePercent(id, percent)
            return id
        }
        val cols = stage.cols.toFloat()
        val rows = stage.rows.toFloat()
        return Box(
            guideline("x${tile.x}", ConstraintSet.VERTICAL_GUIDELINE, tile.x / cols),
            guideline("y${tile.y}", ConstraintSet.HORIZONTAL_GUIDELINE, tile.y / rows),
            guideline("x${tile.x + tile.w}", ConstraintSet.VERTICAL_GUIDELINE, (tile.x + tile.w) / cols),
            guideline("y${tile.y + tile.h}", ConstraintSet.HORIZONTAL_GUIDELINE, (tile.y + tile.h) / rows),
        )
    }

    private fun place(set: ConstraintSet, id: Int, box: Box, wrapHeight: Boolean) {
        if (set.getConstraint(id) == null) return
        val inset = (6 * context.resources.displayMetrics.density).toInt()
        set.clear(id)
        set.connect(id, ConstraintSet.START, box.left, ConstraintSet.START, inset)
        set.connect(id, ConstraintSet.END, box.right, ConstraintSet.START, inset)
        set.connect(id, ConstraintSet.TOP, box.top, ConstraintSet.TOP, inset)
        set.connect(id, ConstraintSet.BOTTOM, box.bottom, ConstraintSet.TOP, inset)
        set.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(id, if (wrapHeight) ConstraintSet.WRAP_CONTENT else ConstraintSet.MATCH_CONSTRAINT)
        set.setVerticalBias(id, 0f)
        set.setElevation(id, 1f)
    }

    companion object {
        /** The section on the lock screen now, for the shade's swipe detector. */
        @Volatile private var current: LockStageSection? = null

        @JvmStatic
        fun swipePage(direction: Int) {
            current?.swipe(direction)
        }
    }

    /** The two shortcut buttons keep their size, at the tile's ends. */
    /** The date row, or Google's At a Glance date with its cards under it when that is on. */
    private fun glance(set: ConstraintSet, box: Box) {
        place(set, R.id.keyguard_slice_view, box, wrapHeight = true)
        place(set, sharedR.id.date_smartspace_view, box, wrapHeight = true)
        val cards = sharedR.id.bc_smartspace_view
        if (set.getConstraint(cards) == null || set.getConstraint(sharedR.id.date_smartspace_view) == null) return
        val inset = (6 * context.resources.displayMetrics.density).toInt()
        set.clear(cards)
        set.connect(cards, ConstraintSet.START, box.left, ConstraintSet.START, inset)
        set.connect(cards, ConstraintSet.END, box.right, ConstraintSet.START, inset)
        set.connect(cards, ConstraintSet.TOP, sharedR.id.date_smartspace_view, ConstraintSet.BOTTOM)
        set.constrainWidth(cards, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(cards, ConstraintSet.WRAP_CONTENT)
        set.setElevation(cards, 1f)
    }

    private fun shortcuts(set: ConstraintSet, box: Box) {
        val res = context.resources
        val width = res.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width)
        val height = res.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height)
        for ((id, side) in listOf(R.id.start_button to ConstraintSet.START, R.id.end_button to ConstraintSet.END)) {
            if (set.getConstraint(id) == null) continue
            set.clear(id)
            set.constrainWidth(id, width)
            set.constrainHeight(id, height)
            set.connect(id, side, if (side == ConstraintSet.START) box.left else box.right, ConstraintSet.START)
            set.connect(id, ConstraintSet.TOP, box.top, ConstraintSet.TOP)
            set.connect(id, ConstraintSet.BOTTOM, box.bottom, ConstraintSet.TOP)
            set.setVisibilityMode(id, ConstraintSet.VISIBILITY_MODE_IGNORE)
        }
    }
}
