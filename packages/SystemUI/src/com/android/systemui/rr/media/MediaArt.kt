/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.media

import android.graphics.Bitmap
import android.media.MediaMetadata

/** Album art from media sessions. */
object MediaArt {
    private const val SAMPLES = 8

    /**
     * Names the picture: every getMetadata() unparcels a new Bitmap, so identity can't tell a
     * play/pause from a new song. The app, track and size plus a few pixels can.
     */
    fun key(pkg: String?, metadata: MediaMetadata, art: Bitmap): String {
        val key = StringBuilder().append(pkg).append('|')
            .append(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)).append('|')
            .append(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)).append('|')
            .append(art.width).append('x').append(art.height)
        try {
            for (i in 0 until SAMPLES) {
                key.append('|').append(art.getPixel(art.width * i / SAMPLES, art.height * i / SAMPLES))
            }
        } catch (e: IllegalStateException) {
            // Hardware and recycled bitmaps have no pixels to read.
        }
        return key.toString()
    }
}
