package com.hmgapps.core.trip

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 휴게소 한 곳. 내비(경로상 정차지)·피로도 가드(TOP3)·Family Care(스탬프·동승자 맞춤 휴식 추천)가 같은 목록을 쓴다.
 *
 * nursingRoom(수유실) / kidsZone(놀이방·키즈존) 은 **시연용 예시 값**이다. 실제 편의시설은
 * 한국도로공사 휴게소 정보(공공데이터포털 '휴게소 편의시설 현황')로 확인해 교체할 것.
 */
data class RestArea(
    val name: String,
    val lat: Double,
    val lon: Double,
    val nursingRoom: Boolean = false,
    val kidsZone: Boolean = false,
    /** 짧은 소개 (TTS 로 읽는다). */
    val note: String = "",
) {
    val amenityText: String
        get() = listOfNotNull(if (nursingRoom) "수유실" else null, if (kidsZone) "놀이방" else null).joinToString(" · ")
}

object RestAreaCatalog {
    /** 경부선 위주 예시 데이터 (좌표 근사값). */
    val ALL: List<RestArea> = listOf(
        RestArea("기흥휴게소", 37.2427, 127.0990, nursingRoom = true, kidsZone = false),
        RestArea("오산휴게소", 37.1480, 127.0780, nursingRoom = true, kidsZone = false),
        RestArea("안성휴게소", 37.0030, 127.2210, nursingRoom = true, kidsZone = true),
        RestArea("망향휴게소", 36.9170, 127.2160, nursingRoom = true, kidsZone = false),
        RestArea("천안삼거리휴게소", 36.8000, 127.1420, nursingRoom = true, kidsZone = false),
        // 경부선 남쪽 구간 (내비 경로 점검용, 좌표 근사값)
        RestArea("죽전휴게소", 37.3330, 127.1100, nursingRoom = true, kidsZone = false),
        RestArea("옥산휴게소", 36.6300, 127.3600, nursingRoom = true, kidsZone = false),
        RestArea("죽암휴게소", 36.5200, 127.4300, nursingRoom = true, kidsZone = true),
        RestArea("신탄진휴게소", 36.4300, 127.4100, nursingRoom = true, kidsZone = false),
        RestArea("금강휴게소", 36.2800, 127.6500, nursingRoom = true, kidsZone = false, note = "금강이 내려다보이는 휴게소예요."),
        RestArea("추풍령휴게소", 36.2100, 127.9800, nursingRoom = true, kidsZone = false),
        RestArea("칠곡휴게소", 35.9700, 128.4200, nursingRoom = true, kidsZone = true),
    )

    fun byName(name: String): RestArea? = ALL.firstOrNull { it.name == name }
}

/** 위경도 거리 (하버사인, km). */
object GeoMath {
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
