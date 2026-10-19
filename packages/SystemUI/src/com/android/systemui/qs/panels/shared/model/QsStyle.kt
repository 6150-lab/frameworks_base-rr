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
    MONO(7),
    NEON(8),
    BENTO(9),
    SHAPES(10),
    BLUEPRINT(11),
    DUO(12),
    SOFT(13);

    /** Every tile is one column wide and shows its label. */
    val uniformTiles: Boolean
        get() = this == CLASSIC || this == CARDS || this == SHAPES

    fun columns(base: Int): Int =
        when (this) {
            CLASSIC,
            SHAPES -> base + base / 4
            CARDS -> maxOf(2, base * 3 / 4)
            else -> base
        }

    companion object {
        const val SETTING = "rr_qs_style"
        const val LOCKSCREEN_MEDIA_SETTING = "rr_lockscreen_media_style"

        fun of(value: Int): QsStyle = entries.firstOrNull { it.value == value } ?: STOCK
    }
}

val LocalQsStyle = staticCompositionLocalOf { QsStyle.STOCK }

/** Liquid Glass material on the tiles; [SOLID] while glass is off for QS or blur is off. */
enum class QsGlass {
    SOLID,
    FROSTED,
    LIQUID;

    companion object {
        const val SETTING_QS = "rr_glass_qs"
        const val SETTING_MATERIAL = "rr_glass_material"
    }
}

val LocalQsGlass = staticCompositionLocalOf { QsGlass.SOLID }
