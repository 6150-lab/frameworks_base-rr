/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.edgelight

import android.app.Notification
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.widget.FrameLayout

import com.android.settingslib.Utils

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.internal.rr.RemixHaptics
import com.android.internal.util.ContrastColorUtil

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@SysUISingleton
class EdgeLightViewController 
@Inject 
constructor(
    private val context: Context,
    private val listener: NotificationListener,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
) : NotificationListener.NotificationHandler {

    private val edgeLightView = EdgeLightView(context)
    private val settingsRepo = EdgeLightSettingsRepository(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val wallpaperManager = context.getSystemService(WallpaperManager::class.java)!!

    private var currentSettings = settingsRepo.currentSettings()

    private var job: Job? = null

    private var isDozing = false

    private var lastNotificationKey: String? = null
    private var lastNotificationText: CharSequence? = null
    private var lastNotificationTime: Long = 0L
    private val deduplicationWindowMs = 3000L
    private var lastNotifColor: Int = Utils.getColorAccentDefaultColor(context)
    /** The color Haptic Remix gives the notification being shown (its sender's or app's), or 0. */
    private var remixColor = 0

    init {
        INSTANCE = this

        listener.addNotificationHandler(this)
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onDozingChanged(isDozing: Boolean) =
                this@EdgeLightViewController.onDozingChanged(isDozing)

            override fun onPulsingChanged(pulsing: Boolean) =
                this@EdgeLightViewController.setPulsing(pulsing)
        })
        keyguardStateController.addCallback(object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() =
                this@EdgeLightViewController.onKeyguardShowingChanged(
                    keyguardStateController.isShowing)

            override fun onKeyguardFadingAwayChanged() =
                this@EdgeLightViewController.onKeyguardFadingAwayChanged(
                    keyguardStateController.isKeyguardFadingAway)

            override fun onKeyguardGoingAwayChanged() =
                this@EdgeLightViewController.onKeyguardGoingAwayChanged(
                    keyguardStateController.isKeyguardGoingAway)
        })
        isDozing = statusBarStateController.isDozing
        updateView()
    }

    fun getEdgeLightView(): FrameLayout = edgeLightView

    private fun getColor(): Int =
        if (remixColor != 0) remixColor else when (currentSettings.colorMode) {
            COLOR_MODE_ACCENT -> Utils.getColorAccentDefaultColor(context)
            COLOR_MODE_CUSTOM -> currentSettings.customColor
            COLOR_MODE_WALLPAPER -> wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor?.toArgb() ?: Utils.getColorAccentDefaultColor(context)
            COLOR_MODE_NOTIFICATION -> lastNotifColor
            COLOR_MODE_RAINBOW -> EdgeLightView.COLOR_RAINBOW
            else -> Utils.getColorAccentDefaultColor(context)
        }

    private fun updateView() {
        job?.cancel()
        job = scope.launch {
            settingsRepo.settingsFlow.collect { settings ->
                currentSettings = settings
                if (!settings.isEnabled) {
                    stop()
                } else {
                    edgeLightView.paintColor = getColor()
                    edgeLightView.userPulseCount = settings.pulseCount
                    edgeLightView.userStrokeWidth = settings.strokeWidth
                    edgeLightView.edgeStyle = settings.edgeStyle
                    edgeLightView.animationEffect = settings.animationEffect
                    edgeLightView.userSpread = settings.spread
                    edgeLightView.userIntensity = settings.intensity
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (!currentSettings.isEnabled || !isDozing) return
        // A contact or app with an edge color of its own lights it in every color mode.
        remixColor = RemixHaptics.edgeColor(context, sbn.packageName, sbn.notification)
        if (remixColor != 0 || currentSettings.colorMode != COLOR_MODE_NOTIFICATION) {
            edgeLightView.paintColor = getColor()
            return
        }

        val currentKey = sbn.key
        val currentText = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val now = System.currentTimeMillis()

        if (currentKey == lastNotificationKey &&
            currentText == lastNotificationText &&
            (now - lastNotificationTime <= deduplicationWindowMs)) {
            return
        }

        lastNotificationKey = currentKey
        lastNotificationText = currentText
        lastNotificationTime = now

        val notifColor = sbn.notification?.color ?: Color.TRANSPARENT
        val accent = Utils.getColorAccentDefaultColor(context)

        lastNotifColor = when {
            notifColor == Color.TRANSPARENT || notifColor == 0 -> accent
            ContrastColorUtil.isColorDark(notifColor) -> accent
            else -> notifColor
        }
        edgeLightView.paintColor = lastNotifColor
    }

    private fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        if (!dozing) stop()
    }

    private fun onKeyguardShowingChanged(showing: Boolean) {
        if (showing) updateView() else stop()
    }

    private fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        if (fadingAway) stop()
    }

    private fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (goingAway) stop()
    }

    private fun setPulsing(pulsing: Boolean) {
        if (!pulsing) {
            remixColor = 0
            stop()
            return
        }
        if (!currentSettings.isEnabled || !isDozing) return
        if (Settings.System.getIntForUser(context.contentResolver, KEY_NOTIFICATIONS, 1,
                UserHandle.USER_CURRENT) == 0) return
        edgeLightView.apply {
            visible = true
            pulseRunning = true
        }
    }

    private fun stop() {
        edgeLightView.pulseRunning = false
        edgeLightView.visible = false
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {}
    override fun onNotificationsInitialized() {}
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    companion object {
        private const val COLOR_MODE_ACCENT = "accent"
        private const val COLOR_MODE_NOTIFICATION = "notification"
        private const val COLOR_MODE_WALLPAPER = "wallpaper"
        private const val COLOR_MODE_CUSTOM = "custom"
        private const val COLOR_MODE_RAINBOW = "rainbow"
        /** Notification pulses, one of the edge light moments. */
        private const val KEY_NOTIFICATIONS = "edge_light_moment_notifications"

        @Volatile
        private var INSTANCE: EdgeLightViewController? = null

        @JvmStatic
        fun get(context: Context): EdgeLightViewController {
            return INSTANCE ?: throw IllegalStateException(
                "EdgeLightViewController not initialized"
            )
        }
    }
}
