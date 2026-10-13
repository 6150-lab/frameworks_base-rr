/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarState

/**
 * The Now Bar's current activity as a chip next to the clock while unlocked. Calls, screen
 * recording and Live Updates already get the system's own chips, so this one covers music,
 * timers and voice recording.
 */
class NowBarChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), NowBarRepository.Listener, StatusBarStateController.StateListener {

    private lateinit var icon: ImageView
    private lateinit var label: TextView
    private lateinit var chronometer: Chronometer
    private var item: NowBarItem? = null
    private var state = StatusBarState.SHADE
    private var items: List<NowBarItem> = emptyList()

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon = requireViewById(R.id.now_bar_chip_icon)
        label = requireViewById(R.id.now_bar_chip_text)
        chronometer = requireViewById(R.id.now_bar_chip_chronometer)
        icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(context.getColor(android.R.color.system_accent1_600))
        }
        setOnClickListener {
            item?.contentIntent?.let {
                Dependency.get(ActivityStarter::class.java).postStartActivityDismissingKeyguard(it)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val controller = Dependency.get(StatusBarStateController::class.java)
        state = controller.state
        controller.addCallback(this)
        NowBarRepository.instance?.addListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Dependency.get(StatusBarStateController::class.java).removeCallback(this)
        NowBarRepository.instance?.removeListener(this)
        chronometer.stop()
    }

    override fun onStateChanged(newState: Int) {
        state = newState
        update()
    }

    override fun onNowBarChanged(items: List<NowBarItem>) {
        this.items = items
        update()
    }

    private fun update() {
        val repository = NowBarRepository.instance
        val top = items.firstOrNull()
        item = top?.takeIf {
            repository?.settings?.chip == true && state == StatusBarState.SHADE &&
                (it.type == NowBarType.MUSIC || it.type == NowBarType.TIMER ||
                    (it.type == NowBarType.RECORDING && it.packageName != context.packageName))
        }
        val shown = item
        if (shown == null) {
            visibility = GONE
            chronometer.stop()
            return
        }
        visibility = VISIBLE
        icon.setImageDrawable(
            if (shown.type == NowBarType.MUSIC) {
                context.getDrawable(if (shown.playing) R.drawable.rr_nowbar_pause else R.drawable.rr_nowbar_play)
            } else {
                shown.icon?.mutate()
            }
        )
        if (shown.chronometerBase != 0L) {
            chronometer.base =
                SystemClock.elapsedRealtime() + (shown.chronometerBase - System.currentTimeMillis())
            chronometer.isCountDown = shown.countDown
            chronometer.start()
            chronometer.visibility = VISIBLE
            label.visibility = GONE
        } else {
            chronometer.stop()
            chronometer.visibility = GONE
            label.visibility = VISIBLE
            label.text = shown.title
        }
        contentDescription = shown.title
    }
}
