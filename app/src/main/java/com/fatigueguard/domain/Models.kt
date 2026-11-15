package com.fatigueguard.domain

/** 차량 텔레메트리 입력 (문서: TelemetryEvent). warmup=true 는 데모용 과거 재생 구간. */
data class TelemetryEvent(
    val timestampMs: Long,
    val speedKmh: Float,
    val lat: Double? = null,
    val lon: Double? = null,
    val warmup: Boolean = false,
)

enum class Urgency { GREEN, YELLOW, RED }

/** 피로도 평가 결과 (level 0~4). */
data class FatigueAssessment(
    val timestampMs: Long,
    val drivingMinutes: Int,
    val distanceKm: Double,
    val speedStdDev: Float,
    val accelEvents: Int,
    val score: Int,
    val level: Int,
) {
    val urgency: Urgency
        get() = when {
            level <= 1 -> Urgency.GREEN
            level == 2 -> Urgency.YELLOW
            else -> Urgency.RED
        }
}

data class RestAreaCandidate(val name: String, val distanceKm: Double, val lat: Double, val lon: Double)

/** 로컬 저장용 세션 로그 (문서: FatigueSession). */
data class FatigueSession(
    val timestamp: String,
    val drivingDurationMin: Int,
    val distanceKm: Double,
    val speedStdDev: Float,
    val accelEvents: Int,
    val fatigueLevel: Int,
    val recommendedRestDistKm: Double?,
    val nextRestAreaCandidates: List<String>,
)

/** 사용자 동의 (문서: UserConsent). */
data class Consent(
    val telemetry: Boolean = true,
    val location: Boolean = true,
    val storingLogs: Boolean = true,
    val tts: Boolean = true,
)

/** 기능 지원 여부 (문서: CapabilitiesCheck). */
data class Capabilities(
    val vehicleSdk: Boolean = false,
    val androidLocation: Boolean = false, // 표준 Android 위치 (권한 + provider 사용 가능)
    val fusedLocation: Boolean = false,   // Pleos FusedLocation SDK (미연동)
    val naviHelper: Boolean = false,
    val tts: Boolean = false,
)

object Messages {
    val byLevel = listOf(
        "컨디션이 좋습니다. 안전운전 하세요.",
        "운전 시간이 늘고 있습니다. 가까운 휴게소에서 쉬어가는 것을 고려하세요.",
        "피로 징후가 있습니다. 가까운 휴게소를 안내합니다.",
        "피로도가 높습니다. 가장 가까운 휴게소에서 쉬세요.",
        "매우 위험합니다. 지금 즉시 안전한 곳에 정차하고 휴식하세요.",
    )
    val levelName = listOf("안정", "양호", "주의", "위험", "즉시 휴식")
}
