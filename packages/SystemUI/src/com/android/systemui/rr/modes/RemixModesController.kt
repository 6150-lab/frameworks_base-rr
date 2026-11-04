/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.modes

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import javax.inject.Inject
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Remix Modes: a Look per Android Mode. When the set of active Modes changes, the Look mapped to
 * the main one goes on; with none mapped and active, the everyday Look comes back. Remixer does
 * the applying, this only decides and asks.
 */
@SysUISingleton
class RemixModesController
@Inject
constructor(
    private val context: Context,
    private val zenModeInteractor: ZenModeInteractor,
    @Application private val scope: CoroutineScope,
) : CoreStartable {
    private val handler = Handler(Looper.getMainLooper())
    private var mapping = JSONObject()
    private var wallpaper = true
    private var applied: String? = null
    private var active: List<String> = emptyList()

    override fun start() {
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                read()
                evaluate()
            }
        }
        for (key in arrayOf(KEY_MAPPING, KEY_WALLPAPER, KEY_APPLIED)) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
            )
        }
        read()
        scope.launch {
            zenModeInteractor.modes
                .map { modes -> modes.filter { it.isActive }.map { it.id } }
                .distinctUntilChanged()
                .collect { ids ->
                    handler.post {
                        active = ids
                        evaluate()
                    }
                }
        }
    }

    private fun read() {
        val resolver = context.contentResolver
        mapping = try {
            JSONObject(Settings.System.getStringForUser(resolver, KEY_MAPPING,
                UserHandle.USER_CURRENT) ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        wallpaper = Settings.System.getIntForUser(resolver, KEY_WALLPAPER, 1,
            UserHandle.USER_CURRENT) == 1
        applied = Settings.System.getStringForUser(resolver, KEY_APPLIED, UserHandle.USER_CURRENT)
            ?.takeIf { it.isNotEmpty() }
    }

    /** The Look for the first active mapped Mode, else the everyday one once a Mode had its turn. */
    private fun evaluate() {
        val wanted = active.firstNotNullOfOrNull { id -> mapping.optString(id).takeIf { it.isNotEmpty() } }
            ?: mapping.optString(KEY_EVERYDAY).takeIf { it.isNotEmpty() && applied != null }
        if (wanted == null || wanted == applied) return
        Log.i(TAG, "Modes $active ask for look $wanted")
        context.sendBroadcastAsUser(
            Intent(ACTION_APPLY_LOOK)
                .setPackage(SETTINGS_PACKAGE)
                .putExtra(EXTRA_NAME, wanted)
                .putExtra(EXTRA_WALLPAPER, wallpaper),
            UserHandle.CURRENT,
        )
        ripple()
    }

    /** A glass ripple from the status bar, so the switch reads as one thing and not a flicker. */
    private fun ripple() {
        if (context.getSystemService(PowerManager::class.java)?.isInteractive != true) return
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val view = RippleView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "RemixModes"
            fitInsetsTypes = 0
        }
        try {
            wm.addView(view, lp)
        } catch (e: RuntimeException) {
            return
        }
        view.play { try { wm.removeView(view) } catch (e: RuntimeException) {} }
    }

    private class RippleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val accent = context.getColor(android.R.color.system_accent1_400)
        private var progress = 0f

        fun play(end: () -> Unit) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 750
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                doOnEnd(end)
                start()
            }
        }

        private fun ValueAnimator.doOnEnd(end: () -> Unit) {
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = 0f
            val reach = hypot(width.toFloat(), height.toFloat()) * progress
            val fade = 1f - progress
            // A soft disc with a brighter rim, like the lens the glass draws.
            paint.color = ColorUtils.setAlphaComponent(accent, (0x2E * fade).toInt())
            canvas.drawCircle(cx, cy, reach, paint)
            paint.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (0x66 * fade).toInt())
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 3f
            canvas.drawCircle(cx, cy, reach, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private companion object {
        const val TAG = "RemixModes"
        const val KEY_MAPPING = "rr_mode_looks"
        const val KEY_WALLPAPER = "rr_mode_looks_wallpaper"
        const val KEY_APPLIED = "rr_mode_look_applied"
        const val KEY_EVERYDAY = "everyday"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val ACTION_APPLY_LOOK = "com.resurrection.settings.APPLY_LOOK"
        const val EXTRA_NAME = "name"
        const val EXTRA_WALLPAPER = "wallpaper"
    }
}
