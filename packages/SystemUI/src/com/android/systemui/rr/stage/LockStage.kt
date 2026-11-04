/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.stage

import org.json.JSONObject

/** One tile of the Lock Stage: what it is and where it sits on the grid. */
data class StageTile(
    val id: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** Whether it stays on the always-on display. */
    val aod: Boolean,
)

/**
 * The Lock Stage layout Remixer saves in Settings.System [KEY]: a grid of [cols] by [rows]
 * over the whole lock screen, and the tiles on it. A missing tile is a hidden one.
 */
class StageSpec(val cols: Int, val rows: Int, val tiles: List<StageTile>) {
    fun tile(id: String) = tiles.firstOrNull { it.id == id }

    companion object {
        const val KEY = "rr_lock_stage"

        const val CLOCK = "clock"
        const val GLANCE = "glance"
        const val WEATHER = "weather"
        const val NOW_BAR = "nowbar"
        const val SHORTCUTS = "shortcuts"
        const val BATTERY = "battery"
        const val ALARM = "alarm"
        const val TOGGLES = "toggles"

        /** Null when there is no stage, or it cannot be read: the stock lock screen then. */
        fun parse(json: String?): StageSpec? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val cols = o.optInt("cols", 6).coerceIn(2, 12)
                val rows = o.optInt("rows", 12).coerceIn(4, 24)
                val array = o.getJSONArray("tiles")
                val tiles = ArrayList<StageTile>()
                for (i in 0 until array.length()) {
                    val t = array.getJSONObject(i)
                    val w = t.optInt("w", 1).coerceIn(1, cols)
                    val h = t.optInt("h", 1).coerceIn(1, rows)
                    tiles += StageTile(
                        id = t.getString("id"),
                        x = t.optInt("x", 0).coerceIn(0, cols - w),
                        y = t.optInt("y", 0).coerceIn(0, rows - h),
                        w = w,
                        h = h,
                        aod = t.optBoolean("aod", true),
                    )
                }
                if (tiles.isEmpty()) null else StageSpec(cols, rows, tiles)
            } catch (e: Exception) {
                null
            }
        }
    }
}
