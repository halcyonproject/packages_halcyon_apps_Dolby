/*
 * Copyright (C) 2023-2024 Paranoid Android
 * Copyright (C) 2024-2026 Halcyon Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hlcyn.dolby

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.hlcyn.dolby.DolbyConstants.Companion.dlog
import org.hlcyn.dolby.DolbyConstants.Companion.elog
import org.hlcyn.dolby.DolbyConstants.Companion.wlog
import org.hlcyn.dolby.DolbyConstants.DsParam
import org.hlcyn.dolby.data.ProfileRepository
import org.hlcyn.dolby.geq.data.EqualizerRepository
import org.hlcyn.dolby.preference.DolbyPreferenceStore

class DolbyController private constructor(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val preferenceStore = DolbyPreferenceStore(context)
    private val profileRepository by lazy { ProfileRepository.getInstance(context) }
    private var dolbyAudioEffect: DolbyAudioEffect? = null
    private val handler = Handler(Looper.getMainLooper())

    private val usePortStereoWidening = context.resources.getBoolean(R.bool.config_dolby_port_stereo_widening)
    private val minIeqAmount = context.resources.getInteger(R.integer.config_dolby_min_ieq_amount)
    private val forceIeqReapply = context.resources.getBoolean(R.bool.config_dolby_force_ieq_reapply)
    private val minStereoWidening = DolbyConstants.getMinStereoWidening(context)
    private val maxStereoWidening = DolbyConstants.getMaxStereoWidening(context)
    private val maxDialogueAmount = DolbyConstants.getMaxDialogueAmount(context)
    private val builtInProfileIds = DolbyConstants.getBuiltInProfileIds(context)

    private val current10BandGains = IntArray(10)
    private val current20BandGains = IntArray(20)

    private var lastAppliedProfileId: Int = -1
    private var lastAppliedBaseProfileId: Int = -1
    private var pendingHalHeal: Boolean = preferenceStore.consumePendingHalHeal()

    private val _dsOnFlow = MutableStateFlow(preferenceStore.dsOn)
    val dsOnFlow: StateFlow<Boolean> = _dsOnFlow.asStateFlow()

    private val _hasControlFlow = MutableStateFlow(true)
    val hasControlFlow: StateFlow<Boolean> = _hasControlFlow.asStateFlow()

    val currentBaseProfileId: Int
        get() = profileRepository.getProfile(preferenceStore.profile)?.baseProfileId ?: preferenceStore.profile

    private var wasPlaying = false
    private val reinitRunnable = Runnable {
        dlog(TAG, "Executing debounced effect reinitialization and state restore")
        reinitEffectAndRestore()
    }

    private fun postReinitEffect() {
        handler.removeCallbacks(reinitRunnable)
        handler.postDelayed(reinitRunnable, 200)
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            updateVirtualizerForRecording(configs.isNotEmpty())
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val isPlaying = configs.any { it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED }
            val effectNeedsStart = preferenceStore.dsOn &&
                (dolbyAudioEffect == null || !dolbyAudioEffect!!.hasControl() || !dolbyAudioEffect!!.enabled)
            if (isPlaying && (!wasPlaying || effectNeedsStart)) {
                dlog(TAG, "Playback active, effectNeedsStart=$effectNeedsStart, ensuring effect is attached and restored")
                if (dolbyAudioEffect == null || !dolbyAudioEffect!!.hasControl()) {
                    reinitEffectAndRestore()
                } else if (effectNeedsStart) {
                    restoreSettings()
                }
            }
            wasPlaying = isPlaying
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            dlog(TAG, "Audio devices added, posting effect reinitialization")
            postReinitEffect()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            dlog(TAG, "Audio devices removed, posting effect reinitialization")
            postReinitEffect()
        }
    }

    init {
        if (profileRepository.getProfile(preferenceStore.profile) == null) {
            preferenceStore.profile = builtInProfileIds.firstOrNull() ?: 0
        }
        initEffect(restore = true)
        audioManager.registerAudioRecordingCallback(recordingCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    }

    fun reinitEffectAndRestore() {
        initEffect(restore = true)
    }

    private fun initEffect(restore: Boolean = false) {
        try {
            dolbyAudioEffect?.release()
        } catch (e: Exception) {
            // Ignore release error
        }
        try {
            dolbyAudioEffect = DolbyAudioEffect(
                EFFECT_PRIORITY,
                AUDIO_SESSION_GLOBAL,
                usePortStereoWidening
            ).apply {
                onControlStatusChanged = { hasControl ->
                    dlog(TAG, "AudioEffect control status updated: $hasControl")
                    _hasControlFlow.value = hasControl
                    if (hasControl && preferenceStore.dsOn) {
                        restoreSettings()
                    }
                }
                onEnableStatusChanged = { enabled ->
                    dlog(TAG, "AudioEffect enable status updated: $enabled")
                    _dsOnFlow.value = enabled
                    preferenceStore.dsOn = enabled
                }
            }
            _hasControlFlow.value = dolbyAudioEffect?.hasControl() ?: false
            dlog(TAG, "DolbyAudioEffect initialized successfully, hasControl=${_hasControlFlow.value}")
            if (restore) {
                restoreSettings()
            }
        } catch (e: Exception) {
            elog(TAG, "Failed to initialize DolbyAudioEffect: ${e.message}", e)
            dolbyAudioEffect = null
            _hasControlFlow.value = false
        }
    }

    private fun checkEffect(): Boolean {
        if (dolbyAudioEffect == null || !dolbyAudioEffect!!.hasControl()) {
            dlog(TAG, "DolbyAudioEffect null or lost control, reinitializing...")
            initEffect(restore = true)
        }
        return dolbyAudioEffect != null
    }

    private fun <T> checkEffectAndRun(block: () -> T): T? {
        if (!checkEffect()) {
            wlog(TAG, "checkEffectAndRun: DolbyAudioEffect unavailable")
            return null
        }
        return try {
            block()
        } catch (e: Exception) {
            elog(TAG, "Error executing effect operation: ${e.message}", e)
            null
        }
    }

    fun onBootCompleted() {
        dlog(TAG, "onBootCompleted")
        restoreSettings()
    }

    fun restoreSettings() {
        if (dolbyAudioEffect == null || !dolbyAudioEffect!!.hasControl()) {
            initEffect(restore = false)
            if (dolbyAudioEffect == null) return
        }
        val effect = dolbyAudioEffect ?: return

        if (pendingHalHeal) {
            dlog(TAG, "Healing built-in HAL profiles in Dolby service")
            for (builtInId in builtInProfileIds) {
                effect.resetProfileSpecificSettings(builtInId)
            }
            pendingHalHeal = false
        }

        val currentProfile = preferenceStore.profile
        val isCustom = profileRepository.isCustomProfile(currentProfile)
        val baseId = currentBaseProfileId

        if (lastAppliedProfileId != -1 && lastAppliedProfileId != currentProfile) {
            val prevWasCustom = profileRepository.isCustomProfile(lastAppliedProfileId)
            if (prevWasCustom && lastAppliedBaseProfileId != -1) {
                effect.resetProfileSpecificSettings(lastAppliedBaseProfileId)
            }
            if (isCustom && baseId != lastAppliedBaseProfileId) {
                effect.resetProfileSpecificSettings(baseId)
            }
        }
        lastAppliedProfileId = currentProfile
        lastAppliedBaseProfileId = baseId

        effect.profile = baseId

        if (!preferenceStore.dsOn) {
            dlog(TAG, "restoreSettings: dsOn is false, disabling DAP effect without mutating profile settings")
            effect.dsOn = false
            loadGeqGainsIntoMemory()
            return
        }

        effect.dsOn = true
        restoreProfileOverridesToHal(effect, currentProfile, baseId, forceAll = isCustom)
    }

    private fun restoreProfileOverridesToHal(
        effect: DolbyAudioEffect,
        profileId: Int,
        baseId: Int,
        forceAll: Boolean
    ) {
        if (forceAll || preferenceStore.hasProfilePref(DolbyConstants.PREF_IEQ, profileId)) {
            applyIeqPresetToHal(effect, preferenceStore.ieqPreset, baseId)
        }
        if (forceAll || preferenceStore.hasProfilePref(DolbyConstants.PREF_DIALOGUE, profileId)) {
            val deAmount = preferenceStore.dialogueEnhancerAmount.coerceIn(0, maxDialogueAmount)
            effect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, deAmount > 0, baseId)
            effect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, deAmount, baseId)
        }
        if (forceAll || preferenceStore.hasProfilePref(DolbyConstants.PREF_BASS, profileId)) {
            effect.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, preferenceStore.bassEnhancerEnabled, baseId)
        }
        if (forceAll || preferenceStore.hasProfilePref(DolbyConstants.PREF_HP_VIRTUALIZER, profileId)) {
            effect.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, preferenceStore.headphoneVirtEnabled, baseId)
        }
        if (forceAll || preferenceStore.hasProfilePref(DolbyConstants.PREF_SPK_VIRTUALIZER, profileId)) {
            effect.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, preferenceStore.speakerVirtEnabled, baseId)
        }
        if (forceAll || preferenceStore.hasProfilePref(DolbyConstants.PREF_STEREO, profileId)) {
            val stereo = preferenceStore.stereoWideningAmount.coerceIn(minStereoWidening, maxStereoWidening)
            effect.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, stereo, baseId)
        }
        if (forceAll || preferenceStore.hasProfilePref(DolbyConstants.PREF_VOLUME, profileId)) {
            effect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, preferenceStore.volumeLevelerEnabled, baseId)
        }

        if (forceAll || preferenceStore.hasProfilePref(DolbyConstants.PREF_PRESET, profileId)) {
            restoreGeqGains()
        } else {
            loadGeqGainsIntoMemory()
        }
    }

    private fun applyIeqPresetToHal(effect: DolbyAudioEffect, preset: Int, baseId: Int) {
        val validPreset = preset.coerceIn(0, 3)
        if (validPreset > 0) {
            if (minIeqAmount > 0) {
                val currentAmt = effect.getDapParameterInt(DsParam.IEQ_AMOUNT, baseId)
                if (currentAmt < minIeqAmount) {
                    effect.setDapParameter(DsParam.IEQ_AMOUNT, minIeqAmount, baseId)
                }
            }
            if (forceIeqReapply && effect.getDapParameterInt(DsParam.IEQ_PRESET, baseId) == validPreset) {
                effect.setDapParameter(DsParam.IEQ_PRESET, 0, baseId)
            }
        }
        effect.setDapParameter(DsParam.IEQ_PRESET, validPreset, baseId)
    }

    private fun updateVirtualizerForRecording(isRecording: Boolean) {
        if (!preferenceStore.dsOn) return
        checkEffectAndRun {
            if (isRecording) {
                dolbyAudioEffect?.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, false, currentBaseProfileId)
                dolbyAudioEffect?.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, false, currentBaseProfileId)
            } else {
                dolbyAudioEffect?.setDapParameter(
                    DsParam.HEADPHONE_VIRTUALIZER,
                    headphoneVirtEnabled,
                    currentBaseProfileId
                )
                dolbyAudioEffect?.setDapParameter(
                    DsParam.SPEAKER_VIRTUALIZER,
                    speakerVirtEnabled,
                    currentBaseProfileId
                )
            }
        }
    }

    var dsOn: Boolean
        get() = preferenceStore.dsOn
        set(value) {
            preferenceStore.dsOn = value
            _dsOnFlow.value = value
            if (value) {
                if (dolbyAudioEffect == null || !dolbyAudioEffect!!.hasControl()) {
                    reinitEffectAndRestore()
                } else {
                    restoreSettings()
                }
            } else {
                checkEffectAndRun {
                    dolbyAudioEffect?.dsOn = false
                }
            }
        }

    var profile: Int
        get() = preferenceStore.profile
        set(value) {
            preferenceStore.profile = value
            restoreSettings()
        }

    var preset: Int
        get() = preferenceStore.preset
        set(value) {
            preferenceStore.preset = value
            restoreGeqGains()
        }

    fun getProfileName(): String? {
        return profileRepository.getProfileName(profile)
    }

    var ieqPreset: Int
        get() {
            if (preferenceStore.hasProfilePref(DolbyConstants.PREF_IEQ) ||
                preferenceStore.getConfiguredDefaultsForProfile(profile) != null
            ) {
                return preferenceStore.ieqPreset
            }
            return dolbyAudioEffect?.getDapParameterInt(DsParam.IEQ_PRESET, currentBaseProfileId)
                ?: preferenceStore.ieqPreset
        }
        set(value) {
            val validPreset = value.coerceIn(0, 3)
            preferenceStore.ieqPreset = validPreset
            checkEffectAndRun {
                dolbyAudioEffect?.let { applyIeqPresetToHal(it, validPreset, currentBaseProfileId) }
            }
        }

    var dialogueEnhancerAmount: Int
        get() {
            if (preferenceStore.hasProfilePref(DolbyConstants.PREF_DIALOGUE) ||
                preferenceStore.getConfiguredDefaultsForProfile(profile) != null
            ) {
                return preferenceStore.dialogueEnhancerAmount
            }
            val effect = dolbyAudioEffect ?: return preferenceStore.dialogueEnhancerAmount
            val enabled = effect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, currentBaseProfileId)
            return if (enabled) {
                effect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, currentBaseProfileId)
            } else {
                0
            }
        }
        set(value) {
            val clamped = value.coerceIn(0, maxDialogueAmount)
            preferenceStore.dialogueEnhancerAmount = clamped
            checkEffectAndRun {
                dolbyAudioEffect?.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, clamped > 0, currentBaseProfileId)
                dolbyAudioEffect?.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, clamped, currentBaseProfileId)
            }
        }

    var bassEnhancerEnabled: Boolean
        get() {
            if (preferenceStore.hasProfilePref(DolbyConstants.PREF_BASS) ||
                preferenceStore.getConfiguredDefaultsForProfile(profile) != null
            ) {
                return preferenceStore.bassEnhancerEnabled
            }
            return dolbyAudioEffect?.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, currentBaseProfileId)
                ?: preferenceStore.bassEnhancerEnabled
        }
        set(value) {
            preferenceStore.bassEnhancerEnabled = value
            checkEffectAndRun {
                dolbyAudioEffect?.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, value, currentBaseProfileId)
            }
        }

    var stereoWideningAmount: Int
        get() {
            if (preferenceStore.hasProfilePref(DolbyConstants.PREF_STEREO) ||
                preferenceStore.getConfiguredDefaultsForProfile(profile) != null
            ) {
                return preferenceStore.stereoWideningAmount
            }
            return dolbyAudioEffect?.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, currentBaseProfileId)
                ?.coerceIn(minStereoWidening, maxStereoWidening)
                ?: preferenceStore.stereoWideningAmount
        }
        set(value) {
            val clamped = value.coerceIn(minStereoWidening, maxStereoWidening)
            preferenceStore.stereoWideningAmount = clamped
            val autoEnableHpVirt = clamped > minStereoWidening && !headphoneVirtEnabled
            if (autoEnableHpVirt) {
                preferenceStore.headphoneVirtEnabled = true
            }
            checkEffectAndRun {
                if (autoEnableHpVirt) {
                    dolbyAudioEffect?.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, true, currentBaseProfileId)
                }
                dolbyAudioEffect?.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, clamped, currentBaseProfileId)
            }
        }

    fun toggleDsOn(): Boolean {
        val newState = !dsOn
        dsOn = newState
        return newState
    }

    var volumeLevelerEnabled: Boolean
        get() {
            if (preferenceStore.hasProfilePref(DolbyConstants.PREF_VOLUME) ||
                preferenceStore.getConfiguredDefaultsForProfile(profile) != null
            ) {
                return preferenceStore.volumeLevelerEnabled
            }
            return dolbyAudioEffect?.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, currentBaseProfileId)
                ?: preferenceStore.volumeLevelerEnabled
        }
        set(value) {
            preferenceStore.volumeLevelerEnabled = value
            checkEffectAndRun {
                dolbyAudioEffect?.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, value, currentBaseProfileId)
            }
        }

    var headphoneVirtEnabled: Boolean
        get() {
            if (preferenceStore.hasProfilePref(DolbyConstants.PREF_HP_VIRTUALIZER) ||
                preferenceStore.getConfiguredDefaultsForProfile(profile) != null
            ) {
                return preferenceStore.headphoneVirtEnabled
            }
            return dolbyAudioEffect?.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, currentBaseProfileId)
                ?: preferenceStore.headphoneVirtEnabled
        }
        set(value) {
            preferenceStore.headphoneVirtEnabled = value
            checkEffectAndRun {
                dolbyAudioEffect?.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, value, currentBaseProfileId)
            }
        }

    var speakerVirtEnabled: Boolean
        get() {
            if (preferenceStore.hasProfilePref(DolbyConstants.PREF_SPK_VIRTUALIZER) ||
                preferenceStore.getConfiguredDefaultsForProfile(profile) != null
            ) {
                return preferenceStore.speakerVirtEnabled
            }
            return dolbyAudioEffect?.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, currentBaseProfileId)
                ?: preferenceStore.speakerVirtEnabled
        }
        set(value) {
            preferenceStore.speakerVirtEnabled = value
            checkEffectAndRun {
                dolbyAudioEffect?.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, value, currentBaseProfileId)
            }
        }

    fun setGeqBandGain(band: Int, gain: Int) {
        if (band in 0..9) {
            preferenceStore.preset = preferenceStore.preset
            current10BandGains[band] = gain.coerceIn(-15, 15)
            syncGeqToDap()
        }
    }

    fun getGeqBandGain(band: Int): Int {
        return if (band in 0..9) current10BandGains[band] else 0
    }

    private fun loadGeqGainsIntoMemory() {
        try {
            val equalizerRepo = EqualizerRepository.getInstance(context)
            val gains = equalizerRepo.getPresetGains(preferenceStore.preset)
            for (i in 0 until 10) {
                current10BandGains[i] = gains.getOrNull(i)?.gain ?: 0
            }
        } catch (e: Exception) {
            dlog(TAG, "Error loading GEQ gains: ${e.message}")
        }
    }

    fun restoreGeqGains() {
        loadGeqGainsIntoMemory()
        syncGeqToDap()
    }

    private fun syncGeqToDap() {
        for (i in 0 until 10) {
            val scaled = current10BandGains[i].coerceIn(-15, 15) * 10
            current20BandGains[2 * i] = scaled
            current20BandGains[2 * i + 1] = scaled
        }
        checkEffectAndRun {
            dolbyAudioEffect?.setDapParameter(DsParam.GRAPHIC_EQUALIZER_ENABLE, true, currentBaseProfileId)
            dolbyAudioEffect?.setDapParameter(DsParam.GEQ_BAND_GAINS, current20BandGains, currentBaseProfileId)
        }
    }

    fun resetCurrentProfile() {
        val prof = profile
        val baseId = currentBaseProfileId
        preferenceStore.resetProfile(prof)
        EqualizerRepository.getInstance(context).resetPresetGains(0)
        loadGeqGainsIntoMemory()
        checkEffectAndRun {
            dolbyAudioEffect?.resetProfileSpecificSettings(baseId)
        }
        if (profileRepository.isCustomProfile(prof)) {
            restoreSettings()
        }
    }

    fun isOnSpeaker(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } &&
                !devices.any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE
                }
    }

    companion object {
        private const val TAG = "DolbyController"
        private const val EFFECT_PRIORITY = 100
        private const val AUDIO_SESSION_GLOBAL = 0

        @Volatile
        private var instance: DolbyController? = null

        fun getInstance(context: Context): DolbyController {
            return instance ?: synchronized(this) {
                instance ?: DolbyController(context.applicationContext).also { instance = it }
            }
        }
    }
}
