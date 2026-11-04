/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.media

import android.app.WallpaperColors
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaSessionManager
import java.util.concurrent.Executors
import javax.inject.Inject
import android.media.session.MediaSessionManager as SessionService

/**
 * Feeds the active media session into [MediaSessionManager], which the ported clock styles
 * read for playback state and album colors. Evolution X feeds it from code RR doesn't carry.
 */
@SysUISingleton
class MediaSessionFeeder @Inject constructor(private val context: Context) : CoreStartable {

    private val handler = Handler(Looper.getMainLooper())
    private val colorExecutor = Executors.newSingleThreadExecutor()
    private val controllers = ArrayList<MediaController>()
    private var lastArtKey: String? = null
    private lateinit var service: SessionService

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()

        override fun onMetadataChanged(metadata: MediaMetadata?) = update()

        override fun onSessionDestroyed() {
            handler.post { track(sessions()) }
        }
    }

    override fun start() {
        service = context.getSystemService(SessionService::class.java)
        service.addOnActiveSessionsChangedListener({ list -> track(list ?: emptyList()) }, null, handler)
        track(sessions())
    }

    private fun sessions(): List<MediaController> =
        try {
            service.getActiveSessions(null)
        } catch (e: SecurityException) {
            emptyList()
        }

    private fun track(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        controllers += list
        controllers.forEach { it.registerCallback(callback, handler) }
        update()
    }

    private fun update() {
        val manager = MediaSessionManager.get()
        val controller = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull { it.metadata != null }
        manager.onPlaybackStateChanged(controller?.playbackState?.state ?: PlaybackState.STATE_NONE)
        val metadata = controller?.metadata ?: return
        manager.onMetadataChanged(metadata)
        try {
            manager.onAppIconChanged(context.packageManager.getApplicationIcon(controller.packageName))
        } catch (e: PackageManager.NameNotFoundException) {
            // Uninstalled while playing.
        }
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        // Every fetch brings a new Bitmap; the key tells the same cover from a new one.
        val key = art?.let { MediaArt.key(controller.packageName, metadata, it) }
        if (key == lastArtKey) return
        lastArtKey = key
        manager.onAlbumArtChanged(art?.let { BitmapDrawable(context.resources, it) })
        if (art == null) return
        colorExecutor.execute {
            val color = try {
                WallpaperColors.fromBitmap(art).primaryColor.toArgb()
            } catch (e: RuntimeException) {
                return@execute
            }
            handler.post { if (lastArtKey == key) manager.onMediaColorsChanged(color) }
        }
    }
}
