package com.hmgapps.family.domain

import kotlin.math.ceil
import kotlin.math.roundToInt

/** 여정 지도에서 남은 거리를 세는 단위 (아이 눈높이). */
enum class JourneyToken(val label: String, val emoji: String) {
    APPLE("사과", "🍎"),
    STAR("별", "⭐"),
    CANDY("사탕", "🍬"),
    STRAWBERRY("딸기", "🍓"),
}

/**
 * 1. 여정 진행 지도: 남은 거리·도착 예정 시간(NaviHelper 경로 정보)을 아이가 알아듣는 말로 바꾼다.
 * 예) 전체 길을 사과 10개로 나누고, 남은 길만큼 사과가 남는다 → "할머니 집까지 사과 3개 남았어!"
 */
object JourneyMap {
    const val UNITS = 10
    /** 이 거리 안이면 '거의 다 왔어'. */
    const val NEAR_M = 500

    /** 목적지 별명 후보. 첫 칸(null) = 내비 목적지 이름 그대로. */
    val NICKNAMES: List<String?> = listOf(null, "할머니 집", "할아버지 집", "바다", "캠핑장", "놀이공원")

    /** 남은 토큰 수 (0..UNITS). 정보가 없으면 null. */
    fun unitsLeft(remainM: Int?, totalM: Int?, units: Int = UNITS): Int? {
        val r = remainM ?: return null
        val t = totalM ?: return null
        if (t <= 0) return null
        if (r <= NEAR_M) return 0
        return ceil(r.toDouble() / t * units).toInt().coerceIn(1, units)
    }

    fun placeName(nickname: String?, destination: String?): String =
        nickname ?: destination?.takeIf { it.isNotBlank() } ?: "목적지"

    /** "할머니 집까지 사과 3개 남았어!" */
    fun phrase(place: String, units: Int, token: JourneyToken): String = when (units) {
        0 -> "${place}에 거의 다 왔어! 조금만 기다려 줘."
        else -> "${place}까지 ${token.label} ${units}개 남았어!"
    }

    /** "1시간 10분" / "25분" / "곧" */
    fun durationText(seconds: Int?): String? {
        val s = seconds ?: return null
        val min = (s / 60.0).roundToInt()
        return when {
            min <= 1 -> "곧"
            min < 60 -> "${min}분"
            min % 60 == 0 -> "${min / 60}시간"
            else -> "${min / 60}시간 ${min % 60}분"
        }
    }

    /** 아이용 시간 비유: 20분짜리 만화 몇 편. */
    fun cartoonText(seconds: Int?): String? {
        val s = seconds ?: return null
        val eps = (s / 60.0 / 20.0).roundToInt()
        return if (eps <= 0) null else "만화 ${eps}편 보면 도착해요"
    }

    fun stampPhrase(restName: String, count: Int): String = "${restName} 통과! 스탬프 쾅! 지금까지 스탬프 ${count}개 모았어."
}
