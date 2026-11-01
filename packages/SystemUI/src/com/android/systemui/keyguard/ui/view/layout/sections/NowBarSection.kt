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
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.rr.nowbar.NowBarLook
import com.android.systemui.rr.nowbar.NowBarRepository
import com.android.systemui.rr.nowbar.NowBarView
import dagger.Lazy
import javax.inject.Inject
import kotlin.math.max

/** The Now Bar, between the lock screen shortcuts like One UI's. */
class NowBarSection
@Inject
constructor(
    private val context: Context,
    private val repository: NowBarRepository,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val keyguardBlueprintInteractor: Lazy<KeyguardBlueprintInteractor>,
) : KeyguardSection() {

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<View>(R.id.now_bar) != null) return
        val view = NowBarView(
            context, repository, statusBarStateController, activityStarter, broadcastDispatcher
        ) {
            keyguardBlueprintInteractor
                .get()
                .refreshBlueprint(IntraBlueprintTransition.Type.DefaultTransition)
        }
        view.id = R.id.now_bar
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val res = context.resources
        val density = res.displayMetrics.density
        val look = repository.settings.look
        val edge = res.getDimensionPixelSize(R.dimen.keyguard_affordance_horizontal_offset)
        // The shortcuts' slots stay free even when no shortcut is set, so the bar never jumps.
        val between = edge + res.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width) +
            (GAP * density).toInt()
        val side = when (look.width) {
            NowBarLook.WIDTH_FULL -> edge
            NowBarLook.WIDTH_NARROW -> between + (NARROW * density).toInt()
            else -> between
        }
        val shortcutBottom =
            res.getDimensionPixelSize(R.dimen.keyguard_affordance_vertical_offset)
        // The capsule's middle on the shortcuts' middle.
        val bottom = max(
            (MIN_BOTTOM * density).toInt(),
            shortcutBottom + res.getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height) / 2 -
                (look.height * density / 2).toInt(),
        ) + (look.raise * density).toInt()
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
            // A full width bar takes the shortcuts' row, so they sit above it while it shows.
            if (look.width == NowBarLook.WIDTH_FULL) {
                for (button in intArrayOf(R.id.start_button, R.id.end_button)) {
                    connect(button, ConstraintSet.BOTTOM, R.id.now_bar, ConstraintSet.TOP,
                        (SHORTCUT_GAP * density).toInt())
                    setGoneMargin(button, ConstraintSet.BOTTOM, shortcutBottom)
                }
            }
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        constraintLayout.removeView(R.id.now_bar)
    }

    private companion object {
        /** Between the bar and each shortcut, in dp. */
        const val GAP = 12f
        /** How much further in a narrow bar sits on each side, in dp. */
        const val NARROW = 26f
        /** The bar stays clear of the gesture pill, in dp. */
        const val MIN_BOTTOM = 14f
        /** Between a full width bar and the shortcuts above it, in dp. */
        const val SHORTCUT_GAP = 10f
    }
}
