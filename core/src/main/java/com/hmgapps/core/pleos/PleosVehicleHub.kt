package com.hmgapps.core.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Pleos Vehicle SDK 에서 받은 차량 값 (받지 못한 항목은 null). */
data class PleosVehicleState(
    /** Vehicle 서비스에 연결을 시도했고 값이 하나라도 들어왔음. */
    val live: Boolean = false,
    val socPercent: Int? = null,
    val capacityWh: Float? = null,
    val chargePortOpen: Boolean? = null,
    /** 현재 주행 전비 km/kWh. */
    val efficiencyKmPerKwh: Float? = null,
    /** 주행가능거리 km (SDK 는 m 단위). */
    val rangeKm: Float? = null,
    /** 현재 트립 주행시간 (분). */
    val tripDrivingMinutes: Int? = null,
    val gear: VehicleGear? = null,
    val brakeFluid: OilLevelState? = null,
    /** 마지막 오류 (권한·미지원 등). 화면 디버그 표시용. */
    val lastError: String? = null,
)

/**
 * Pleos Vehicle SDK 단일 진입점 (프로세스 공용, 참조 카운트).
 * 내비·Energy Hub·Predictive Care 세 앱이 같은 APK 라서 한 번 연결해 함께 쓴다.
 *
 * 사용 API (모두 normal 권한, 읽기 전용)
 * - EvBattery: registerEvBatteryStateOfCharge(%), getEvBatteryCapacity(Wh), registerEvChargePortOpen, registerCurrentEvEfficiency(km/kWh)
 * - Odometer : registerRangeRemainingListener(m), registerTripCurrentDrivingTime(분)
 * - Gear     : registerGearSelection
 * - Safety   : registerBrakeFluidLevelState
 * 타이어 공기압·경고등(telltale)은 PRIVATE(시스템 전용) 권한이라 쓰지 않는다.
 */
object PleosVehicleHub {
    private const val TAG = "PleosHub"

    private val _state = MutableStateFlow(PleosVehicleState())
    val state: StateFlow<PleosVehicleState> = _state.asStateFlow()

    private var vehicle: Vehicle? = null
    private var refs = 0
    private val cleanups = mutableListOf<() -> Unit>()

    private fun fail(what: String, e: Exception) {
        Log.w(TAG, "$what 실패", e)
        _state.update { it.copy(lastError = "$what: ${e.message ?: e.javaClass.simpleName}") }
    }

    @Synchronized
    fun acquire(context: Context) {
        refs++
        if (vehicle != null) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            val v = Vehicle(context.applicationContext)
            v.initialize()
            vehicle = v
            subscribe(v)
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle SDK 초기화 실패", t)
            _state.update { it.copy(lastError = "초기화: ${t.message}") }
            runCatching { vehicle?.release() }
            vehicle = null
        }
    }

    @Synchronized
    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs > 0) return
        cleanups.forEach { runCatching(it) }
        cleanups.clear()
        runCatching { vehicle?.release() }
        vehicle = null
    }

    private fun mark(change: (PleosVehicleState) -> PleosVehicleState) =
        _state.update { change(it).copy(live = true) }

    private fun subscribe(v: Vehicle) {
        val ev = v.getEvBattery()
        val odo = v.getOdometer()

        runCatching {
            ev.getEvBatteryCapacity(
                onComplete = { wh -> if (wh != null && wh > 0f) mark { it.copy(capacityWh = wh) } },
                onFailed = { fail("배터리 용량", it) },
            )
        }

        val soc = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) { if (value != null) mark { it.copy(socPercent = value) } }
            override fun onFailed(e: Exception) = fail("배터리 SoC", e)
        }
        guard("배터리 SoC") { ev.registerEvBatteryStateOfCharge(soc); cleanups += { ev.unregisterEvBatteryStateOfCharge(soc) } }

        val port = object : EvChargePortOpenListener {
            override fun onEvChargePortOpenUpdated(isOpened: Boolean?) { if (isOpened != null) mark { it.copy(chargePortOpen = isOpened) } }
            override fun onFailed(e: Exception) = fail("충전구", e)
        }
        guard("충전구") { ev.registerEvChargePortOpen(port); cleanups += { ev.unregisterEvChargePortOpen(port) } }

        val eff = object : EvBatteryEfficiencyListener {
            override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) {
                if (efficiency != null && efficiency > 0f) mark { it.copy(efficiencyKmPerKwh = efficiency) }
            }
            override fun onFailed(e: Exception) = fail("전비", e)
        }
        guard("전비") { ev.registerCurrentEvEfficiency(eff); cleanups += { ev.unregisterCurrentEvEfficiency(eff) } }

        val range = object : RangeRemainingListener {
            override fun onRangeRemainingChanged(rangeRemaining: Float?) {
                if (rangeRemaining != null) mark { it.copy(rangeKm = rangeRemaining / 1000f) }
            }
            override fun onFailed(e: Exception) = fail("주행가능거리", e)
        }
        guard("주행가능거리") { odo.registerRangeRemainingListener(range); cleanups += { odo.unregisterRangeRemainingListener(range) } }

        val time = object : DrivingTimeListener {
            override fun onDrivingTimeUpdated(value: Int?) { if (value != null) mark { it.copy(tripDrivingMinutes = value) } }
            override fun onFailed(e: Exception) = fail("주행시간", e)
        }
        guard("주행시간") { odo.registerTripCurrentDrivingTime(time); cleanups += { odo.unregisterTripCurrentDrivingTime(time) } }

        val gearApi = v.getGear()
        val gear = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { if (gear != null) mark { it.copy(gear = gear) } }
            override fun onFailed(e: Exception) = fail("기어", e)
        }
        guard("기어") { gearApi.registerGearSelection(gear); cleanups += { gearApi.unregisterGearSelection(gear) } }

        val safety = v.getSafety()
        val brake = object : OilLevelStateListener {
            override fun onOilLevelUpdated(level: OilLevelState?) { if (level != null) mark { it.copy(brakeFluid = level) } }
            override fun onFailed(e: Exception) = fail("브레이크액", e)
        }
        guard("브레이크액") { safety.registerBrakeFluidLevelState(brake); cleanups += { safety.unregisterBrakeFluidLevelState(brake) } }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (e: Exception) { fail(what, e) }
    }
}

/** 화면 표시용 한글 이름. */
fun VehicleGear.label(): String = when (this) {
    VehicleGear.GEAR_PARK -> "P"
    VehicleGear.GEAR_REVERSE -> "R"
    VehicleGear.GEAR_NEUTRAL -> "N"
    VehicleGear.GEAR_DRIVE -> "D"
    VehicleGear.GEAR_UNKNOWN -> "-"
    else -> name.removePrefix("GEAR_")
}

fun OilLevelState.label(): String = when (this) {
    OilLevelState.NORMAL -> "정상"
    OilLevelState.HIGH -> "높음"
    OilLevelState.LOW -> "부족"
    OilLevelState.CRITICALLY_LOW -> "매우 부족"
    OilLevelState.ERROR -> "센서 오류"
    else -> name
}
