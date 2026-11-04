/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.looks

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** The user's Looks as Remixer keeps them: saved ones in a setting, RR's own on /product. */
object Looks {
    private const val TAG = "RrLooks"
    const val KEY_LOOKS = "rr_looks"
    /** The Look applied last, written by Remixer when it applies one. */
    const val KEY_CURRENT = "rr_look_current"
    private const val LOOKS_DIR = "/product/etc/rr_looks"
    private const val PREFIX = "rrlook:"
    private const val ACTION_APPLY = "com.resurrection.settings.APPLY_LOOK"
    private const val SETTINGS_PACKAGE = "com.android.settings"

    /** Saved Looks first, then the built-in ones in their order. */
    fun names(context: Context): List<String> {
        val names = ArrayList<String>()
        try {
            val json = Settings.System.getStringForUser(
                context.contentResolver, KEY_LOOKS, UserHandle.USER_CURRENT
            )
            val array = JSONArray(json ?: "[]")
            for (i in 0 until array.length()) {
                array.getJSONObject(i).optString("name").takeIf { it.isNotEmpty() }?.let(names::add)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Saved looks unreadable", e)
        }
        val builtIn = File(LOOKS_DIR).listFiles { _, name -> name.endsWith(".rrlook") } ?: emptyArray()
        builtIn.mapNotNull { file ->
            try {
                val body = file.readText().trim().removePrefix(PREFIX)
                JSONObject(body).takeIf { it.has("name") }
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.optInt("order", 99) }.forEach { look ->
            look.optString("name").takeIf { it.isNotEmpty() && it !in names }?.let(names::add)
        }
        return names
    }

    fun current(context: Context): String? =
        Settings.System.getStringForUser(context.contentResolver, KEY_CURRENT, UserHandle.USER_CURRENT)
            ?.takeIf { it.isNotEmpty() }

    /** Asks Remixer to put [name] on, wallpaper included. */
    fun apply(context: Context, name: String) {
        context.sendBroadcastAsUser(
            Intent(ACTION_APPLY)
                .setPackage(SETTINGS_PACKAGE)
                .putExtra("name", name)
                .putExtra("wallpaper", true),
            UserHandle.CURRENT,
        )
    }
}
