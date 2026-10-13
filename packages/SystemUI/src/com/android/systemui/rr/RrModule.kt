/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr

import com.android.systemui.CoreStartable
import com.android.systemui.edgelight.EdgeLightMomentsController
import com.android.systemui.rr.chargereport.ChargeReportController
import com.android.systemui.rr.nowbar.NowBarRepository
import com.android.systemui.rr.pie.PieController
import com.android.systemui.rr.quicklaunch.QuickLaunchController
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

    @Binds
    @IntoMap
    @ClassKey(QuickLaunchController::class)
    abstract fun bindQuickLaunch(impl: QuickLaunchController): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(NowBarRepository::class)
    abstract fun bindNowBar(impl: NowBarRepository): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(ChargeReportController::class)
    abstract fun bindChargeReport(impl: ChargeReportController): CoreStartable

    @Binds
    @IntoMap
    @ClassKey(PieController::class)
    abstract fun bindPie(impl: PieController): CoreStartable
}
