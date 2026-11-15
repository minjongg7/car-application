package com.hmgapps.core.energy

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 배터리 상태 스냅샷. 앱 3종(내비/Energy Hub/Predictive Care)이 같은 값을 본다. */
data class EnergySnapshot(
    val socPercent: Double,
    val capacityKwh: Double,
    val chargePortConnected: Boolean = false,
    /** 배터리 기준 순전력(kW). +충전, -방전. 모르면 0. */
    val powerKw: Double = 0.0,
    /** true = 차량(Pleos Vehicle SDK) 실측, false = 데모/시뮬레이션 값. */
    val live: Boolean = false,
    /** 차량이 계산한 주행가능거리 km (Pleos Odometer). 없으면 null → 앱이 SoC×용량×전비로 계산. */
    val vehicleRangeKm: Double? = null,
    /** 차량이 측정한 현재 전비 km/kWh (Pleos EvBattery). 없으면 null → 사용자 설정 전비 사용. */
    val measuredEfficiency: Double? = null,
) {
    val energyKwh: Double get() = capacityKwh * socPercent / 100.0
}

/** 사용자 설정 (공용). 방전 락은 V2L·V2G·내비 도착 경고가 함께 쓴다. */
data class EnergySettings(
    /** 사용자가 직접 정한 최소 잔량(%). */
    val lockSocPercent: Int = 30,
    /** 전비(km/kWh). */
    val efficiencyKmPerKwh: Double = 5.5,
    /** 내일 출근(편도/왕복 합) 거리 km. 0 이면 사용자 락만 사용. */
    val commuteKm: Double = 40.0,
    /** 출근 거리 계산에 더하는 안전 여유(%p). */
    val reservePercent: Int = 10,
)

object EnergyMath {
    /** 실측 전비가 있으면 그것을, 없으면 사용자 설정 전비를 쓴다. */
    fun efficiency(snapshot: EnergySnapshot, s: EnergySettings): Double =
        snapshot.measuredEfficiency?.takeIf { it > 0 } ?: s.efficiencyKmPerKwh

    /** 차량 주행가능거리가 있으면 그것을, 없으면 계산값. */
    fun range(snapshot: EnergySnapshot, s: EnergySettings): Double =
        snapshot.vehicleRangeKm ?: rangeKm(snapshot.socPercent, snapshot.capacityKwh, efficiency(snapshot, s))

    /** 거리 km 를 주행하는 데 필요한 SoC(%). 올림. */
    fun socForDistance(km: Double, efficiencyKmPerKwh: Double, capacityKwh: Double): Int {
        if (km <= 0 || efficiencyKmPerKwh <= 0 || capacityKwh <= 0) return 0
        return ceil(km / efficiencyKmPerKwh / capacityKwh * 100.0).toInt()
    }

    /** 실제 적용되는 락 = max(사용자 락, 출근 필요량 + 여유). 5~95% 로 제한. */
    fun effectiveLockSoc(s: EnergySettings, capacityKwh: Double): Int {
        val commute = if (s.commuteKm > 0)
            socForDistance(s.commuteKm, s.efficiencyKmPerKwh, capacityKwh) + s.reservePercent else 0
        return min(95, max(5, max(s.lockSocPercent, commute)))
    }

    /** 락 위에서 꺼내 쓸 수 있는 에너지(kWh). */
    fun usableKwh(snapshot: EnergySnapshot, lockSoc: Int): Double =
        max(0.0, (snapshot.socPercent - lockSoc) / 100.0 * snapshot.capacityKwh)

    /** 남은 에너지로 갈 수 있는 거리(km). */
    fun rangeKm(socPercent: Double, capacityKwh: Double, efficiencyKmPerKwh: Double): Double =
        max(0.0, socPercent / 100.0 * capacityKwh * efficiencyKmPerKwh)
}
