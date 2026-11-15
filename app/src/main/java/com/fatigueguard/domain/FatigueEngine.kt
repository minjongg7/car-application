package com.fatigueguard.domain

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** 점수/레벨 계산 (순수 함수, 단위 테스트 대상). */
object FatigueScoring {
    /** 주행시간(0~60, 180분에 만점) + 속도 편차(0~20) + 급가감속(0~20). */
    fun score(drivingMin: Int, speedStdDev: Float, accelEvents: Int): Int {
        val d = min(60f, drivingMin / 180f * 60f)
        val s = min(20f, speedStdDev * 2f)
        val a = min(20f, accelEvents * 2f)
        return (d + s + a).toInt().coerceIn(0, 100)
    }

    fun level(score: Int): Int = when {
        score < 20 -> 0
        score < 40 -> 1
        score < 60 -> 2
        score < 80 -> 3
        else -> 4
    }
}

/**
 * 텔레메트리 스트림을 받아 최근 [windowMs] 구간의 통계로 피로도를 평가한다.
 * - 정차가 [restResetMs] 이상 지속되면 휴식으로 간주하고 세션을 초기화한다.
 * - 샘플 간격이 [maxGapMs]를 넘으면 급가감속/거리 계산에서 제외한다.
 * 상태를 가지므로 스레드 안전하지 않다 (단일 코루틴에서 호출).
 */
class FatigueEngine(
    private val windowMs: Long = 10 * 60_000L,
    private val restResetMs: Long = 15 * 60_000L,
    private val maxGapMs: Long = 10_000L,
    private val accelThresholdKmhPerSec: Float = 5f,
) {
    private var startMs: Long? = null
    private var stoppedSince: Long? = null
    private var last: TelemetryEvent? = null
    private var distanceKm = 0.0
    private val window = ArrayDeque<TelemetryEvent>()
    private val accelTimes = ArrayDeque<Long>()

    fun update(e: TelemetryEvent): FatigueAssessment {
        if (e.speedKmh < STOP_KMH) {
            val since = stoppedSince ?: e.timestampMs.also { stoppedSince = it }
            if (e.timestampMs - since >= restResetMs) reset()
        } else {
            stoppedSince = null
        }
        if (startMs == null) startMs = e.timestampMs

        last?.let { p ->
            val dtMs = e.timestampMs - p.timestampMs
            if (dtMs in 1..maxGapMs) {
                val dt = dtMs / 1000f
                distanceKm += (p.speedKmh + e.speedKmh) / 2.0 / 3600.0 * dt
                if (abs(e.speedKmh - p.speedKmh) / dt >= accelThresholdKmhPerSec) accelTimes.addLast(e.timestampMs)
            }
        }
        last = e
        window.addLast(e)
        while (window.isNotEmpty() && e.timestampMs - window.first().timestampMs > windowMs) window.removeFirst()
        while (accelTimes.isNotEmpty() && e.timestampMs - accelTimes.first() > windowMs) accelTimes.removeFirst()

        val minutes = ((e.timestampMs - (startMs ?: e.timestampMs)) / 60_000L).toInt()
        val std = speedStdDev()
        val score = FatigueScoring.score(minutes, std, accelTimes.size)
        return FatigueAssessment(e.timestampMs, minutes, distanceKm, std, accelTimes.size, score, FatigueScoring.level(score))
    }

    fun reset() {
        startMs = null; last = null; distanceKm = 0.0
        window.clear(); accelTimes.clear()
    }

    private fun speedStdDev(): Float {
        val v = window.filter { it.speedKmh >= STOP_KMH }.map { it.speedKmh }
        if (v.size < 2) return 0f
        val mean = v.average()
        return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size).toFloat()
    }

    private companion object { const val STOP_KMH = 1f }
}
