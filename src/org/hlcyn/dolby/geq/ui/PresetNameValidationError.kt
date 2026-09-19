/*
 * Copyright (C) 2023-2024 Paranoid Android
 * Copyright (C) 2024-2026 Halcyon Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hlcyn.dolby.geq.ui

import androidx.annotation.StringRes
import org.hlcyn.dolby.R

enum class PresetNameValidationError(@param:StringRes val errorMessageRes: Int) {
    ALREADY_EXISTS(R.string.dolby_geq_preset_name_exists),
    TOO_LONG(R.string.dolby_geq_preset_name_too_long)
}
