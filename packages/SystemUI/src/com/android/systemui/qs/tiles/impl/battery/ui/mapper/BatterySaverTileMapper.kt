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

package com.android.systemui.qs.tiles.impl.battery.ui.mapper

import android.content.Context
import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.power.ScreenOnTime
import com.android.systemui.rr.battery.BatteryRingDrawable
import com.android.systemui.rr.battery.NeonRingPrefs
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.battery.domain.model.BatterySaverTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

/** Maps [BatterySaverTileModel] to [QSTileState]. */
open class BatterySaverTileMapper
@Inject
constructor(
    @ShadeDisplayAware protected val resources: Resources,
    private val theme: Resources.Theme,
    @Application private val context: Context,
) : QSTileDataToStateMapper<BatterySaverTileModel> {

    override fun map(config: QSTileConfig, data: BatterySaverTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.battery_detail_switch_title)
            contentDescription = label
            val iconRes =
                if (data.isPowerSaving) R.drawable.qs_battery_saver_icon_on
                else R.drawable.qs_battery_saver_icon_off
            icon = Icon.Loaded(resources.getDrawable(iconRes, theme), null, iconRes)
            sideViewIcon = QSTileState.SideViewIcon.None
            var sot = if (ScreenOnTime.isEnabled(context)) ScreenOnTime.label(context) else null
            // RR: the neon ring and the level, like the status bar's.
            if (data.level >= 0 && NeonRingPrefs.tileRing(context)) {
                val size = (24 * resources.displayMetrics.density).toInt()
                icon = Icon.Loaded(BatteryRingDrawable(data.level, data.isPluggedIn, size), null)
                val percent = java.text.NumberFormat.getPercentInstance().format(data.level / 100.0)
                sot = if (sot != null) "$percent \u00b7 $sot" else percent
            }

            if (data.isPluggedIn) {
                activationState = QSTileState.ActivationState.UNAVAILABLE
                supportedActions = setOf(QSTileState.UserAction.LONG_CLICK)
                secondaryLabel = sot ?: ""
            } else if (data.isPowerSaving) {
                activationState = QSTileState.ActivationState.ACTIVE
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)

                if (data is BatterySaverTileModel.Extreme) {
                    secondaryLabel =
                        resources.getString(
                            if (data.isExtremeSaving) R.string.extreme_battery_saver_text
                            else R.string.standard_battery_saver_text
                        )
                    stateDescription = secondaryLabel
                } else {
                    secondaryLabel = sot ?: ""
                }
            } else {
                activationState = QSTileState.ActivationState.INACTIVE
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
                secondaryLabel = sot ?: ""
            }
        }
}
