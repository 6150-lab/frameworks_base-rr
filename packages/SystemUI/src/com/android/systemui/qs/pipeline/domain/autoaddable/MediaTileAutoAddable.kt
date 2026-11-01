/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.shared.model.QsMediaTileSize
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The Media tile is the only player in QS, so it's added once for those updating with a tile list
 * from before it existed. Removing it after that stays respected.
 */
@SysUISingleton
class MediaTileAutoAddable @Inject constructor() : AutoAddable {
    private val spec = TileSpec.create(QsMediaTileSize.TILE_SPEC)

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> = flowOf(AutoAddSignal.Add(spec))

    override val autoAddTracking = AutoAddTracking.IfNotAdded(spec)

    override val description = "MediaTileAutoAddable ($autoAddTracking)"
}
