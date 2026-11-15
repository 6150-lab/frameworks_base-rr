/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.shared.model.QsVolumeSize
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

abstract class SliderTileAutoAddable(spec: String, private val position: Int) : AutoAddable {
    private val tileSpec = TileSpec.create(spec)

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> =
        flowOf(AutoAddSignal.Add(tileSpec, position))

    override val autoAddTracking = AutoAddTracking.IfNotAdded(tileSpec)

    override val description = "SliderTileAutoAddable ($autoAddTracking)"
}

@SysUISingleton
class BrightnessTileAutoAddable @Inject constructor() :
    SliderTileAutoAddable(QsVolumeSize.BRIGHTNESS_SPEC, 0)

@SysUISingleton
class VolumeTileAutoAddable @Inject constructor() :
    SliderTileAutoAddable(QsVolumeSize.TILE_SPEC, 1)

@SysUISingleton
class SoundTileAutoAddable @Inject constructor() :
    SliderTileAutoAddable(QsVolumeSize.SOUND_SPEC, 2)
