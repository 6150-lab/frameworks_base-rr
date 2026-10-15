/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.PendingIntent
import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
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
import androidx.core.graphics.ColorUtils
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import kotlin.math.abs

/**
 * The Now Bar on the lock screen, between the shortcuts like One UI's: a capsule with the current
 * activity, and the next one peeking out behind it. Swipe between activities, tap to open the
 * card, long-press to hide one. On the always-on display it is a dim outline.
 */
class NowBarView(
    context: Context,
    private val repository: NowBarRepository,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    private val broadcastDispatcher: BroadcastDispatcher,
) : FrameLayout(context), NowBarRepository.Listener, StatusBarStateController.StateListener {

    private val density = resources.displayMetrics.density
    private val accent = context.getColor(android.R.color.system_accent1_400)
    private val semibold = Typeface.create(Typeface.DEFAULT, 600, false)
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)
    private val peekBg = GradientDrawable().apply { cornerRadius = dp(CAPSULE / 2) }
    private val peek = View(context)
    private val capsuleBg = GradientDrawable().apply { cornerRadius = dp(CAPSULE / 2) }
    private val capsule = FrameLayout(context)
    private val row = FrameLayout(context)
    private val icon = ImageView(context)

    // Music
    private val songTitle = TextView(context)
    private val controls = LinearLayout(context)
    private val controlViews = List(3) { ImageView(context) }
    private val wave = NowBarWaveView(context)

    // Everything else
    private val texts = LinearLayout(context)
    private val label = TextView(context)
    private val title = TextView(context)
    private val chronometer = Chronometer(context)
    private val subtitle = TextView(context)
    private val actionRow = LinearLayout(context)
    private val actionViews = List(2) { ImageView(context) }
    private val progress = ProgressLine(context)

    // Games
    private val scoreRow = LinearLayout(context)
    private val logo1 = ImageView(context)
    private val logo2 = ImageView(context)
    private val team1 = TextView(context)
    private val team2 = TextView(context)
    private val scoreText = TextView(context)
    private val statusText = TextView(context)

    private var items: List<NowBarItem> = emptyList()
    private var currentKey: String? = null
    private var dozing = false
    private var card: NowBarCard? = null
    private var burnIn = 0
    private var artColorsFor: Bitmap? = null
    private var artColors: IntArray? = null

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
        clipChildren = false
        clipToPadding = false
        visibility = GONE
        peek.background = peekBg
        addView(peek, LayoutParams(LayoutParams.MATCH_PARENT, dp(CAPSULE).toInt(), Gravity.TOP)
            .apply {
                marginStart = dp(14f).toInt()
                marginEnd = dp(14f).toInt()
            })
        capsule.background = capsuleBg
        capsule.elevation = dp(6f)
        capsule.clipToOutline = true
        addView(capsule, LayoutParams(LayoutParams.MATCH_PARENT, dp(CAPSULE).toInt(),
            Gravity.BOTTOM).apply { topMargin = dp(PEEK).toInt() })
        capsule.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setOval(0, 0, view.width, view.height)
        }
        icon.clipToOutline = true
        row.addView(icon, LayoutParams(dp(ART).toInt(), dp(ART).toInt(),
            Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(INSET).toInt() })

        buildMusic()
        buildTexts()
        buildScore()
    }

    private fun buildMusic() {
        songTitle.setTextColor(Color.WHITE)
        songTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        songTitle.typeface = medium
        songTitle.isSingleLine = true
        songTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
        songTitle.marqueeRepeatLimit = -1
        songTitle.isHorizontalFadingEdgeEnabled = true
        songTitle.setFadingEdgeLength(dp(10f).toInt())
        songTitle.isSelected = true
        row.addView(songTitle, LayoutParams(LayoutParams.MATCH_PARENT, dp(18f).toInt(),
            Gravity.TOP).apply {
                marginStart = dp(CONTENT_START).toInt()
                marginEnd = dp(16f).toInt()
                topMargin = dp(6f).toInt()
            })

        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it,
                true)
        }.resourceId
        for (view in controlViews) {
            val pad = dp(8f).toInt()
            view.setPadding(pad, pad, pad, pad)
            view.imageTintList = ColorStateList.valueOf(Color.WHITE)
            if (ripple != 0) view.setBackgroundResource(ripple)
            controls.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(view, LinearLayout.LayoutParams(dp(34f).toInt(), dp(34f).toInt()))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        row.addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, dp(34f).toInt(),
            Gravity.TOP).apply {
                marginStart = dp(CONTENT_START).toInt()
                marginEnd = dp(16f).toInt()
                topMargin = dp(22f).toInt()
            })

        row.addView(wave, LayoutParams(LayoutParams.MATCH_PARENT, dp(11f).toInt(),
            Gravity.BOTTOM).apply {
                marginStart = dp(44f).toInt()
                marginEnd = dp(26f).toInt()
            })
    }

    private fun buildTexts() {
        texts.orientation = LinearLayout.VERTICAL
        label.setTextColor(0xB8FFFFFF.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        label.typeface = medium
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        title.typeface = semibold
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        chronometer.setTextColor(Color.WHITE)
        chronometer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        chronometer.typeface = semibold
        chronometer.fontFeatureSettings = "tnum"
        subtitle.setTextColor(0xB8FFFFFF.toInt())
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        subtitle.isSingleLine = true
        subtitle.ellipsize = TextUtils.TruncateAt.END
        texts.addView(label)
        texts.addView(title)
        texts.addView(chronometer)
        texts.addView(subtitle)
        row.addView(texts, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL).apply { marginStart = dp(CONTENT_START - 1f).toInt() })

        actionRow.orientation = LinearLayout.HORIZONTAL
        for (view in actionViews) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1FFFFFFF)
            }
            val pad = dp(10f).toInt()
            view.setPadding(pad, pad, pad, pad)
            view.imageTintList = ColorStateList.valueOf(Color.WHITE)
            actionRow.addView(view, LinearLayout.LayoutParams(dp(ART).toInt(), dp(ART).toInt())
                .apply { marginStart = dp(6f).toInt() })
        }
        row.addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, dp(ART).toInt(),
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(INSET).toInt() })

        row.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(3f).toInt(),
            Gravity.BOTTOM).apply {
                marginStart = dp(CONTENT_START - 1f).toInt()
                marginEnd = dp(22f).toInt()
                bottomMargin = dp(7f).toInt()
            })
    }

    private fun buildScore() {
        scoreRow.orientation = LinearLayout.HORIZONTAL
        scoreRow.gravity = Gravity.CENTER_VERTICAL
        val side = dp(14f).toInt()
        scoreRow.setPaddingRelative(side, 0, side, 0)
        for (team in listOf(team1, team2)) {
            team.setTextColor(Color.WHITE)
            team.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            team.typeface = semibold
            team.isSingleLine = true
            team.ellipsize = TextUtils.TruncateAt.END
        }
        team1.gravity = Gravity.START
        team2.gravity = Gravity.END
        scoreText.setTextColor(Color.WHITE)
        scoreText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        scoreText.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        scoreText.fontFeatureSettings = "tnum"
        scoreText.gravity = Gravity.CENTER
        statusText.setTextColor(0xB8FFFFFF.toInt())
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        statusText.isSingleLine = true
        statusText.ellipsize = TextUtils.TruncateAt.END
        statusText.gravity = Gravity.CENTER
        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(scoreText)
            addView(statusText)
        }
        val logo = dp(28f).toInt()
        scoreRow.addView(logo1, LinearLayout.LayoutParams(logo, logo))
        scoreRow.addView(team1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,
            1f).apply { marginStart = dp(8f).toInt() })
        scoreRow.addView(middle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6f).toInt()
                marginEnd = dp(6f).toInt()
            })
        scoreRow.addView(team2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,
            1f).apply { marginEnd = dp(8f).toInt() })
        scoreRow.addView(logo2, LinearLayout.LayoutParams(logo, logo))
        row.addView(scoreRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        val music = item.type == NowBarType.MUSIC && !dozing
        val game = item.score != null
        val colors = if (music) item.art?.let(::colorsOf) else null
        val fill = when {
            dozing -> Color.BLACK
            colors != null -> colors[0]
            else -> 0xEB0D0809.toInt()
        }
        capsuleBg.setColor(fill)
        capsuleBg.setStroke(dp(1f).toInt(), if (dozing) 0x29FFFFFF else 0x14FFFFFF)
        capsule.elevation = if (dozing) 0f else dp(6f)
        alpha = if (dozing) 0.85f else 1f
        peek.visibility = if (items.size > 1 && !dozing) VISIBLE else INVISIBLE
        peekBg.setColor(ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(fill, Color.WHITE, 0.08f), 0xC0))

        bindIcon(item, game)
        songTitle.visibility = if (music) VISIBLE else GONE
        controls.visibility = if (music) VISIBLE else GONE
        texts.visibility = if (!music && !game) VISIBLE else GONE
        actionRow.visibility = if (!music && !game) VISIBLE else GONE
        progress.visibility = GONE
        scoreRow.visibility = if (game) VISIBLE else GONE
        when {
            music -> bindMusic(item, colors)
            game -> bindScore(item.score!!)
            else -> bindTexts(item)
        }
        val playing = music && item.playing && settings.wave
        wave.visibility = if (playing) VISIBLE else GONE
        wave.running = playing
        capsule.contentDescription = listOfNotNull(item.title, item.text).joinToString(", ")
    }

    private fun bindIcon(item: NowBarItem, game: Boolean) {
        if (game) {
            icon.visibility = GONE
            return
        }
        icon.visibility = VISIBLE
        icon.alpha = if (dozing) 0.6f else 1f
        val art = item.art
        if (art != null) {
            icon.setImageBitmap(art)
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.setPadding(0, 0, 0, 0)
            icon.imageTintList = null
            icon.background = null
            return
        }
        icon.setImageDrawable(item.icon?.mutate())
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val appIcon = item.type == NowBarType.MUSIC
        val pad = if (appIcon) 0 else dp(9f).toInt()
        icon.setPadding(pad, pad, pad, pad)
        icon.imageTintList = if (appIcon) null else ColorStateList.valueOf(Color.WHITE)
        val tint = if (item.color != 0 && item.color != Color.BLACK) item.color else accent
        icon.background = if (appIcon) null else GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((tint and 0x00FFFFFF) or (if (dozing) 0x14000000 else 0x66000000))
        }
    }

    private fun bindMusic(item: NowBarItem, colors: IntArray?) {
        chronometer.stop()
        val line = listOfNotNull(item.title.takeIf { it.isNotEmpty() }, item.text)
            .joinToString(" · ")
        if (songTitle.text.toString() != line) songTitle.text = line
        controlViews.forEachIndexed { i, view ->
            val action = item.actions.getOrNull(i)
            if (action == null) {
                view.visibility = INVISIBLE
                return@forEachIndexed
            }
            view.visibility = VISIBLE
            if (action.iconRes != 0) view.setImageResource(action.iconRes)
            else view.setImageDrawable(action.icon?.loadDrawable(context))
            view.contentDescription = action.title
            view.setOnClickListener { run(action) }
        }
        wave.colors = colors?.copyOfRange(1, 4) ?: intArrayOf(
            ColorUtils.setAlphaComponent(accent, 0x8C), ColorUtils.setAlphaComponent(accent, 0x66),
            ColorUtils.setAlphaComponent(Color.WHITE, 0x40))
    }

    private fun bindTexts(item: NowBarItem) {
        label.visibility = GONE
        if (item.chronometerBase != 0L && !dozing) {
            chronometer.base = SystemClock.elapsedRealtime() +
                (item.chronometerBase - System.currentTimeMillis())
            chronometer.isCountDown = item.countDown
            chronometer.start()
            chronometer.visibility = VISIBLE
            title.visibility = GONE
            label.text = item.title
            label.visibility = if (item.title.isNotEmpty()) VISIBLE else GONE
            subtitle.text = item.text
        } else {
            chronometer.stop()
            chronometer.visibility = GONE
            title.visibility = VISIBLE
            when {
                item.chronometerBase != 0L -> {
                    // Minutes only on the always-on display; the time tick refreshes it.
                    val minutes =
                        (abs(item.chronometerBase - System.currentTimeMillis()) / 60000L).toInt()
                    title.text = context.getString(
                        if (item.countDown) R.string.now_bar_minutes_left
                        else R.string.now_bar_minutes,
                        minutes,
                    )
                    subtitle.text = item.title
                }
                item.type == NowBarType.MUSIC -> {
                    title.text = listOfNotNull(item.title.takeIf { it.isNotEmpty() }, item.text)
                        .joinToString(" · ")
                    subtitle.text = null
                }
                else -> {
                    title.text = item.title
                    subtitle.text = item.text
                }
            }
        }
        subtitle.visibility = if (subtitle.text.isNullOrEmpty() || dozing) GONE else VISIBLE

        val shown = if (dozing) emptyList()
            else item.actions.filter { it.icon != null || it.iconRes != 0 }.take(2)
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
        (texts.layoutParams as LayoutParams).marginEnd =
            dp(INSET + shown.size * (ART + 6f) + 6f).toInt()
        texts.requestLayout()
        progress.visibility = if (item.progress >= 0f && !dozing) VISIBLE else GONE
        progress.set(item.progress, accent)
    }

    private fun bindScore(score: NowBarScore) {
        chronometer.stop()
        team1.text = score.team1
        team2.text = score.team2
        scoreText.text = if (score.score1.isEmpty()) "vs" else "${score.score1} – ${score.score2}"
        statusText.text = score.status
        statusText.visibility = if (score.status.isNullOrEmpty()) GONE else VISIBLE
        for ((view, logo) in listOf(logo1 to score.logo1, logo2 to score.logo2)) {
            view.setImageDrawable(logo)
            view.visibility = if (logo != null) VISIBLE else GONE
            view.alpha = if (dozing) 0.6f else 1f
        }
    }

    /** The capsule's tint and three wave colors from the album art, cached per bitmap. */
    private fun colorsOf(art: Bitmap): IntArray? {
        if (art === artColorsFor) return artColors
        artColorsFor = art
        artColors = try {
            val wallpaperColors = WallpaperColors.fromBitmap(art)
            val primary = wallpaperColors.primaryColor.toArgb()
            val secondary = wallpaperColors.secondaryColor?.toArgb() ?: primary
            val tertiary = wallpaperColors.tertiaryColor?.toArgb() ?: secondary
            intArrayOf(
                tone(primary, 0.17f, 0.5f, 0xF0),
                tone(primary, 0.66f, 0.85f, 0x8C),
                tone(secondary, 0.72f, 0.85f, 0x99),
                tone(tertiary, 0.6f, 0.85f, 0x8C),
            )
        } catch (e: RuntimeException) {
            // Hardware or odd bitmaps: the default look.
            null
        }
        return artColors
    }

    private fun tone(color: Int, lightness: Float, maxSaturation: Float, alpha: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1].coerceAtMost(maxSaturation)
        hsl[2] = lightness
        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), alpha)
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

    /** A thin progress bar: a dim track and a filled part. */
    private class ProgressLine(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fraction = 0f
        private var color = Color.WHITE

        fun set(fraction: Float, color: Int) {
            this.fraction = fraction.coerceIn(0f, 1f)
            this.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = 0x29FFFFFF
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            paint.color = color
            canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), r, r, paint)
        }
    }

    companion object {
        /** The capsule's height and how far the next activity peeks out above it, in dp. */
        const val CAPSULE = 64f
        const val PEEK = 7f
        private const val ART = 38f
        private const val INSET = 12f
        private const val CONTENT_START = INSET + ART + 12f
        private const val REMIXER_ACTION = "com.resurrection.settings.NOW_BAR"
        private val BURN_IN_STEPS_X = floatArrayOf(0f, 3f, 5f, 3f, 0f, -3f, -5f, -3f)
        private val BURN_IN_STEPS_Y = floatArrayOf(0f, -2f, -4f, -6f, -4f, -2f, 0f, -2f)
    }
}
