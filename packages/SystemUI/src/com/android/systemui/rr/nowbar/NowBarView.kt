/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import kotlin.math.abs

/**
 * The Now Bar on the lock screen: a capsule with the current activity. Swipe between activities,
 * tap to open the card, long-press to hide one. On the always-on display it is a dim outline.
 */
class NowBarView(
    context: Context,
    private val repository: NowBarRepository,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    private val broadcastDispatcher: BroadcastDispatcher,
) : LinearLayout(context), NowBarRepository.Listener, StatusBarStateController.StateListener {

    private val density = resources.displayMetrics.density
    private val accent = context.getColor(android.R.color.system_accent1_400)
    private val capsuleBg = GradientDrawable().apply { cornerRadius = dp(30f) }
    private val capsule = FrameLayout(context)
    private val row = LinearLayout(context)
    private val icon = ImageView(context)
    private val title = TextView(context)
    private val chronometer = Chronometer(context)
    private val subtitle = TextView(context)
    private val actionViews = List(2) { ImageView(context) }
    private val wave = NowBarWaveView(context)
    private val dots = NowBarDotsView(context)

    private var items: List<NowBarItem> = emptyList()
    private var currentKey: String? = null
    private var dozing = false
    private var card: NowBarCard? = null
    private var burnIn = 0

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showMenu()
    }

    private val timeTick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!dozing) return
            // A few pixels each minute against burn-in.
            burnIn = (burnIn + 1) % 8
            translationX = dp(BURN_IN_STEPS_X[burnIn])
            translationY = dp(BURN_IN_STEPS_Y[burnIn])
            bind()
        }
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        visibility = GONE
        capsule.background = capsuleBg
        capsule.elevation = dp(6f)
        addView(capsule, LayoutParams(LayoutParams.MATCH_PARENT, dp(60f).toInt()))

        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPaddingRelative(dp(9f).toInt(), 0, dp(9f).toInt(), 0)
        capsule.addView(row, FrameLayout.LayoutParams(-1, -1))

        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setOval(0, 0, view.width, view.height)
        }
        icon.clipToOutline = true
        row.addView(icon, LayoutParams(dp(42f).toInt(), dp(42f).toInt()))

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        title.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        chronometer.setTextColor(Color.WHITE)
        chronometer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        chronometer.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        chronometer.fontFeatureSettings = "tnum"
        subtitle.setTextColor(0xA6FFFFFF.toInt())
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        subtitle.isSingleLine = true
        subtitle.ellipsize = TextUtils.TruncateAt.END
        texts.addView(title)
        texts.addView(chronometer)
        texts.addView(subtitle)
        row.addView(texts, LayoutParams(0, -2, 1f).apply { marginStart = dp(11f).toInt() })

        for (view in actionViews) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1FFFFFFF)
            }
            val pad = dp(9f).toInt()
            view.setPadding(pad, pad, pad, pad)
            view.imageTintList = ColorStateList.valueOf(Color.WHITE)
            row.addView(view, LayoutParams(dp(38f).toInt(), dp(38f).toInt()).apply {
                marginStart = dp(6f).toInt()
            })
        }

        wave.color = accent
        capsule.addView(wave, FrameLayout.LayoutParams(-1, dp(7f).toInt(), Gravity.BOTTOM).apply {
            marginStart = dp(22f).toInt()
            marginEnd = dp(22f).toInt()
            bottomMargin = dp(3f).toInt()
        })
        addView(dots, LayoutParams(-1, dp(8f).toInt()).apply { topMargin = dp(6f).toInt() })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        dozing = statusBarStateController.isDozing
        statusBarStateController.addCallback(this)
        broadcastDispatcher.registerReceiver(timeTick, IntentFilter(Intent.ACTION_TIME_TICK))
        repository.addListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repository.removeListener(this)
        statusBarStateController.removeCallback(this)
        broadcastDispatcher.unregisterReceiver(timeTick)
        chronometer.stop()
        dismissCard()
    }

    override fun onNowBarChanged(items: List<NowBarItem>) {
        this.items = items
        if (items.none { it.key == currentKey }) currentKey = items.firstOrNull()?.key
        bind()
        card?.update(items.firstOrNull { it.key == card?.key })
    }

    override fun onDozingChanged(isDozing: Boolean) {
        dozing = isDozing
        if (dozing) dismissCard()
        if (!dozing) {
            translationX = 0f
            translationY = 0f
        }
        bind()
    }

    private val index get() = items.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)

    private fun bind() {
        val item = items.getOrNull(index)
        val settings = repository.settings
        if (item == null || (dozing && !settings.aod)) {
            visibility = GONE
            chronometer.stop()
            return
        }
        visibility = VISIBLE
        capsuleBg.setColor(if (dozing) Color.BLACK else 0xE60D0809.toInt())
        capsuleBg.setStroke(dp(1f).toInt(), if (dozing) 0x22FFFFFF else 0x1AFFFFFF)
        capsule.elevation = if (dozing) 0f else dp(6f)
        alpha = if (dozing) 0.8f else 1f

        val art = item.art
        if (art != null) {
            icon.setImageBitmap(art)
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.setPadding(0, 0, 0, 0)
            icon.imageTintList = null
            icon.background = null
        } else {
            icon.setImageDrawable(item.icon?.mutate())
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            val appIcon = item.type == NowBarType.MUSIC
            val pad = if (appIcon) 0 else dp(10f).toInt()
            icon.setPadding(pad, pad, pad, pad)
            icon.imageTintList = if (appIcon) null else ColorStateList.valueOf(Color.WHITE)
            val tint = if (item.color != 0 && item.color != Color.BLACK) item.color else accent
            icon.background = if (appIcon) null else GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((tint and 0x00FFFFFF) or (if (dozing) 0x14000000 else 0x4D000000))
            }
        }

        if (item.chronometerBase != 0L && !dozing) {
            chronometer.base = SystemClock.elapsedRealtime() + (item.chronometerBase - System.currentTimeMillis())
            chronometer.isCountDown = item.countDown
            chronometer.start()
            chronometer.visibility = VISIBLE
            title.visibility = GONE
            subtitle.text = listOfNotNull(item.title, item.text).joinToString(" · ")
        } else {
            chronometer.stop()
            chronometer.visibility = GONE
            title.visibility = VISIBLE
            if (item.chronometerBase != 0L) {
                // Minutes only on the always-on display; the time tick refreshes it.
                val minutes = (abs(item.chronometerBase - System.currentTimeMillis()) / 60000L).toInt()
                title.text = context.getString(
                    if (item.countDown) R.string.now_bar_minutes_left else R.string.now_bar_minutes,
                    minutes,
                )
                subtitle.text = item.title
            } else {
                title.text = item.title
                subtitle.text = item.text
            }
        }
        subtitle.visibility = if (subtitle.text.isNullOrEmpty()) GONE else VISIBLE

        val shown = when {
            dozing -> emptyList()
            item.type == NowBarType.MUSIC -> listOfNotNull(item.actions.getOrNull(1))
            else -> item.actions.filter { it.icon != null || it.iconRes != 0 }.take(2)
        }
        actionViews.forEachIndexed { i, view ->
            val action = shown.getOrNull(i)
            if (action == null) {
                view.visibility = GONE
                return@forEachIndexed
            }
            view.visibility = VISIBLE
            if (action.iconRes != 0) view.setImageResource(action.iconRes)
            else view.setImageDrawable(action.icon?.loadDrawable(context))
            view.contentDescription = action.title
            view.setOnClickListener { run(action) }
        }
        val playingMusic = item.type == NowBarType.MUSIC && item.playing
        actionViews[0].backgroundTintList =
            if (playingMusic && !dozing) ColorStateList.valueOf(accent) else null
        wave.visibility = if (playingMusic && settings.wave && !dozing) VISIBLE else GONE
        wave.running = wave.visibility == VISIBLE
        dots.set(if (dozing) 0 else items.size, index)
        capsule.contentDescription = listOfNotNull(item.title, item.text).joinToString(", ")
    }

    private fun run(action: NowBarAction) {
        action.run?.invoke()
        action.intent?.let { send(it) }
    }

    private fun send(intent: PendingIntent) {
        if (intent.isActivity) {
            activityStarter.startPendingIntentDismissingKeyguard(intent)
        } else {
            try {
                intent.send()
            } catch (e: PendingIntent.CanceledException) {
                // Gone with its notification.
            }
        }
    }

    private fun openCard() {
        val item = items.getOrNull(index) ?: return
        dismissCard()
        card = NowBarCard(context, item, repository.settings.wave, activityStarter, ::send) {
            card = null
        }.also { it.show() }
    }

    private fun dismissCard() {
        card?.dismiss()
        card = null
    }

    private fun showMenu() {
        val item = items.getOrNull(index) ?: return
        PopupMenu(context, capsule).apply {
            menu.add(0, 1, 0, R.string.now_bar_hide)
            menu.add(0, 2, 1, R.string.now_bar_settings)
            setOnMenuItemClickListener {
                if (it.itemId == 1) {
                    repository.hide(item.key)
                } else {
                    activityStarter.postStartActivityDismissingKeyguard(
                        Intent(REMIXER_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0
                    )
                }
                true
            }
            show()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dozing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(ev)
            MotionEvent.ACTION_MOVE -> if (horizontalDrag(ev)) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dozing) return false
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(ev)
            MotionEvent.ACTION_MOVE -> {
                horizontalDrag(ev)
                if (abs(ev.y - downY) > slop) removeCallbacks(longPress)
                if (dragging) {
                    val dx = ev.x - downX
                    val canMove = if (dx < 0) index < items.size - 1 else index > 0
                    row.translationX = dx * (if (canMove) 1f else 0.3f)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) settle() else if (!longPressed) openCard()
                recycleVelocity()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                springBack()
                recycleVelocity()
            }
        }
        return true
    }

    private fun startGesture(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        dragging = false
        longPressed = false
        recycleVelocity()
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
        removeCallbacks(longPress)
        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun horizontalDrag(ev: MotionEvent): Boolean {
        val dx = abs(ev.x - downX)
        if (!dragging && dx > slop && dx > abs(ev.y - downY) && items.size > 1) {
            dragging = true
            removeCallbacks(longPress)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return dragging
    }

    private fun settle() {
        val vx = velocity?.let {
            it.computeCurrentVelocity(1000)
            it.xVelocity
        } ?: 0f
        val dx = row.translationX
        val target = when {
            (dx < -width * 0.25f || vx < -minFling) && index < items.size - 1 -> index + 1
            (dx > width * 0.25f || vx > minFling) && index > 0 -> index - 1
            else -> index
        }
        if (target == index) {
            springBack()
            return
        }
        val out = if (target > index) -width.toFloat() else width.toFloat()
        row.animate().translationX(out).alpha(0f).setDuration(140L).withEndAction {
            currentKey = items[target].key
            bind()
            row.translationX = -out
            row.animate().translationX(0f).alpha(1f).setDuration(180L).start()
        }.start()
    }

    private fun springBack() {
        dragging = false
        row.animate().translationX(0f).setDuration(180L).start()
    }

    private fun recycleVelocity() {
        velocity?.recycle()
        velocity = null
    }

    private fun dp(value: Float) = value * density

    companion object {
        private const val REMIXER_ACTION = "com.resurrection.settings.REMIXER"
        private val BURN_IN_STEPS_X = floatArrayOf(0f, 3f, 5f, 3f, 0f, -3f, -5f, -3f)
        private val BURN_IN_STEPS_Y = floatArrayOf(0f, -2f, -4f, -6f, -4f, -2f, 0f, -2f)
    }
}
