package com.hmgapps.care.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 데모 데이터 생성기. 셀 단위 BMS·진동 데이터는 표준 AAOS 속성으로 제공되지 않으므로
 * 실차 연동 전까지 이 생성기로 화면과 모델을 시연한다 (실데이터 소스는 같은 형태로 교체).
 *
 * 시나리오: 감속기 베어링 진동이 서서히 증가(3개월 내 임계 도달 가능성 높음),
 * 12V 배터리는 완만한 하락, 나머지는 안정. 모듈 6 의 셀 하나가 약간 낮은 전압·높은 온도.
 */
class CareSimulator(seed: Int = 42) {
    private val r = Random(seed)
    private fun g(): Double {
        val u1 = r.nextDouble().coerceAtLeast(1e-9)
        return sqrt(-2 * ln(u1)) * cos(2 * PI * r.nextDouble())
    }

    companion object {
        const val MODULES = 8
        const val CELLS_PER_MODULE = 12
        const val HISTORY_DAYS = 120
    }

    fun frame(nowMs: Long, socPercent: Double): BmsFrame {
        val base = 3.45 + 0.75 * (socPercent / 100.0)  // SoC 에 따른 대략적 셀 전압
        val wave = sin(nowMs / 9_000.0)
        val cells = buildList {
            for (m in 0 until MODULES) for (c in 0 until CELLS_PER_MODULE) {
                val weak = m == 5 && c == 7
                val v = base + g() * 0.004 + (if (weak) -0.028 else 0.0)
                val temp = 31.0 + m * 0.35 + g() * 0.4 + (if (weak) 3.8 else 0.0) + wave * 0.3
                add(Cell(m, c, v, temp))
            }
        }
        return BmsFrame(
            timestampMs = nowMs,
            cells = cells,
            sohPercent = 93.4,
            packCurrentA = 42 + g() * 6,
            aux12vVolt = 12.52 + g() * 0.03,
            ldcRippleMv = 118 + g() * 12 + wave * 8,
            switchFaults = if (r.nextDouble() < 0.02) 1 else 0,
        )
    }

    fun history(): List<SensorSeries> {
        val d = HISTORY_DAYS
        fun series(start: Double, perDay: Double, noise: Double, accel: Double = 0.0) =
            List(d) { i -> start + perDay * i + accel * i * i + g() * noise }
        return listOf(
            SensorSeries(Component.REDUCER_BEARING, series(1.6, 0.0062, 0.05, accel = 0.00002), threshold = 3.3),
            SensorSeries(Component.AUX_BATTERY, series(12.78, -0.0018, 0.025), threshold = 12.2),
            SensorSeries(Component.COOLANT_PUMP, series(4.1, 0.004, 0.25), threshold = 9.0),
            SensorSeries(Component.CELL_BALANCE, series(21.0, 0.03, 1.2), threshold = 45.0),
            SensorSeries(Component.LDC, series(110.0, 0.05, 6.0), threshold = 220.0),
        )
    }
}
