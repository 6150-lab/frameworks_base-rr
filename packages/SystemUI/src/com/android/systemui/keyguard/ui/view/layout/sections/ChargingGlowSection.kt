/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.rr.chargingglow.ChargingGlowView
import com.android.systemui.statusbar.policy.BatteryController
import javax.inject.Inject

/** The charging glow, full screen and below everything else on the lock screen. */
class ChargingGlowSection
@Inject
constructor(
    private val context: Context,
    private val batteryController: BatteryController,
    private val statusBarStateController: StatusBarStateController,
    private val broadcastDispatcher: BroadcastDispatcher,
) : KeyguardSection() {

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<ChargingGlowView>(R.id.charging_glow) != null) return
        val view =
            ChargingGlowView(context, batteryController, statusBarStateController, broadcastDispatcher)
        view.id = R.id.charging_glow
        constraintLayout.addView(view, 0)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            constrainWidth(R.id.charging_glow, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.charging_glow, ConstraintSet.MATCH_CONSTRAINT)
            connect(R.id.charging_glow, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(R.id.charging_glow, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(R.id.charging_glow, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(R.id.charging_glow, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(R.id.charging_glow)
    }
}
