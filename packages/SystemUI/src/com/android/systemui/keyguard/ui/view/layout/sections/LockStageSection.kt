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
import com.android.systemui.rr.stage.BatteryRingView
import com.android.systemui.rr.stage.NextAlarmView
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

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            keyguardBlueprintInteractor.get()
                .refreshBlueprint(IntraBlueprintTransition.Type.DefaultTransition)
        }
    }

    private val stateListener = object : StatusBarStateController.StateListener {
        override fun onDozingChanged(isDozing: Boolean) {
            dozing = isDozing
            applyDozing()
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!observing) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(StageSpec.KEY), false, observer, UserHandle.USER_ALL)
            statusBarStateController.addCallback(stateListener)
            dozing = statusBarStateController.isDozing
            observing = true
        }
        spec = StageSpec.parse(
            Settings.System.getStringForUser(context.contentResolver, StageSpec.KEY,
                UserHandle.USER_CURRENT))
        removeComplications(constraintLayout)
        val stage = spec ?: return
        for (tile in stage.tiles) {
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
        for (tile in stage.tiles) {
            val box = box(constraintSet, stage, tile)
            when (tile.id) {
                StageSpec.CLOCK -> place(constraintSet, R.id.clock_ls, box, wrapHeight = true)
                StageSpec.GLANCE -> place(constraintSet, R.id.keyguard_slice_view, box, wrapHeight = true)
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
        for (view in complications.values) constraintLayout.removeView(view)
        complications.clear()
    }

    /** Complications that are not for the always-on display hide while dozing. */
    private fun applyDozing() {
        val stage = spec ?: return
        for ((id, view) in complications) {
            val tile = stage.tile(id) ?: continue
            view.visibility = if (dozing && !tile.aod) View.GONE else View.VISIBLE
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

    /** The two shortcut buttons keep their size, at the tile's ends. */
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
