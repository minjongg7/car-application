package com.hmgapps.care.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hmgapps.care.domain.BmsFrame
import com.hmgapps.care.domain.CareSimulator
import com.hmgapps.care.domain.Component
import com.hmgapps.care.domain.DrivePattern
import com.hmgapps.care.domain.FailurePredictor
import com.hmgapps.care.domain.Forecast
import com.hmgapps.care.domain.HealthReport
import com.hmgapps.care.domain.HealthScorer
import com.hmgapps.care.domain.SensorSeries
import com.hmgapps.core.energy.VehicleEnergy
import com.hmgapps.core.pleos.PleosVehicleHub
import com.hmgapps.core.pleos.PleosVehicleState
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CareTab { BMS, PREDICT }
enum class HeatMode { VOLTAGE, TEMP }

data class CareUi(
    val tab: CareTab = CareTab.BMS,
    val frame: BmsFrame,
    val health: HealthReport,
    /** 12V/LDC 실시간 추이 (최근 60초). */
    val aux12v: List<Double> = emptyList(),
    val ripple: List<Double> = emptyList(),
    val heat: HeatMode = HeatMode.VOLTAGE,
    val history: List<SensorSeries>,
    val pattern: DrivePattern = DrivePattern.CURRENT,
    val horizonDays: Int = 90,
    val forecasts: List<Forecast>,
    val selected: Component,
    val socLive: Boolean,
    val vehicle: PleosVehicleState = PleosVehicleState(),
)

private fun OilLevelState?.severity(): Int? = when (this) {
    null -> null
    OilLevelState.NORMAL, OilLevelState.HIGH -> 0
    OilLevelState.LOW -> 1
    OilLevelState.CRITICALLY_LOW -> 2
    else -> 3
}

private fun health(f: BmsFrame, v: PleosVehicleState) =
    HealthScorer.withVehicleSignals(HealthScorer.score(f), v.brakeFluid.severity())

class CareViewModel(app: Application) : AndroidViewModel(app) {
    private val sim = CareSimulator()
    private val history = sim.history()

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<CareUi> = _state.asStateFlow()

    private fun initial(): CareUi {
        val f = sim.frame(System.currentTimeMillis(), VehicleEnergy.state.value.socPercent)
        val fc = FailurePredictor.forecastAll(history)
        return CareUi(frame = f, health = HealthScorer.score(f), history = history, forecasts = fc,
            selected = fc.first().component, socLive = VehicleEnergy.state.value.live)
    }

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val e = VehicleEnergy.state.value
                val v = PleosVehicleHub.state.value
                val f = sim.frame(System.currentTimeMillis(), e.socPercent)
                _state.update {
                    it.copy(
                        frame = f, health = health(f, v), socLive = e.live, vehicle = v,
                        aux12v = (it.aux12v + f.aux12vVolt).takeLast(60),
                        ripple = (it.ripple + f.ldcRippleMv).takeLast(60),
                    )
                }
            }
        }
    }

    fun selectTab(t: CareTab) = _state.update { it.copy(tab = t) }
    fun setHeat(m: HeatMode) = _state.update { it.copy(heat = m) }
    fun select(c: Component) = _state.update { it.copy(selected = c) }
    fun setPattern(p: DrivePattern) = recompute { it.copy(pattern = p) }
    fun setHorizon(days: Int) = recompute { it.copy(horizonDays = days) }

    private fun recompute(change: (CareUi) -> CareUi) = _state.update {
        val n = change(it)
        n.copy(forecasts = FailurePredictor.forecastAll(n.history, n.horizonDays, n.pattern))
    }
}
