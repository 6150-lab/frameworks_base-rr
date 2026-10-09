/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.shared.model

import androidx.compose.runtime.staticCompositionLocalOf

/** Quick settings look, picked in Remixer and stored in Settings.System [SETTING]. */
enum class QsStyle(val value: Int) {
    STOCK(0),
    REMIX(1),
    COMPACT(2),
    GLASS(3),
    CIRCLES(4),
    CLASSIC(5),
    CARDS(6),
    MONO(7);

    /** Every tile is one column wide and shows its label. */
    val uniformTiles: Boolean
        get() = this == CLASSIC || this == CARDS

    fun columns(base: Int): Int =
        when (this) {
            CLASSIC -> base + base / 4
            CARDS -> maxOf(2, base * 3 / 4)
            else -> base
        }

    companion object {
        const val SETTING = "rr_qs_style"

        fun of(value: Int): QsStyle = entries.firstOrNull { it.value == value } ?: STOCK
    }
}

val LocalQsStyle = staticCompositionLocalOf { QsStyle.STOCK }
