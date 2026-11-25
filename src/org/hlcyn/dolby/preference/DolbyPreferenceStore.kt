/*
 * Copyright (C) 2023-2024 Paranoid Android
 * Copyright (C) 2024-2026 Halcyon Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hlcyn.dolby.preference

import android.content.Context
import androidx.preference.PreferenceManager
import org.hlcyn.dolby.DolbyConstants
import org.hlcyn.dolby.R
import org.hlcyn.dolby.data.ProfileRepository

class DolbyPreferenceStore(private val context: Context) {

    data class ProfileDefaults(
        val ieqPreset: Int,
        val dialogueEnhancerAmount: Int,
        val bassEnhancerEnabled: Boolean,
        val stereoWideningAmount: Int,
        val volumeLevelerEnabled: Boolean,
        val headphoneVirtEnabled: Boolean,
        val speakerVirtEnabled: Boolean,
    )

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val minStereo = DolbyConstants.getMinStereoWidening(context)
    private val maxStereo = DolbyConstants.getMaxStereoWidening(context)
    private val configuredProfileDefaults: Map<Int, ProfileDefaults> by lazy {
        parseConfiguredProfileDefaults(context)
    }

    init {
        migrateIfNeeded()
    }

    private fun migrateIfNeeded() {
        val currentVersion = sharedPreferences.getInt(DolbyConstants.PREF_SCHEMA_VERSION, 0)
        if (currentVersion >= DolbyConstants.CURRENT_SCHEMA_VERSION) return

        val editor = sharedPreferences.edit()
        val builtInIds = DolbyConstants.getBuiltInProfileIds(context)
        for (builtInId in builtInIds) {
            for (prefKey in DolbyConstants.PROFILE_SPECIFIC_PREFS) {
                editor.remove("${prefKey}_${builtInId}")
            }
        }

        for ((key, rawVal) in sharedPreferences.all) {
            if (key.startsWith("${DolbyConstants.PREF_STEREO}_") && rawVal is Int) {
                val profSuffix = key.removePrefix("${DolbyConstants.PREF_STEREO}_").toIntOrNull()
                if (profSuffix != null && profSuffix >= ProfileRepository.CUSTOM_PROFILE_BASE_ID) {
                    val clamped = rawVal.coerceIn(minStereo, maxStereo)
                    if (clamped != rawVal) {
                        editor.putInt(key, clamped)
                    }
                }
            }
        }

        if (!sharedPreferences.contains(DolbyConstants.PREF_ENABLE)) {
            editor.putBoolean(DolbyConstants.PREF_ENABLE, true)
        }
        editor.putBoolean(DolbyConstants.PREF_PENDING_HAL_HEAL, true)
        editor.putInt(DolbyConstants.PREF_SCHEMA_VERSION, DolbyConstants.CURRENT_SCHEMA_VERSION)
        editor.apply()
    }

    fun consumePendingHalHeal(): Boolean {
        if (!sharedPreferences.getBoolean(DolbyConstants.PREF_PENDING_HAL_HEAL, false)) {
            return false
        }
        sharedPreferences.edit().putBoolean(DolbyConstants.PREF_PENDING_HAL_HEAL, false).apply()
        return true
    }

    var dsOn: Boolean
        get() = sharedPreferences.getBoolean(DolbyConstants.PREF_ENABLE, true)
        set(value) = sharedPreferences.edit().putBoolean(DolbyConstants.PREF_ENABLE, value).apply()

    var profile: Int
        get() = sharedPreferences.getInt(DolbyConstants.PREF_PROFILE, 0)
        set(value) = sharedPreferences.edit().putInt(DolbyConstants.PREF_PROFILE, value).apply()

    var preset: Int
        get() = getProfileIntFor(profile, DolbyConstants.PREF_PRESET, 0)
        set(value) = setProfileInt(DolbyConstants.PREF_PRESET, value)

    var ieqPreset: Int
        get() = getIeqPresetFor(profile)
        set(value) = setProfileInt(DolbyConstants.PREF_IEQ, value)

    var dialogueEnhancerAmount: Int
        get() = getDialogueEnhancerAmountFor(profile)
        set(value) = setProfileInt(DolbyConstants.PREF_DIALOGUE, value)

    var bassEnhancerEnabled: Boolean
        get() = getBassEnhancerEnabledFor(profile)
        set(value) = setProfileBoolean(DolbyConstants.PREF_BASS, value)

    var stereoWideningAmount: Int
        get() = getStereoWideningAmountFor(profile)
        set(value) = setProfileInt(DolbyConstants.PREF_STEREO, value)

    var volumeLevelerEnabled: Boolean
        get() = getVolumeLevelerEnabledFor(profile)
        set(value) = setProfileBoolean(DolbyConstants.PREF_VOLUME, value)

    var headphoneVirtEnabled: Boolean
        get() = getHeadphoneVirtEnabledFor(profile)
        set(value) = setProfileBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, value)

    var speakerVirtEnabled: Boolean
        get() = getSpeakerVirtEnabledFor(profile)
        set(value) = setProfileBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, value)

    fun hasProfilePref(key: String, profileId: Int = profile): Boolean =
        sharedPreferences.contains(getProfileKey(key, profileId))

    fun getConfiguredDefaultsForProfile(profileId: Int): ProfileDefaults? {
        val baseId = ProfileRepository.getInstance(context).getProfile(profileId)?.baseProfileId ?: profileId
        return configuredProfileDefaults[baseId]
    }

    fun getDefaultsForProfile(profileId: Int): ProfileDefaults =
        getConfiguredDefaultsForProfile(profileId) ?: ProfileDefaults(
            ieqPreset = 0,
            dialogueEnhancerAmount = 0,
            bassEnhancerEnabled = false,
            stereoWideningAmount = minStereo,
            volumeLevelerEnabled = false,
            headphoneVirtEnabled = false,
            speakerVirtEnabled = false,
        )

    fun getIeqPresetFor(profileId: Int): Int =
        getProfileIntFor(profileId, DolbyConstants.PREF_IEQ, getDefaultsForProfile(profileId).ieqPreset)

    fun getDialogueEnhancerAmountFor(profileId: Int): Int =
        getProfileIntFor(profileId, DolbyConstants.PREF_DIALOGUE, getDefaultsForProfile(profileId).dialogueEnhancerAmount)

    fun getBassEnhancerEnabledFor(profileId: Int): Boolean =
        getProfileBooleanFor(profileId, DolbyConstants.PREF_BASS, getDefaultsForProfile(profileId).bassEnhancerEnabled)

    fun getStereoWideningAmountFor(profileId: Int): Int =
        getProfileIntFor(profileId, DolbyConstants.PREF_STEREO, getDefaultsForProfile(profileId).stereoWideningAmount)
            .coerceIn(minStereo, maxStereo)

    fun getVolumeLevelerEnabledFor(profileId: Int): Boolean =
        getProfileBooleanFor(profileId, DolbyConstants.PREF_VOLUME, getDefaultsForProfile(profileId).volumeLevelerEnabled)

    fun getHeadphoneVirtEnabledFor(profileId: Int): Boolean =
        getProfileBooleanFor(profileId, DolbyConstants.PREF_HP_VIRTUALIZER, getDefaultsForProfile(profileId).headphoneVirtEnabled)

    fun getSpeakerVirtEnabledFor(profileId: Int): Boolean =
        getProfileBooleanFor(profileId, DolbyConstants.PREF_SPK_VIRTUALIZER, getDefaultsForProfile(profileId).speakerVirtEnabled)

    private fun getProfileKey(key: String, profileId: Int = profile): String = "${key}_${profileId}"

    private fun getProfileIntFor(profileId: Int, key: String, defValue: Int): Int =
        sharedPreferences.getInt(getProfileKey(key, profileId), defValue)

    private fun setProfileInt(key: String, value: Int) =
        sharedPreferences.edit().putInt(getProfileKey(key), value).apply()

    private fun getProfileBooleanFor(profileId: Int, key: String, defValue: Boolean): Boolean =
        sharedPreferences.getBoolean(getProfileKey(key, profileId), defValue)

    private fun setProfileBoolean(key: String, value: Boolean) =
        sharedPreferences.edit().putBoolean(getProfileKey(key), value).apply()

    fun resetProfile(profileId: Int) {
        val editor = sharedPreferences.edit()
        for (prefKey in DolbyConstants.PROFILE_SPECIFIC_PREFS) {
            editor.remove(getProfileKey(prefKey, profileId))
        }
        editor.apply()
    }

    companion object {
        private fun parseConfiguredProfileDefaults(context: Context): Map<Int, ProfileDefaults> {
            val entries = try {
                context.resources.getStringArray(R.array.config_dolby_profile_defaults)
            } catch (e: Exception) {
                emptyArray()
            }
            if (entries.isEmpty()) return emptyMap()
            val result = mutableMapOf<Int, ProfileDefaults>()
            for (entry in entries) {
                val parts = entry.split(":")
                if (parts.size != 2) continue
                val profileId = parts[0].trim().toIntOrNull() ?: continue
                val fields = parts[1].split(",").map { it.trim() }
                if (fields.size < 7) continue
                result[profileId] = ProfileDefaults(
                    ieqPreset = fields[0].toIntOrNull() ?: 0,
                    dialogueEnhancerAmount = fields[1].toIntOrNull() ?: 0,
                    bassEnhancerEnabled = fields[2].toBoolean(),
                    stereoWideningAmount = fields[3].toIntOrNull() ?: 0,
                    volumeLevelerEnabled = fields[4].toBoolean(),
                    headphoneVirtEnabled = fields[5].toBoolean(),
                    speakerVirtEnabled = fields[6].toBoolean(),
                )
            }
            return result
        }
    }
}
