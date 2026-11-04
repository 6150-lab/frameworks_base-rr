/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.Notification
import android.app.Person
import android.app.WallpaperColors
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import com.android.settingslib.utils.StringUtil
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.rr.media.MediaArt
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.FlashlightController
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.max

/**
 * Everything happening right now, for the Now Bar: music from media sessions; calls, navigation,
 * timers, recording and Live Updates from ongoing notifications; charging and the flashlight.
 */
@SysUISingleton
class NowBarRepository
@Inject
constructor(
    private val context: Context,
    private val notificationListener: NotificationListener,
    private val batteryController: BatteryController,
    private val flashlightController: FlashlightController,
    @Background private val bgExecutor: Executor,
    val activityStarter: ActivityStarter,
) : CoreStartable, NotificationListener.NotificationHandler {

    fun interface Listener {
        fun onNowBarChanged(items: List<NowBarItem>)
    }

    val settings = NowBarSettings(context)

    /** What the Now Bar shows, most important first. */
    var items: List<NowBarItem> = emptyList()
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val notificationItems = LinkedHashMap<String, NowBarItem>()
    private val hidden = HashSet<String>()
    private var lockscreenCallKeys = emptySet<String>()
    private val controllers = ArrayList<MediaController>()
    private var mediaItem: NowBarItem? = null
    // The song's art scaled, with its colors: one instance across state changes, so views can
    // compare art by identity.
    private var artKey: String? = null
    private var pendingArtKey: String? = null
    private var art: Bitmap? = null
    private var artColors: IntArray? = null
    private var chargingItem: NowBarItem? = null
    private var flashlightItem: NowBarItem? = null
    private lateinit var sessionManager: MediaSessionManager
    private val scores = NowBarScores(context, handler) { publish() }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            updateSessions(sessions ?: emptyList())
        }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()

        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()

        override fun onSessionDestroyed() {
            handler.post { updateSessions(activeSessions()) }
        }
    }

    private val batteryCallback = object : BatteryController.BatteryStateChangeCallback {
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            chargingItem = if (pluggedIn) chargingItem(level, charging) else null
            publish()
        }
    }

    private val flashlightListener = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            handler.post {
                flashlightItem = if (enabled) flashlightItem() else null
                publish()
            }
        }

        override fun onFlashlightError() {
            handler.post {
                flashlightItem = null
                publish()
            }
        }

        override fun onFlashlightAvailabilityChanged(available: Boolean) {}
    }

    override fun start() {
        instance = this
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                settings.read()
                scores.setSource(if (settings.enabled) settings.scores else NowBarScores.SOURCE_OFF)
                publish()
            }
        }
        for (key in NowBarSettings.KEYS) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
            )
        }
        notificationListener.addNotificationHandler(this)
        sessionManager = context.getSystemService(MediaSessionManager::class.java)
        sessionManager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
        updateSessions(activeSessions())
        batteryController.addCallback(batteryCallback)
        flashlightController.addCallback(flashlightListener)
        scores.setSource(if (settings.enabled) settings.scores else NowBarScores.SOURCE_OFF)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("NowBar: enabled=${settings.enabled} sessions=${controllers.size} " +
            "media=${mediaItem != null} notifications=${notificationItems.keys} hidden=$hidden " +
            "scores=${settings.scores}:${scores.items.size}")
        pw.println("  items=${items.map { "${it.type}:${it.key}" }}")
        for (listener in listeners) {
            val view = listener as? android.view.View
            if (view == null) {
                pw.println("  listener=$listener")
                continue
            }
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            pw.println("  view=${view.javaClass.simpleName} attached=${view.isAttachedToWindow} " +
                "visibility=${view.visibility} alpha=${view.alpha} size=${view.width}x${view.height} " +
                "at=${at[0]},${at[1]} shown=${view.isShown}")
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onNowBarChanged(items)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Pins [packageName]'s Live Update first and on the always-on display; null unpins. */
    fun pin(packageName: String?) {
        Settings.System.putStringForUser(context.contentResolver, NowBarSettings.KEY_PIN,
            packageName ?: "", UserHandle.USER_CURRENT)
    }

    /** Hides [key] until it ends. */
    fun hide(key: String) {
        hidden += key
        publish()
    }

    private fun publish() {
        val all = ArrayList<NowBarItem>(notificationItems.values)
        mediaItem?.let(all::add)
        chargingItem?.let(all::add)
        flashlightItem?.let(all::add)
        all += scores.items
        hidden.retainAll(all.map { it.key }.toSet())
        val pinned = settings.pinned
        // A pinned Live Update leads, so it is what the always-on display shows.
        items = all.filter { settings.shows(it.type) && it.key !in hidden }
            .sortedWith(compareBy({ !(it.type == NowBarType.LIVE && it.packageName == pinned) },
                { it.type.ordinal }))
        val callKeys = if (settings.hidesLockscreenCalls()) {
            items.filter { it.type == NowBarType.CALL }.map { it.key }.toSet()
        } else {
            emptySet()
        }
        val callsChanged = callKeys != lockscreenCallKeys
        lockscreenCallKeys = callKeys
        listeners.toList().forEach { it.onNowBarChanged(items) }
        if (callsChanged) lockscreenListener?.invoke()
    }

    /** True while this notification is the call the Now Bar is showing. */
    fun showsCallOnLockscreen(key: String) =
        settings.hidesLockscreenCalls() && items.any { it.type == NowBarType.CALL && it.key == key }

    // Media

    private fun activeSessions(): List<MediaController> =
        try {
            sessionManager.getActiveSessions(null)
        } catch (e: SecurityException) {
            Log.w(TAG, "No media sessions", e)
            emptyList()
        }

    private fun updateSessions(sessions: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers.clear()
        controllers += sessions
        controllers.forEach { it.registerCallback(mediaCallback, handler) }
        updateMedia()
    }

    private fun updateMedia() {
        // Metadata once per session: a second fetch can come back empty when the session ends.
        val sessions = controllers.mapNotNull { c -> c.metadata?.let { c to it } }
        val session = sessions.firstOrNull { (c, _) -> c.isPlaying() }
            ?: sessions.firstOrNull { (c, _) -> c.playbackState?.state == PlaybackState.STATE_PAUSED }
        val item = session?.let { (c, metadata) -> mediaItem(c, metadata) }
        // A song swiped away while paused comes back when it plays again.
        if (item?.playing == true && mediaItem?.playing != true) hidden -= item.key
        mediaItem = item
        publish()
    }

    private fun MediaController.isPlaying() = playbackState?.state == PlaybackState.STATE_PLAYING

    private fun mediaItem(controller: MediaController, metadata: MediaMetadata): NowBarItem {
        val playing = controller.isPlaying()
        val pkg = controller.packageName
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel(pkg)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val raw = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = raw?.let { art(pkg, metadata, it) }
        val controls = controller.transportControls
        val actions = listOf(
            NowBarAction(context.getString(R.string.now_bar_previous),
                iconRes = R.drawable.rr_nowbar_prev, run = { controls.skipToPrevious() }),
            if (playing) {
                NowBarAction(context.getString(R.string.now_bar_pause),
                    iconRes = R.drawable.rr_nowbar_pause, run = { controls.pause() })
            } else {
                NowBarAction(context.getString(R.string.now_bar_play),
                    iconRes = R.drawable.rr_nowbar_play, run = { controls.play() })
            },
            NowBarAction(context.getString(R.string.now_bar_next),
                iconRes = R.drawable.rr_nowbar_next, run = { controls.skipToNext() }),
        )
        return NowBarItem(
            key = "media:$pkg",
            type = NowBarType.MUSIC,
            packageName = pkg,
            icon = appIcon(pkg),
            art = art,
            artColors = if (art != null) artColors else null,
            title = title ?: "",
            text = artist,
            playing = playing,
            actions = actions,
            contentIntent = controller.sessionActivity,
            controller = controller,
        )
    }

    // Notifications

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val item = notificationItem(sbn)
        if (item != null) {
            notificationItems[sbn.key] = item
        } else if (notificationItems.remove(sbn.key) == null) {
            return
        }
        publish()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (notificationItems.remove(sbn.key) != null) publish()
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) = onNotificationRemoved(sbn, rankingMap)

    override fun onNotificationsInitialized() {
        val active = try {
            notificationListener.activeNotifications
        } catch (e: RuntimeException) {
            null
        }
        active?.forEach { sbn -> notificationItem(sbn)?.let { notificationItems[sbn.key] = it } }
        publish()
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    private fun classify(sbn: StatusBarNotification): NowBarType? {
        val n = sbn.notification
        val ongoing =
            n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (!ongoing || n.isMediaNotification) return null
        val chronometer = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        return when {
            n.isStyle(Notification.CallStyle::class.java) ||
                n.category == Notification.CATEGORY_CALL -> NowBarType.CALL
            sbn.packageName == context.packageName ->
                if (n.channelId == SCREEN_RECORD_CHANNEL) NowBarType.RECORDING else null
            n.category == Notification.CATEGORY_NAVIGATION -> NowBarType.NAVIGATION
            n.category == Notification.CATEGORY_STOPWATCH -> NowBarType.TIMER
            chronometer && (n.category == Notification.CATEGORY_ALARM ||
                sbn.packageName in CLOCK_PACKAGES) -> NowBarType.TIMER
            sbn.packageName in RECORDER_PACKAGES -> NowBarType.RECORDING
            (n.flags and Notification.FLAG_PROMOTED_ONGOING != 0 ||
                n.isStyle(Notification.ProgressStyle::class.java)) &&
                settings.showsLiveFrom(sbn.packageName) -> NowBarType.LIVE
            else -> null
        }
    }

    private fun notificationItem(sbn: StatusBarNotification): NowBarItem? {
        val type = classify(sbn) ?: return null
        val n = sbn.notification
        val extras = n.extras
        val packageContext = try {
            sbn.getPackageContext(context)
        } catch (e: RuntimeException) {
            context
        }
        var title = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        if (type == NowBarType.CALL) {
            extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name
                ?.let { title = it }
        }
        val label = title ?: appLabel(sbn.packageName) ?: return null
        val live = if (type == NowBarType.LIVE) liveOf(n, packageContext) else null
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val progress = when {
            live != null -> live.progress
            max > 0 && !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) ->
                extras.getInt(Notification.EXTRA_PROGRESS).toFloat() / max
            else -> -1f
        }
        val actions = n.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && it.title != null }
            .take(MAX_ACTIONS)
            .map { NowBarAction(it.title, icon = it.getIcon(), intent = it.actionIntent) }
        return NowBarItem(
            key = sbn.key,
            type = type,
            packageName = sbn.packageName,
            icon = n.smallIcon?.loadDrawable(packageContext),
            art = n.getLargeIcon()?.loadDrawable(packageContext)?.let {
                it.toBitmap(LARGE_ICON_SIZE, LARGE_ICON_SIZE)
            },
            title = label,
            text = extras.getCharSequence(Notification.EXTRA_TEXT),
            chronometerBase =
                if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) n.`when` else 0L,
            countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            progress = progress,
            color = n.color,
            actions = actions,
            contentIntent = n.contentIntent,
            live = live,
        )
    }

    /** Android 16's progress bar from a Live Update, or null when it has none. */
    private fun liveOf(n: Notification, packageContext: Context): NowBarLive? {
        val short = n.shortCriticalText
        if (!n.isStyle(Notification.ProgressStyle::class.java)) {
            return short?.let { NowBarLive(emptyList(), emptyList(), -1f, false, it) }
        }
        val style = try {
            Notification.Builder.recoverBuilder(packageContext, n).style
                as? Notification.ProgressStyle
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val max = style.progressMax.coerceAtLeast(1).toFloat()
        val segments = style.progressSegments
        return NowBarLive(
            segments = if (segments.isEmpty()) listOf(1f to 0)
                else segments.map { it.length / max to it.color },
            points = style.progressPoints.map { (it.position / max).coerceIn(0f, 1f) to it.color },
            progress = if (style.isProgressIndeterminate) -1f
                else (style.progress / max).coerceIn(0f, 1f),
            styledByProgress = style.isStyledByProgress,
            shortText = short,
        )
    }

    // Charging and flashlight

    private fun chargingItem(level: Int, charging: Boolean): NowBarItem {
        val remaining = context.getSystemService(BatteryManager::class.java)
            ?.computeChargeTimeRemaining() ?: -1L
        val text = when {
            level >= 100 || !charging -> context.getString(R.string.now_bar_charged)
            remaining > 0 -> context.getString(
                R.string.now_bar_full_in,
                StringUtil.formatElapsedTime(context, remaining.toDouble(), false, true),
            )
            else -> null
        }
        return NowBarItem(
            key = KEY_CHARGING,
            type = NowBarType.CHARGING,
            packageName = null,
            icon = context.getDrawable(R.drawable.rr_nowbar_bolt),
            art = null,
            title = context.getString(R.string.now_bar_charging, level),
            text = text,
            progress = level / 100f,
        )
    }

    private fun flashlightItem() = NowBarItem(
        key = KEY_FLASHLIGHT,
        type = NowBarType.FLASHLIGHT,
        packageName = null,
        icon = context.getDrawable(R.drawable.rr_ql_flashlight),
        art = null,
        title = context.getString(R.string.now_bar_flashlight_on),
        text = null,
        actions = listOf(
            NowBarAction(context.getString(R.string.now_bar_turn_off),
                iconRes = R.drawable.rr_nowbar_close, run = { flashlightController.setFlashlight(false) })
        ),
    )

    private fun appLabel(pkg: String?): CharSequence? =
        pkg?.let {
            try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(it, 0)
                )
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

    private fun appIcon(pkg: String): Drawable? =
        try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun scaled(bitmap: Bitmap, size: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= size) return bitmap
        val f = size.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * f).toInt().coerceAtLeast(1),
            (bitmap.height * f).toInt().coerceAtLeast(1), true,
        )
    }

    /**
     * [raw] scaled, with its colors, made on the background executor: a cover is a few frames
     * of work. Every getMetadata() unparcels a new Bitmap, so [MediaArt.key] tells the song;
     * the last song's art stays until the new one is ready.
     */
    private fun art(pkg: String, metadata: MediaMetadata, raw: Bitmap): Bitmap? {
        val key = MediaArt.key(pkg, metadata, raw)
        if (key != artKey && key != pendingArtKey) {
            pendingArtKey = key
            bgExecutor.execute {
                val scaled = try {
                    scaled(raw, ART_SIZE)
                } catch (e: RuntimeException) {
                    raw
                }
                val colors = paletteOf(scaled)
                handler.post {
                    if (pendingArtKey != key) return@post
                    pendingArtKey = null
                    artKey = key
                    art = scaled
                    artColors = colors
                    updateMedia()
                }
            }
        }
        return art
    }

    /** The capsule's tint and three wave colors from the album art. */
    private fun paletteOf(art: Bitmap): IntArray? =
        try {
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

    private fun tone(color: Int, lightness: Float, maxSaturation: Float, alpha: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1].coerceAtMost(maxSaturation)
        hsl[2] = lightness
        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), alpha)
    }

    companion object {
        /** For views inflated outside Dagger, like the status bar chip. */
        @Volatile
        var instance: NowBarRepository? = null
            private set

        /** Lock screen list refreshes when a call enters or leaves the bar. */
        @Volatile
        var lockscreenListener: (() -> Unit)? = null

        private const val TAG = "NowBar"
        private const val KEY_CHARGING = "charging"
        private const val KEY_FLASHLIGHT = "flashlight"
        private const val SCREEN_RECORD_CHANNEL = "screen_record"
        private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")
        private val RECORDER_PACKAGES = setOf("org.lineageos.recorder")
        private const val MAX_ACTIONS = 3
        private const val ART_SIZE = 512
        private const val LARGE_ICON_SIZE = 192
    }
}
