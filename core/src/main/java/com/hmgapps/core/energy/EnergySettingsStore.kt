package com.hmgapps.core.energy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 공용 에너지 설정 (기기 로컬 SharedPreferences). 한 앱에서 바꾸면 다른 앱도 즉시 반영된다. */
object EnergySettingsStore {
    private const val PREFS = "energy_settings"
    private val _settings = MutableStateFlow(EnergySettings())
    val settings: StateFlow<EnergySettings> = _settings.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context): EnergySettings {
        if (!loaded) {
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = EnergySettings()
            _settings.value = EnergySettings(
                lockSocPercent = p.getInt("lock", d.lockSocPercent),
                efficiencyKmPerKwh = p.getFloat("eff", d.efficiencyKmPerKwh.toFloat()).toDouble(),
                commuteKm = p.getFloat("commute", d.commuteKm.toFloat()).toDouble(),
                reservePercent = p.getInt("reserve", d.reservePercent),
            )
            loaded = true
        }
        return _settings.value
    }

    fun save(context: Context, s: EnergySettings) {
        _settings.value = s
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("lock", s.lockSocPercent)
            .putFloat("eff", s.efficiencyKmPerKwh.toFloat())
            .putFloat("commute", s.commuteKm.toFloat())
            .putInt("reserve", s.reservePercent)
            .apply()
    }
}
