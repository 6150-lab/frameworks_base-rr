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
import com.android.systemui.res.R
import com.android.systemui.rr.nightstand.BatteryRingView
import com.android.systemui.rr.nightstand.NextAlarmView
import com.android.systemui.rr.nightstand.NightstandController
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.NextAlarmController
import dagger.Lazy
import javax.inject.Inject

/**
 * Nightstand's bedside layout while it is on: the clock face high and centred, the next alarm
 * under it and the battery ring lower down, all drawn for the always-on display. Comes after
 * the other sections, so its constraints win.
 */
class NightstandSection
@Inject
constructor(
    private val context: Context,
    private val batteryController: BatteryController,
    private val nextAlarmController: NextAlarmController,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection() {
    private val handler = Handler(Looper.getMainLooper())
    private var observing = false
    private var active = false
    private var alarm: NextAlarmView? = null
    private var battery: BatteryRingView? = null
    private val guidelines = HashMap<Float, Int>()

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            keyguardBlueprintInteractor.get().refreshBlueprint(
                IntraBlueprintTransition.Config(
                    IntraBlueprintTransition.Type.DefaultTransition,
                    rebuildSections = listOf(this@NightstandSection),
                )
            )
        }
    }

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (!observing) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(NightstandController.KEY_ACTIVE), false, observer,
                UserHandle.USER_ALL)
            observing = true
        }
        active = Settings.System.getIntForUser(context.contentResolver,
            NightstandController.KEY_ACTIVE, 0, UserHandle.USER_CURRENT) == 1
        removeViews(constraintLayout)
        if (!active) return
        alarm = NextAlarmView(context, nextAlarmController).also {
            it.id = View.generateViewId()
            it.dozing = true
            constraintLayout.addView(it)
        }
        battery = BatteryRingView(context, batteryController).also {
            it.id = View.generateViewId()
            it.dozing = true
            constraintLayout.addView(it)
        }
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (!active) return
        val density = context.resources.displayMetrics.density
        val side = (24 * density).toInt()
        val clock = R.id.clock_ls
        val hasClock = constraintSet.getConstraint(clock) != null
        if (hasClock) {
            constraintSet.clear(clock)
            constraintSet.connect(clock, ConstraintSet.TOP, guideline(constraintSet, CLOCK_TOP), ConstraintSet.TOP)
            constraintSet.connect(clock, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, side)
            constraintSet.connect(clock, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, side)
            constraintSet.constrainWidth(clock, ConstraintSet.MATCH_CONSTRAINT)
            constraintSet.constrainHeight(clock, ConstraintSet.WRAP_CONTENT)
            constraintSet.setElevation(clock, 1f)
        }
        alarm?.let { view ->
            constraintSet.constrainWidth(view.id, ConstraintSet.WRAP_CONTENT)
            constraintSet.constrainHeight(view.id, ConstraintSet.WRAP_CONTENT)
            constraintSet.connect(view.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            constraintSet.connect(view.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            if (hasClock) {
                constraintSet.connect(view.id, ConstraintSet.TOP, clock, ConstraintSet.BOTTOM, (16 * density).toInt())
            } else {
                constraintSet.connect(view.id, ConstraintSet.TOP, guideline(constraintSet, ALARM_TOP), ConstraintSet.TOP)
            }
            constraintSet.setElevation(view.id, 1f)
        }
        battery?.let { view ->
            val size = (128 * density).toInt()
            constraintSet.constrainWidth(view.id, size)
            constraintSet.constrainHeight(view.id, size)
            constraintSet.connect(view.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            constraintSet.connect(view.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constraintSet.connect(view.id, ConstraintSet.TOP, guideline(constraintSet, BATTERY_TOP), ConstraintSet.TOP)
            constraintSet.setElevation(view.id, 1f)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        alarm?.let { constraintLayout.removeView(it) }
        battery?.let { constraintLayout.removeView(it) }
        alarm = null
        battery = null
    }

    /** A horizontal guideline at a fraction of the height, reused across rebuilds. */
    private fun guideline(set: ConstraintSet, fraction: Float): Int {
        val id = guidelines.getOrPut(fraction) { View.generateViewId() }
        set.create(id, ConstraintSet.HORIZONTAL_GUIDELINE)
        set.setGuidelinePercent(id, fraction)
        return id
    }

    companion object {
        /** Where the parts sit, as fractions of the screen height; Remixer's preview matches. */
        const val CLOCK_TOP = 0.12f
        const val ALARM_TOP = 0.46f
        const val BATTERY_TOP = 0.62f
    }
}
