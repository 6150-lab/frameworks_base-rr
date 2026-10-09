/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.data.repository

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.shared.settings.data.repository.systemSettingsRepository

val Kosmos.qsStyleRepository by
    Kosmos.Fixture { QsStyleRepository(applicationCoroutineScope, systemSettingsRepository) }
