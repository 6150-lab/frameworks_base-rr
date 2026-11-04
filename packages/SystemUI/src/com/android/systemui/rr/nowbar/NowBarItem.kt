/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.session.MediaController

/** What the Now Bar can show, in the order it shows them. */
enum class NowBarType {
    CALL,
    NAVIGATION,
    TIMER,
    RECORDING,
    MUSIC,
    SPORTS,
    LIVE,
    CHARGING,
    FLASHLIGHT,
}

/** A button on a Now Bar item: a notification action or one of ours. */
class NowBarAction(
    val title: CharSequence,
    val icon: Icon? = null,
    val iconRes: Int = 0,
    val intent: PendingIntent? = null,
    val run: (() -> Unit)? = null,
)

/** A game: both sides, their scores and where the game is at. */
class NowBarScore(
    val team1: CharSequence,
    val team2: CharSequence,
    val score1: CharSequence,
    val score2: CharSequence,
    val logo1: Drawable?,
    val logo2: Drawable?,
    val status: CharSequence?,
)

/** One activity in the Now Bar. */
class NowBarItem(
    /** Stable across updates: the notification key, "media:<package>", "charging"... */
    val key: String,
    val type: NowBarType,
    val packageName: String?,
    val icon: Drawable?,
    val art: Bitmap?,
    /** The capsule tint and wave colors drawn from [art], made with it. */
    val artColors: IntArray? = null,
    val title: CharSequence,
    val text: CharSequence?,
    /** Wall clock time a chronometer counts from or to, or 0. */
    val chronometerBase: Long = 0L,
    val countDown: Boolean = false,
    /** 0 to 1, or -1 for none. */
    val progress: Float = -1f,
    val color: Int = 0,
    val playing: Boolean = false,
    val actions: List<NowBarAction> = emptyList(),
    val contentIntent: PendingIntent? = null,
    val controller: MediaController? = null,
    val score: NowBarScore? = null,
)
