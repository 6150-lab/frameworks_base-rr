/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.rr.nowbar.NowBarRepository
import com.android.systemui.rr.nowbar.NowBarView
import javax.inject.Inject

/** The Now Bar, between the lock screen shortcuts like One UI's. */
class NowBarSection
@Inject
constructor(
    private val context: Context,
    private val repository: NowBarRepository,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    private val broadcastDispatcher: BroadcastDispatcher,
) : KeyguardSection() {

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(R.id.now_bar) != null) return
        val view = NowBarView(
            context, repository, statusBarStateController, activityStarter, broadcastDispatcher
        )
        view.id = R.id.now_bar
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val res = context.resources
        val density = res.displayMetrics.density
        // The shortcuts' slots stay free even when no shortcut is set, so the bar never jumps.
        val side = res.getDimensionPixelSize(R.dimen.keyguard_affordance_horizontal_offset) +
            res.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width) +
            (GAP * density).toInt()
        // The capsule's middle on the shortcuts' middle.
        val bottom = res.getDimensionPixelSize(R.dimen.keyguard_affordance_vertical_offset) +
            res.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height) / 2 -
            (NowBarView.CAPSULE * density / 2).toInt()
        constraintSet.apply {
            constrainWidth(R.id.now_bar, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.now_bar, ConstraintSet.WRAP_CONTENT)
            connect(R.id.now_bar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, side)
            connect(R.id.now_bar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, side)
            connect(R.id.now_bar, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM,
                bottom)
            // The indication text moves above the bar while it shows.
            connect(R.id.keyguard_indication_area, ConstraintSet.BOTTOM, R.id.now_bar,
                ConstraintSet.TOP, (8 * density).toInt())
            setGoneMargin(R.id.keyguard_indication_area, ConstraintSet.BOTTOM,
                res.getDimensionPixelSize(R.dimen.keyguard_indication_margin_bottom))
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(R.id.now_bar)
    }

    private companion object {
        /** Between the bar and each shortcut, in dp. */
        const val GAP = 12f
    }
}
