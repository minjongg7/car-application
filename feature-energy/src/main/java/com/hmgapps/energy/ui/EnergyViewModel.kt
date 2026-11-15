package com.hmgapps.energy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hmgapps.core.energy.EnergyMath
import com.hmgapps.core.energy.EnergySettings
import com.hmgapps.core.energy.EnergySettingsStore
import com.hmgapps.core.energy.EnergySnapshot
import com.hmgapps.core.energy.VehicleEnergy
import com.hmgapps.core.pleos.PleosVehicleHub
import com.hmgapps.core.pleos.PleosVehicleState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.hmgapps.energy.domain.Candle
import com.hmgapps.energy.domain.GuardState
import com.hmgapps.energy.domain.Indicators
import com.hmgapps.energy.domain.PriceSimulator
import com.hmgapps.energy.domain.TouSchedule
import com.hmgapps.energy.domain.TradeAction
import com.hmgapps.energy.domain.TradeDecision
import com.hmgapps.energy.domain.V2gLedger
import com.hmgapps.energy.domain.V2gTrader
import com.hmgapps.energy.domain.V2lLoad
import com.hmgapps.energy.domain.V2lPlan
import com.hmgapps.energy.domain.V2lPlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class EnergyTab { V2L, V2G }

data class V2gState(
    val running: Boolean = false,
    val candles: List<Candle> = emptyList(),
    val rsi: List<Double?> = emptyList(),
    val trades: List<TradeDecision> = emptyList(),
    val ledger: V2gLedger = V2gLedger(),
    val clockMin: Int = START_MINUTE,
) {
    companion object { const val START_MINUTE = 9 * 60 }
}

data class EnergyUi(
    val tab: EnergyTab = EnergyTab.V2L,
    val energy: EnergySnapshot,
    val settings: EnergySettings,
    val lockSoc: Int,
    val loads: List<V2lLoad>,
    val plan: V2lPlan,
    val v2lRunning: Boolean,
    val v2lElapsedMin: Int,
    val v2g: V2gState,
    val notice: String?,
    val vehicle: PleosVehicleState = PleosVehicleState(),
) {
    /** P 단(또는 기어 정보 없음 = 데모)일 때만 V2L/V2G 를 허용한다. */
    val parked: Boolean get() = vehicle.gear == null || vehicle.gear == VehicleGear.GEAR_PARK
    /** 락 계산에 쓰는 전비: Pleos 실측 전비가 있으면 그것. */
    val efficiencyInUse: Double get() = EnergyMath.efficiency(energy, settings)
}

/**
 * Energy Trading Hub 상태.
 * - V2L: 1초 = 1분으로 빠르게 재생한다. 데모 배터리에서는 실제로 잔량을 줄이고, 차량 실측 모드에서는 분배 계획/경보만 계산한다.
 * - V2G: 0.7초마다 15분 봉 하나를 만든다 (모의 시장). 판매/충전량은 데모 배터리에 반영한다.
 */
class EnergyViewModel(app: Application) : AndroidViewModel(app) {
    private val schedule = TouSchedule()
    private val trader = V2gTrader()

    private val tab = MutableStateFlow(EnergyTab.V2L)
    private val loads = MutableStateFlow(V2lPlanner.DEFAULT_LOADS)
    private val v2lRun = MutableStateFlow(false to 0)
    private val v2g = MutableStateFlow(V2gState())
    private val notice = MutableStateFlow<String?>(null)

    init { EnergySettingsStore.load(app) }

    private data class Partial(val tab: EnergyTab, val e: EnergySnapshot, val s: EnergySettings, val loads: List<V2lLoad>, val run: Pair<Boolean, Int>)

    val state: StateFlow<EnergyUi> =
        combine(tab, VehicleEnergy.state, EnergySettingsStore.settings, loads, v2lRun) { t, e, s, l, r -> Partial(t, e, s, l, r) }
            .combine(v2g) { p, g -> p to g }
            .combine(notice) { (p, g), n -> Triple(p, g, n) }
            .combine(PleosVehicleHub.state) { (p, g, n), v -> build(p, g, n).copy(vehicle = v) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, build(
                Partial(EnergyTab.V2L, VehicleEnergy.state.value, EnergySettingsStore.settings.value, loads.value, false to 0),
                v2g.value, null,
            ))

    private fun build(p: Partial, g: V2gState, n: String?): EnergyUi {
        val lock = EnergyMath.effectiveLockSoc(p.s.copy(efficiencyKmPerKwh = EnergyMath.efficiency(p.e, p.s)), p.e.capacityKwh)
        val plan = V2lPlanner.plan(p.loads, p.e.socPercent, p.e.capacityKwh, lock)
        return EnergyUi(p.tab, p.e, p.s, lock, p.loads, plan, p.run.first, p.run.second, g, n)
    }

