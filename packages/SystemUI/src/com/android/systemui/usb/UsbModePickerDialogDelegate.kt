/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.usb

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.TetheringManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.android.compose.theme.PlatformTheme
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.DialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
class UsbModePickerDialogDelegate @Inject constructor(
    private val context: Context,
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val shadeDialogContextInteractor: ShadeDialogContextInteractor,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val userManager: UserManager,
    private val keyguardStateController: KeyguardStateController,
    private val activityStarter: ActivityStarter,
    @Main private val mainHandler: Handler,
) : CoreStartable {

    private var currentDialog: ComponentSystemUIDialog? = null
    private var shownForCurrentSession = false
    private var pendingShow = false

    private val usbManager: UsbManager =
        context.getSystemService(UsbManager::class.java)!!
    private val tetheringManager: TetheringManager =
        context.getSystemService(TetheringManager::class.java)!!
    private val midiSupported = context.packageManager
        .hasSystemFeature(PackageManager.FEATURE_MIDI)
    private val tetheringSupported = tetheringManager.isTetheringSupported
    private val isAdminUser = userManager.isAdminUser
    private val fileTransferRestricted = userManager.hasBaseUserRestriction(
        UserManager.DISALLOW_USB_FILE_TRANSFER, UserHandle.of(UserHandle.myUserId()),
    )
    private val tetheringRestricted = userManager.hasBaseUserRestriction(
        UserManager.DISALLOW_CONFIG_TETHERING, UserHandle.of(UserHandle.myUserId()),
    )

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onUnlockedChanged() {
            if (keyguardStateController.isUnlocked && pendingShow) {
                pendingShow = false
                mainHandler.postDelayed({ showDialog() }, UNLOCK_SHOW_DELAY_MS)
            }
        }
    }

    override fun start() {
        keyguardStateController.addCallback(keyguardCallback)

        broadcastDispatcher.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val connected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false)
                    if (connected) {
                        val functions = resolveFunctions(intent)
                        if (!shownForCurrentSession && isPickerEnabled()) {
                            shownForCurrentSession = true
                            if (keyguardStateController.isUnlocked) {
                                showDialog(functions)
                            } else {
                                pendingShow = true
                            }
                        }
                    } else {
                        shownForCurrentSession = false
                        pendingShow = false
                        dismissDialog()
                    }
                }
            },
            IntentFilter(UsbManager.ACTION_USB_STATE),
        )
    }

    fun showDialog() {
        showDialog(usbManager.currentFunctions)
    }

    private fun showDialog(functions: Long) {
        currentDialog?.dismiss()

        val modes = buildModeList(functions)
        if (modes.isEmpty()) return

        currentDialog = sysuiDialogFactory.create(
            context = shadeDialogContextInteractor.context,
            refreshBackgroundOnThemeChange = false,
            dialogDelegate = IslandWindow,
            isTransient = true,
        ) { dialog ->
            UsbModePickerIsland(dialog, modes, functions)
        }.apply {
            setCanceledOnTouchOutside(false)
        }

        currentDialog?.show()
    }

    private fun isPickerEnabled(): Boolean =
        Settings.System.getIntForUser(
            context.contentResolver, SETTING_PICKER, 1, UserHandle.USER_CURRENT,
        ) != 0

    @Composable
    private fun UsbModePickerIsland(
        dialog: SystemUIDialog,
        initialModes: List<UsbModeItem>,
        currentFunctions: Long,
    ) {
        val isCurrentlyInDarkTheme = isSystemInDarkTheme()
        val cachedDarkTheme = remember { isCurrentlyInDarkTheme }

        PlatformTheme(isDarkTheme = cachedDarkTheme) {
            UsbModePickerContent(
                dialog = dialog,
                modes = initialModes,
                onModeSelected = { function ->
                    onModeSelected(function, currentFunctions)
                    dialog.dismiss()
                },
                onSettingsClick = {
                    dialog.dismiss()
                    openUsbSettings()
                },
                onDismiss = { dialog.dismiss() },
            )
        }
    }

    private fun openUsbSettings() {
        val intent = Intent.makeRestartActivityTask(
            ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$UsbDetailsActivity",
            ),
        )
        activityStarter.startActivity(intent, true)
    }

    private fun onModeSelected(function: Long, previousFunction: Long) {
        if (function == resolveDisplayFunction(previousFunction)) return

        if (isAuthRequired(function)) {
            if (keyguardStateController.isMethodSecure && !keyguardStateController.isUnlocked) {
                activityStarter.postQSRunnableDismissingKeyguard {
                    applyUsbFunction(function, previousFunction)
                }
                return
            }
        }
        applyUsbFunction(function, previousFunction)
    }

    private fun applyUsbFunction(function: Long, previousFunction: Long) {
        if (function == UsbManager.FUNCTION_RNDIS || function == UsbManager.FUNCTION_NCM) {
            tetheringManager.startTethering(
                TetheringManager.TETHERING_USB,
                HandlerExecutor(mainHandler),
                object : TetheringManager.StartTetheringCallback {
                    override fun onTetheringFailed(error: Int) {
                        usbManager.currentFunctions = previousFunction
                    }
                },
            )
        } else {
            usbManager.currentFunctions = function
        }
    }

    private fun resolveFunctions(intent: Intent): Long {
        var functions = UsbManager.FUNCTION_NONE
        if (intent.getBooleanExtra(UsbManager.USB_FUNCTION_MTP, false)
            && intent.getBooleanExtra(UsbManager.USB_DATA_UNLOCKED, false)) {
            functions = functions or UsbManager.FUNCTION_MTP
        }
        if (intent.getBooleanExtra(UsbManager.USB_FUNCTION_PTP, false)
            && intent.getBooleanExtra(UsbManager.USB_DATA_UNLOCKED, false)) {
            functions = functions or UsbManager.FUNCTION_PTP
        }
        if (intent.getBooleanExtra(UsbManager.USB_FUNCTION_RNDIS, false)) {
            functions = functions or UsbManager.FUNCTION_RNDIS
        }
        if (intent.getBooleanExtra(UsbManager.USB_FUNCTION_MIDI, false)) {
            functions = functions or UsbManager.FUNCTION_MIDI
        }
        if (intent.getBooleanExtra(UsbManager.USB_FUNCTION_ACCESSORY, false)) {
            functions = functions or UsbManager.FUNCTION_ACCESSORY
        }
        if (intent.getBooleanExtra(UsbManager.USB_FUNCTION_NCM, false)) {
            functions = functions or UsbManager.FUNCTION_NCM
        }
        if (intent.getBooleanExtra(UsbManager.USB_FUNCTION_UVC, false)) {
            functions = functions or UsbManager.FUNCTION_UVC
        }
        return functions
    }

    private fun buildModeList(currentFunctions: Long): List<UsbModeItem> {
        val displayFunction = resolveDisplayFunction(currentFunctions)
        return USB_MODES.filter { isFunctionSupported(it.function) }
            .map { it.copy(selected = it.function == displayFunction) }
    }

    private fun resolveDisplayFunction(functions: Long): Long = when {
        (functions and UsbManager.FUNCTION_ACCESSORY) != 0L -> UsbManager.FUNCTION_MTP
        functions == UsbManager.FUNCTION_NCM -> UsbManager.FUNCTION_RNDIS
        else -> functions
    }

    private fun isFunctionSupported(function: Long): Boolean {
        if (!midiSupported && (function and UsbManager.FUNCTION_MIDI) != 0L) return false
        if (!tetheringSupported && (function and UsbManager.FUNCTION_RNDIS) != 0L) return false
        if (isDisallowedBySystem(function)) return false
        if (!isAdminUser && (function and UsbManager.FUNCTION_RNDIS) != 0L) return false
        return true
    }

    private fun isDisallowedBySystem(function: Long): Boolean {
        if (fileTransferRestricted && ((function and UsbManager.FUNCTION_MTP) != 0L
                    || (function and UsbManager.FUNCTION_PTP) != 0L)) return true
        if (tetheringRestricted && (function and UsbManager.FUNCTION_RNDIS) != 0L) return true
        if (!usbManager.isUvcGadgetSupportEnabled()
            && (function and UsbManager.FUNCTION_UVC) != 0L) return true
        return false
    }

    private fun isAuthRequired(function: Long): Boolean =
        function != UsbManager.FUNCTION_UVC && function != UsbManager.FUNCTION_MIDI

    private fun dismissDialog() {
        currentDialog?.dismiss()
        currentDialog = null
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("UsbModePickerDialogDelegate:")
        pw.println("  dialogShowing=${currentDialog != null}")
        pw.println("  pendingShow=$pendingShow")
        pw.println("  shownForCurrentSession=$shownForCurrentSession")
        pw.println("  currentFunctions=${usbManager.currentFunctions}")
    }

    /** A card-sized island at the bottom that leaves the rest of the screen usable. */
    private object IslandWindow : DialogDelegate<SystemUIDialog> {
        override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
            val window = dialog.window ?: return
            window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setDimAmount(0f)
            // The island blurs only its own card.
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            )
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            window.attributes = window.attributes.apply {
                blurBehindRadius = 0
                y = dialog.context.resources.getDimensionPixelSize(R.dimen.usb_island_margin)
            }
        }

        override fun getWidth(dialog: SystemUIDialog): Int {
            val res = dialog.context.resources
            return minOf(
                res.displayMetrics.widthPixels -
                    2 * res.getDimensionPixelSize(R.dimen.usb_island_margin),
                res.getDimensionPixelSize(R.dimen.usb_island_max_width),
            )
        }

        override fun getHeight(dialog: SystemUIDialog): Int =
            WindowManager.LayoutParams.WRAP_CONTENT
    }

    companion object {
        private const val UNLOCK_SHOW_DELAY_MS = 500L
        private const val SETTING_PICKER = "rr_usb_mode_picker"

        private val USB_MODES = listOf(
            UsbModeItem(UsbManager.FUNCTION_MTP, R.string.usb_mode_file_transfer, R.string.usb_mode_short_files, Icons.Outlined.SwapVert, false),
            UsbModeItem(UsbManager.FUNCTION_RNDIS, R.string.usb_mode_tethering, R.string.usb_mode_short_tethering, Icons.Outlined.Link, false),
            UsbModeItem(UsbManager.FUNCTION_MIDI, R.string.usb_mode_midi, R.string.usb_mode_midi, Icons.Outlined.MusicNote, false),
            UsbModeItem(UsbManager.FUNCTION_PTP, R.string.usb_mode_photo_transfer, R.string.usb_mode_short_photos, Icons.Outlined.Image, false),
            UsbModeItem(UsbManager.FUNCTION_UVC, R.string.usb_mode_webcam, R.string.usb_mode_short_webcam, Icons.Outlined.Videocam, false),
            UsbModeItem(UsbManager.FUNCTION_NONE, R.string.usb_mode_charging_only, R.string.usb_mode_short_charge, Icons.Outlined.Bolt, false),
        )
    }
}
