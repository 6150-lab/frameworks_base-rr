/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr

import com.android.systemui.CoreStartable
import com.android.systemui.edgelight.EdgeLightMomentsController
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Resurrected Remix startables. Listed in ReferenceSysUIComponent. */
@Module
abstract class RrModule {
    @Binds
    @IntoMap
    @ClassKey(EdgeLightMomentsController::class)
    abstract fun bindEdgeLightMoments(impl: EdgeLightMomentsController): CoreStartable
}
