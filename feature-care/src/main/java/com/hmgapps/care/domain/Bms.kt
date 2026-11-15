package com.hmgapps.care.domain

import kotlin.math.max
import kotlin.math.min

/** 셀 하나 (모듈 module, 모듈 안 순번 slot). */
data class Cell(val module: Int, val slot: Int, val voltage: Double, val tempC: Double)

/**
 * BMS 한 프레임.
 * "듀얼 배터리" = 고전압 메인 팩 + 12V 보조 배터리, 둘 사이 전력은 LDC(DC-DC 컨버터)가 전환한다.
 */
data class BmsFrame(
    val timestampMs: Long,
    val cells: List<Cell>,
    val sohPercent: Double,
    val packCurrentA: Double,
    val aux12vVolt: Double,
    /** LDC 출력 리플(mV p-p). 전력 전환 안정성 지표. */
    val ldcRippleMv: Double,
    /** 최근 1시간 메인 릴레이/LDC 전환 이상 횟수. */
    val switchFaults: Int,
) {
    val packVoltage: Double get() = cells.sumOf { it.voltage }
    val cellSpreadMv: Double get() = if (cells.isEmpty()) 0.0 else (cells.maxOf { it.voltage } - cells.minOf { it.voltage }) * 1000
    val tempSpreadC: Double get() = if (cells.isEmpty()) 0.0 else cells.maxOf { it.tempC } - cells.minOf { it.tempC }
    val maxTempC: Double get() = cells.maxOfOrNull { it.tempC } ?: 0.0
}

enum class Status(val label: String) { GOOD("양호"), WATCH("관찰"), ALERT("점검") }

data class HealthItem(val name: String, val value: String, val penalty: Double, val maxPenalty: Double, val status: Status, val note: String)

data class HealthReport(val score: Int, val items: List<HealthItem>) {
    val grade: String get() = when {
        score >= 90 -> "A"; score >= 80 -> "B"; score >= 70 -> "C"; score >= 60 -> "D"; else -> "E"
    }
    val status: Status get() = when { score >= 80 -> Status.GOOD; score >= 65 -> Status.WATCH; else -> Status.ALERT }
}

/**
 * 차량 건강 점수 = 100 − Σ감점. 감점 항목은 상한이 있어 한 항목이 점수를 독식하지 않는다.
 * 기준값은 **초기 가정치**이며 차종/제조사 BMS 사양에 맞춰 조정해야 한다.
 */
object HealthScorer {
    fun score(f: BmsFrame): HealthReport {
        val items = listOf(
            item("배터리 수명(SOH)", "%.1f%%".format(f.sohPercent), (100 - f.sohPercent) * 1.2, 30.0,
                "신품 대비 용량. 80% 미만은 보증 점검 대상인 경우가 많습니다."),
            item("셀 전압 편차", "%.0f mV".format(f.cellSpreadMv), (f.cellSpreadMv - 30) / 2, 20.0,
                "셀 간 최대-최소 전압 차. 커지면 셀 밸런싱 이상 또는 특정 셀 열화."),
            item("셀 온도 편차", "%.1f ℃".format(f.tempSpreadC), (f.tempSpreadC - 5) * 2, 15.0,
                "냉각 불균일 신호. 냉각수 펌프·유로 점검."),
            item("최고 셀 온도", "%.1f ℃".format(f.maxTempC), (f.maxTempC - 45) * 2, 15.0,
                "45℃ 초과 시 열화가 빨라집니다."),
            item("12V 보조 배터리", "%.2f V".format(f.aux12vVolt), aux12vPenalty(f.aux12vVolt), 10.0,
                "12.4V 미만이면 방전·노화, 14.8V 초과면 충전 제어 이상."),
            item("LDC 전력 전환 리플", "%.0f mV".format(f.ldcRippleMv), (f.ldcRippleMv - 150) / 20 + f.switchFaults * 2.0, 10.0,
                "메인 팩↔12V 전력 전환 안정성. 리플·전환 이상이 늘면 LDC/릴레이 점검."),
        )
        val total = items.sumOf { it.penalty }
        return HealthReport(max(0, min(100, (100 - total).toInt())), items)
    }

    /**
     * 차량 실측 신호(Pleos)를 점수에 더한다. brakeFluidSeverity: 0 정상, 1 부족, 2 매우 부족, 3 센서 오류, null 모름.
     * 모르면 항목을 추가하지 않는다 (데모 환경에서 점수가 바뀌지 않도록).
     */
    fun withVehicleSignals(base: HealthReport, brakeFluidSeverity: Int?): HealthReport {
        if (brakeFluidSeverity == null) return base
        val (raw, value, note) = when (brakeFluidSeverity) {
            0 -> Triple(0.0, "정상", "차량 센서 실측 (Pleos Safety).")
            1 -> Triple(9.0, "부족", "브레이크액 보충·누유 점검이 필요합니다.")
            2 -> Triple(15.0, "매우 부족", "제동 성능 저하 위험 — 즉시 점검하세요.")
            else -> Triple(5.0, "센서 오류", "레벨 센서 신호를 읽지 못했습니다.")
        }
        val items = base.items + item("브레이크액 (차량 실측)", value, raw, 15.0, note)
        val total = items.sumOf { it.penalty }
        return HealthReport(max(0, min(100, (100 - total).toInt())), items)
    }

    private fun aux12vPenalty(v: Double): Double = when {
        v < 12.4 -> (12.4 - v) * 20
        v > 14.8 -> (v - 14.8) * 20
        else -> 0.0
    }

    private fun item(name: String, value: String, raw: Double, cap: Double, note: String): HealthItem {
        val p = min(cap, max(0.0, raw))
        val status = when { p <= cap * 0.2 -> Status.GOOD; p <= cap * 0.6 -> Status.WATCH; else -> Status.ALERT }
        return HealthItem(name, value, p, cap, status, note)
    }
}
