/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.window.data.repository

import android.app.ActivityManager
import android.content.Context
import android.database.ContentObserver
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.view.CrossWindowBlurListeners
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.system.RrGlass
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.window.data.repository.WindowRootViewBlurRepository.Companion.isDisableBlurSysPropSet
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

typealias BlurAppliedListener = Consumer<Int>

/** Repository that maintains state for the window blur effect. */
interface WindowRootViewBlurRepository {
    val blurRequestedByShade: MutableStateFlow<Float>

    val scaleRequestedByShade: MutableStateFlow<Float>

    /** Is blur supported based on settings toggle and battery power saver mode. */
    val isBlurSupported: StateFlow<Boolean>

    var blurAppliedListener: BlurAppliedListener?

    /** true when tracking shade motion that might lead to a shade expansion. */
    val trackingShadeMotion: MutableStateFlow<Boolean>

    companion object {
        /**
         * Whether the `persist.sysui.disableBlur` is set, this is used to disable blur for tests.
         */
        @JvmStatic
        fun isDisableBlurSysPropSet() = SystemProperties.getBoolean(DISABLE_BLUR_PROPERTY, false)

        // property that can be used to disable the cross window blur for tests
        private const val DISABLE_BLUR_PROPERTY = "persist.sysui.disableBlur"
    }
}

@SysUISingleton
class WindowRootViewBlurRepositoryImpl
@Inject
constructor(
    crossWindowBlurListeners: CrossWindowBlurListeners,
    @Main private val executor: Executor,
    @Application private val scope: CoroutineScope,
    @Application private val context: Context,
) : WindowRootViewBlurRepository {

    override val trackingShadeMotion = MutableStateFlow(false)

    override val blurRequestedByShade = MutableStateFlow(0.0f)

    override val scaleRequestedByShade = MutableStateFlow(1.0f)

    override val isBlurSupported: StateFlow<Boolean> =
        conflatedCallbackFlow {
                val sendUpdate =
                    Consumer<Boolean> { value: Boolean ->
                        trySendWithFailureLogging(
                            isBlurAllowed() && value,
                            TAG,
                            "unable to send blur enabled/disable state change",
                        )
                    }
                // Liquid Glass can turn the shade's blur off on its own.
                val glassObserver =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            sendUpdate.accept(crossWindowBlurListeners.isCrossWindowBlurEnabled)
                        }
                    }
                crossWindowBlurListeners.addListener(executor, sendUpdate)
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(RrGlass.QS),
                    false,
                    glassObserver,
                    UserHandle.USER_ALL,
                )
                sendUpdate.accept(crossWindowBlurListeners.isCrossWindowBlurEnabled)

                awaitClose {
                    crossWindowBlurListeners.removeListener(sendUpdate)
                    context.contentResolver.unregisterContentObserver(glassObserver)
                }
            } // stateIn because this is backed by a binder call.
            .stateIn(scope, SharingStarted.Eagerly, false)

    override var blurAppliedListener: BlurAppliedListener? = null

    private fun isBlurAllowed(): Boolean {
        return ActivityManager.isHighEndGfx() && !isDisableBlurSysPropSet() &&
            RrGlass.isOn(RrGlass.QS)
    }

    companion object {
        const val TAG = "WindowRootViewBlurRepository"
    }
}
