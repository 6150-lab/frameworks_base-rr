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

/** The Now Bar, just above the device entry icon. */
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
        val density = context.resources.displayMetrics.density
        val side = (24 * density).toInt()
        constraintSet.apply {
            constrainWidth(R.id.now_bar, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(R.id.now_bar, ConstraintSet.WRAP_CONTENT)
            connect(R.id.now_bar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, side)
            connect(R.id.now_bar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, side)
            connect(R.id.now_bar, ConstraintSet.BOTTOM, R.id.device_entry_icon_view, ConstraintSet.TOP,
                (14 * density).toInt())
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(R.id.now_bar)
    }
}
