package com.hmgapps.care.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** 예측 대상 부품. higherIsWorse=false 면 값이 내려갈수록 나쁜 지표(예: 12V 전압). */
enum class Component(
    val label: String,
    val signal: String,
    val unit: String,
    val higherIsWorse: Boolean,
    val downtime: String,
) {
    REDUCER_BEARING("감속기 베어링", "진동 RMS", "mm/s", true, "감속기 베어링 교체 · 입고 약 1일"),
    AUX_BATTERY("12V 보조 배터리", "휴지 전압", "V", false, "12V 배터리 교체 · 시동(Ready) 불가 위험"),
    COOLANT_PUMP("냉각수 펌프(EWP)", "전류 리플", "%", true, "전동식 워터펌프 교체 · 출력 제한 위험"),
    CELL_BALANCE("셀 밸런싱", "셀 전압 편차", "mV", true, "BMS 진단·셀 밸런싱 · 주행가능거리 감소"),
    LDC("LDC(DC-DC)", "출력 리플", "mV", true, "LDC 점검 · 12V 계통 전원 불안정"),
}

/** 하루 1개 대표값(일 평균 등)으로 만든 시계열. threshold 를 넘으면 정비 필요(다운타임)로 본다. */
data class SensorSeries(val component: Component, val daily: List<Double>, val threshold: Double)

/** 운전 습관이 열화 속도에 주는 배율. */
enum class DrivePattern(val label: String, val factor: Double) {
    CURRENT("현재 주행 패턴 유지", 1.0),
    GENTLE("부드러운 운전(급가감속↓)", 0.6),
    HARSH("가혹 운전(급가감속·고속↑)", 1.4),
}

data class Forecast(
    val component: Component,
    val current: Double,
    val threshold: Double,
    /** 하루 변화량(나빠지는 방향이 +). */
    val worseningPerDay: Double,
    /** horizon 안에 threshold 를 넘을 확률 0~1. */
    val probability: Double,
    /** 추세대로면 몇 일 뒤 threshold 도달 (악화 추세가 아니면 null). */
    val daysToThreshold: Int?,
    val horizonDays: Int,
    val pattern: DrivePattern,
) {
    /** 인포테인먼트 화면용 한 줄 리포트. */
    val sentence: String get() =
        "${pattern.label} 시 ${horizonDays / 30}개월 내 ${component.label} 다운타임 발생 확률 ${(probability * 100).toInt()}%"
}

/**
 * 추세 외삽 기반 고장 확률 (설명 가능한 통계 모델, 학습 데이터 불필요).
 *
 * 1. 일별 값에 최소제곱 직선 y = a + b·t 를 맞춘다.
 * 2. horizon 일 뒤 예측값 μ = ŷ(오늘) + b·f·H  (f = 주행 패턴 배율)
 * 3. 불확실성 σ² = 잔차분산 + (SE(b)·f·H)² + SE(ŷ오늘)²
 * 4. P = P(값 > 임계) = 1 − Φ((임계 − μ)/σ)   (작을수록 나쁜 지표는 부호를 뒤집는다)
 *
 * 열화가 단조 증가한다고 보면 "H 일 시점에 임계 초과" ≈ "H 일 안에 임계 도달" 이다.
 * 실차 데이터가 쌓이면 부품별 생존분석/분류 모델로 바꿀 수 있도록 Forecast 형태만 유지하면 된다.
 */
object FailurePredictor {
    private const val MAX_DAYS = 3650
    fun forecast(s: SensorSeries, horizonDays: Int = 90, pattern: DrivePattern = DrivePattern.CURRENT): Forecast {
        val sign = if (s.component.higherIsWorse) 1.0 else -1.0
        val y = s.daily.map { it * sign }           // "클수록 나쁨" 으로 통일
        val thr = s.threshold * sign
        val n = y.size
        require(n >= 3) { "최소 3일 데이터가 필요합니다" }

        val t = List(n) { it.toDouble() }
        val tMean = t.average()
        val yMean = y.average()
        val sxx = t.sumOf { (it - tMean) * (it - tMean) }
        val b = t.indices.sumOf { (t[it] - tMean) * (y[it] - yMean) } / sxx
        val a = yMean - b * tMean
        val resid = t.indices.map { y[it] - (a + b * t[it]) }
        val s2 = resid.sumOf { it * it } / (n - 2).coerceAtLeast(1)
        val seB = sqrt(s2 / sxx)
        val tNow = t.last()
        val yNow = a + b * tNow
        val seNow = sqrt(s2 * (1.0 / n + (tNow - tMean) * (tNow - tMean) / sxx))

        val f = pattern.factor
        val h = horizonDays.toDouble()
        val mu = yNow + b * f * h
        val sigma = sqrt(s2 + (seB * f * h) * (seB * f * h) + seNow * seNow).coerceAtLeast(1e-9)
        val p = (1.0 - normalCdf((thr - mu) / sigma)).coerceIn(0.0, 1.0)
        // 추세가 통계적으로 유의(기울기 > 2·SE)하고 10년 안일 때만 도달 일수를 보여 준다 (노이즈로 생긴 미세 기울기 배제).
        val significant = b > 2 * seB && b * f > 1e-12
        val days = if (significant) ((thr - yNow) / (b * f)).let { if (it <= 0) 0 else it.toInt() }.takeIf { it <= MAX_DAYS } else null

        return Forecast(s.component, s.daily.last(), s.threshold, b * f, p, days, horizonDays, pattern)
    }

    fun forecastAll(series: List<SensorSeries>, horizonDays: Int = 90, pattern: DrivePattern = DrivePattern.CURRENT) =
        series.map { forecast(it, horizonDays, pattern) }.sortedByDescending { it.probability }

    /** 표준정규 누적분포 (Abramowitz–Stegun 7.1.26, 오차 < 1.5e-7). */
    fun normalCdf(z: Double): Double {
        val x = abs(z) / sqrt(2.0)
        val k = 1.0 / (1.0 + 0.3275911 * x)
        val erf = 1.0 - (((((1.061405429 * k - 1.453152027) * k) + 1.421413741) * k - 0.284496736) * k + 0.254829592) * k * exp(-x * x)
        return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }
}