    fun selectTab(t: EnergyTab) { tab.value = t }

    fun toggleLoad(id: String) = loads.update { l -> l.map { if (it.id == id) it.copy(requested = !it.requested) else it } }

    fun updateSettings(s: EnergySettings) = EnergySettingsStore.save(getApplication(), s)

    fun setDemoSoc(soc: Double) = VehicleEnergy.setDemoSoc(soc)
    fun setPlugged(p: Boolean) = VehicleEnergy.setDemoPlugged(p)
    fun dismissNotice() { notice.value = null }

    // ---------------- V2L ----------------
    private var v2lJob: Job? = null

    fun toggleV2l() { if (v2lJob != null) stopV2l() else startV2l() }

    private fun startV2l() {
        stopV2g()
        v2lRun.value = true to 0
        var lastState = state.value.plan.state
        v2lJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val plan = state.value.plan
                if (!state.value.parked) {
                    notice.value = "주행 중(P단 아님)에는 V2L 을 쓸 수 없어 중지했습니다."
                    stopV2l(); break
                }
                if (plan.totalWatts > 0) {
                    VehicleEnergy.applyDemoEnergy(-plan.totalWatts / 1000.0 / 60.0, -plan.totalWatts / 1000.0)
                }
                v2lRun.update { true to it.second + 1 }
                val now = state.value.plan.state
                if (now != lastState) {
                    notice.value = when (now) {
                        GuardState.LOCKED -> "출근용 전력 보호: 잔량 ${state.value.lockSoc}% 에 도달해 V2L 출력을 모두 차단했습니다." +
                            if (state.value.energy.live) " 차량 V2L 설정의 방전 한도도 ${state.value.lockSoc}% 로 맞춰 두세요." else ""
                        GuardState.LOW -> "락까지 ${V2lPlanner.LOW_MARGIN.toInt()}% 남았습니다. 비필수 기기를 차단해 남은 전력을 아낍니다."
                        GuardState.OK -> null
                    }
                    lastState = now
                }
            }
        }
    }

    private fun stopV2l() {
        v2lJob?.cancel(); v2lJob = null
        v2lRun.update { false to it.second }
        VehicleEnergy.applyDemoEnergy(0.0, 0.0)
    }

    // ---------------- V2G ----------------
    private var v2gJob: Job? = null
    private var prices = PriceSimulator(schedule)

    fun toggleV2g() { if (v2gJob != null) stopV2g() else startV2g() }

    fun resetV2g() {
        stopV2g()
        prices = PriceSimulator(schedule, seed = (System.currentTimeMillis() % 10_000).toInt())
        v2g.value = V2gState()
    }

    private fun startV2g() {
        stopV2l()
        if (!state.value.energy.chargePortConnected) VehicleEnergy.setDemoPlugged(true)
        v2g.update { it.copy(running = true) }
        v2gJob = viewModelScope.launch {
            while (isActive) {
                step()
                delay(700L)
            }
        }
    }

    private fun step() {
        val g = v2g.value
        val c = prices.next(g.clockMin)
        val candles = (g.candles + c).takeLast(96)
        val rsi = Indicators.rsi(candles.map { it.close })
        val s = state.value
        // 실차: 충전구 열림 + P단일 때만 거래. 주행 중이면 대기.
        val d = trader.decide(
            c, rsi.last(), s.energy.socPercent, s.energy.capacityKwh, s.lockSoc,
            s.energy.chargePortConnected && s.parked, schedule.offPeakWon, prices.stepMinutes,
        )
        when (d.action) {
            TradeAction.SELL -> VehicleEnergy.applyDemoEnergy(-d.kWh, -trader.powerKw)
            TradeAction.BUY -> VehicleEnergy.applyDemoEnergy(d.kWh, trader.powerKw)
            TradeAction.HOLD -> VehicleEnergy.applyDemoEnergy(0.0, 0.0)
        }
        v2g.value = g.copy(
            candles = candles, rsi = rsi,
            trades = (g.trades + d).takeLast(96),
            ledger = trader.apply(g.ledger, d, c.close),
            clockMin = (g.clockMin + prices.stepMinutes) % (24 * 60),
        )
    }

    private fun stopV2g() {
        v2gJob?.cancel(); v2gJob = null
        v2g.update { it.copy(running = false) }
    }

    override fun onCleared() { v2lJob?.cancel(); v2gJob?.cancel() }
}
