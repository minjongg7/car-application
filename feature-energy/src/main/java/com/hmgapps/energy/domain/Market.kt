package com.hmgapps.energy.domain

import kotlin.math.max
import kotlin.random.Random

/** 계시별 요금 구간. */
enum class TouTier(val label: String) { OFF_PEAK("경부하"), MID("중간부하"), PEAK("최대부하") }

/**
 * 계시별(시간대별) 요금표. 기본값은 **시연용 예시 값**이다 (실제 한전 요금·V2G 정산 단가가 아님).
 * 실서비스에서는 계약 요금표/V2G 사업자 단가로 교체한다.
 */
data class TouSchedule(
    val offPeakWon: Double = 95.0,
    val midWon: Double = 145.0,
    val peakWon: Double = 210.0,
    /** 시(0~23) → 구간. 기본: 22~08 경부하, 11~12·13~17 최대부하, 나머지 중간부하. */
    val tierOfHour: (Int) -> TouTier = { h ->
        when (h) {
            in 22..23, in 0..7 -> TouTier.OFF_PEAK
            11, in 13..16 -> TouTier.PEAK
            else -> TouTier.MID
        }
    },
) {
    fun tier(minuteOfDay: Int): TouTier = tierOfHour((minuteOfDay / 60) % 24)
    fun base(t: TouTier) = when (t) { TouTier.OFF_PEAK -> offPeakWon; TouTier.MID -> midWon; TouTier.PEAK -> peakWon }
}

/** 15분 봉 하나. 가격 단위 원/kWh. */
data class Candle(
    val minuteOfDay: Int,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val tier: TouTier,
)

/**
 * 실시간 전력 가격(예시) 생성기. 계시별 기본가 주변을 평균회귀 랜덤워크로 움직인다.
 * seed 가 같으면 같은 하루가 나온다 (시연·테스트 재현성).
 */
class PriceSimulator(
    private val schedule: TouSchedule = TouSchedule(),
    seed: Int = 7,
    private val intervalMin: Int = 15,
) {
    private val random = Random(seed)
    private var last: Double? = null

    fun next(minuteOfDay: Int): Candle {
        val tier = schedule.tier(minuteOfDay)
        val base = schedule.base(tier)
        var p = last ?: base
        val open = p
        var hi = p
        var lo = p
        repeat(4) {
            p += (base - p) * 0.35 + (random.nextDouble() - 0.5) * base * 0.10
            p = max(10.0, p)
            hi = max(hi, p); lo = minOf(lo, p)
        }
        last = p
        return Candle(minuteOfDay % (24 * 60), open, hi, lo, p, tier)
    }

    val stepMinutes: Int get() = intervalMin
}
