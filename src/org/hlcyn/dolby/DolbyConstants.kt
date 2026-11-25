/*
 * Copyright (C) 2023-2024 Paranoid Android
 * Copyright (C) 2024-2026 Halcyon Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hlcyn.dolby

import android.content.Context
import android.util.Log

class DolbyConstants {

    enum class DsParam(val id: Int, val length: Int = 1) {
        HEADPHONE_VIRTUALIZER(101),
        SPEAKER_VIRTUALIZER(102),
        VOLUME_LEVELER_ENABLE(103),
        IEQ_PRESET(104),
        DIALOGUE_ENHANCER_ENABLE(105),
        GRAPHIC_EQUALIZER_ENABLE(106),
        IEQ_AMOUNT(107),
        DIALOGUE_ENHANCER_AMOUNT(108),
        GEQ_BAND_GAINS(110, 20),
        BASS_ENHANCER_ENABLE(111),
        STEREO_WIDENING_AMOUNT(113);

        override fun toString(): String = "${name}(${id})"
    }

    companion object {
        const val TAG = "XiaomiDolby"
        val DEBUG = Log.isLoggable(TAG, Log.DEBUG) || android.os.Build.IS_DEBUGGABLE

        const val PREF_SCHEMA_VERSION = "dolby_pref_schema_version"
        const val PREF_PENDING_HAL_HEAL = "dolby_pending_hal_heal"
        const val CURRENT_SCHEMA_VERSION = 2

        const val PREF_ENABLE = "dolby_enable"
        const val PREF_PROFILE = "dolby_profile"
        const val PREF_PRESET = "dolby_preset"
        const val PREF_IEQ = "dolby_ieq"
        const val PREF_DIALOGUE = "dolby_dialogue"
        const val PREF_BASS = "dolby_bass"
        const val PREF_STEREO = "dolby_stereo"
        const val PREF_VOLUME = "dolby_volume"
        const val PREF_HP_VIRTUALIZER = "dolby_hp_virtualizer"
        const val PREF_SPK_VIRTUALIZER = "dolby_spk_virtualizer"
        const val PREF_RESET = "dolby_reset"

        const val ACTION_SET_DOLBY_STATE = "org.hlcyn.dolby.action.SET_DOLBY_STATE"
        const val ACTION_TOGGLE_DOLBY = "org.hlcyn.dolby.action.TOGGLE_DOLBY"
        const val EXTRA_STATE = "state"

        val PROFILE_SPECIFIC_PREFS = setOf(
            PREF_PRESET,
            PREF_IEQ,
            PREF_HP_VIRTUALIZER,
            PREF_SPK_VIRTUALIZER,
            PREF_STEREO,
            PREF_DIALOGUE,
            PREF_BASS,
            PREF_VOLUME,
        )

        fun getBuiltInProfileIds(context: Context): IntArray =
            context.resources.getStringArray(R.array.dolby_profile_values)
                .mapNotNull { it.toIntOrNull() }
                .toIntArray()

        fun getMinStereoWidening(context: Context): Int =
            context.resources.getStringArray(R.array.dolby_stereo_values)
                .mapNotNull { it.toIntOrNull() }
                .minOrNull() ?: 0

        fun getMaxStereoWidening(context: Context): Int =
            context.resources.getStringArray(R.array.dolby_stereo_values)
                .mapNotNull { it.toIntOrNull() }
                .maxOrNull() ?: 64

        fun getMaxDialogueAmount(context: Context): Int =
            context.resources.getStringArray(R.array.dolby_dialogue_values)
                .mapNotNull { it.toIntOrNull() }
                .maxOrNull() ?: 12

        fun resolveProfileLabel(context: Context, raw: String): String = when (raw) {
            "dynamic" -> context.getString(R.string.dolby_profile_dynamic)
            "video" -> context.getString(R.string.dolby_profile_video)
            "music" -> context.getString(R.string.dolby_profile_music)
            "game" -> context.getString(R.string.dolby_profile_game)
            "voice" -> context.getString(R.string.dolby_profile_voice)
            else -> raw
        }

        fun resolveIeqLabel(context: Context, raw: String): String = when (raw) {
            "off" -> context.getString(R.string.dolby_off)
            "detailed" -> context.getString(R.string.dolby_detailed)
            "balanced" -> context.getString(R.string.dolby_balanced)
            "warm" -> context.getString(R.string.dolby_warm)
            else -> raw
        }

        fun dlog(tag: String, msg: String) {
            if (DEBUG) Log.d(tag, msg)
        }

        fun elog(tag: String, msg: String, tr: Throwable? = null) {
            if (tr != null) {
                Log.e(tag, msg, tr)
            } else {
                Log.e(tag, msg)
            }
        }

        fun wlog(tag: String, msg: String, tr: Throwable? = null) {
            if (tr != null) {
                Log.w(tag, msg, tr)
            } else {
                Log.w(tag, msg)
            }
        }
    }
}
