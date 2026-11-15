package com.fatigueguard.nav

import com.fatigueguard.data.RestAreaRepository
import com.hmgapps.core.energy.EnergyMath
import com.hmgapps.core.energy.EnergySettings
import com.hmgapps.core.energy.EnergySnapshot
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class PoiType(val icon: String) { PLACE("📍"), REST("🅿"), CHARGER("⚡") }

data class Poi(val name: String, val type: PoiType, val lat: Double, val lon: Double, val note: String = "")

data class GeoPoint(val lat: Double, val lon: Double)

/** 경로 위(또는 근처) 지점. progress 0 = 출발, 1 = 도착. */
data class OnRoute(val poi: Poi, val progress: Double, val offsetKm: Double, val fromStartKm: Double)

data class RouteEstimate(
    val distanceKm: Double,
    val etaMinutes: Int,
    val arrivalSoc: Double,
    val lockSoc: Int,
    val stops: List<OnRoute>,
) {
    /** 도착 잔량이 방전 락(출근용 보호 잔량)보다 낮음. */
    val belowLock: Boolean get() = arrivalSoc < lockSoc
    /** 도착 전에 배터리가 바닥남. */
    val unreachable: Boolean get() = arrivalSoc <= 0.0
    val restStops get() = stops.filter { it.poi.type == PoiType.REST }
    val chargers get() = stops.filter { it.poi.type == PoiType.CHARGER }
}

/**
 * 오프라인 목적지 데이터 (좌표는 근사값, 시연용). 실서비스에서는 Pleos 내비 검색/POI 로 교체한다.
 * 실제 지도·경로·턴바이턴 안내는 Pleos Navigation 앱이 담당하고, 이 앱은 목적지 선택·사전 점검·경로 요청을 맡는다.
 */
object PoiRepository {
    val PLACES = listOf(
        Poi("서울역", PoiType.PLACE, 37.5547, 126.9707),
        Poi("강남역", PoiType.PLACE, 37.4979, 127.0276),
        Poi("판교역", PoiType.PLACE, 37.3947, 127.1112),
        Poi("수원 화성행궁", PoiType.PLACE, 37.2819, 127.0137),
        Poi("인천국제공항 제1터미널", PoiType.PLACE, 37.4492, 126.4510),
        Poi("세종 정부청사", PoiType.PLACE, 36.5040, 127.2650),
        Poi("대전역", PoiType.PLACE, 36.3324, 127.4344),
        Poi("전주 한옥마을", PoiType.PLACE, 35.8150, 127.1530),
        Poi("대구 동대구역", PoiType.PLACE, 35.8794, 128.6286),
        Poi("부산역", PoiType.PLACE, 35.1152, 129.0422),
        Poi("강릉역", PoiType.PLACE, 37.7637, 128.8996),
    )

    /** 휴게소(피로도 가드와 같은 데이터) + 휴게소 급속충전기(예시). */
    val RESTS = RestAreaRepository.DEFAULT.map { Poi(it.name, PoiType.REST, it.lat, it.lon) }
    val CHARGERS = RestAreaRepository.DEFAULT.map {
        Poi("${it.name} 급속충전", PoiType.CHARGER, it.lat + 0.0008, it.lon + 0.0008, "예시 데이터")
    }

    val ALL = PLACES + RESTS + CHARGERS

    fun search(query: String, limit: Int = 8): List<Poi> {
        val q = query.trim().replace(" ", "")
        if (q.isEmpty()) return PLACES.take(limit)
        return ALL.filter { it.name.replace(" ", "").contains(q, ignoreCase = true) }
            .sortedBy { it.type.ordinal }.take(limit)
    }
}

object RoutePlanner {
    /** 직선거리 → 도로거리 보정 계수 (국내 고속도로 평균 가정치). */
    const val ROAD_FACTOR = 1.25
    const val AVG_SPEED_KMH = 75.0
    /** 경로 근처로 보는 최대 이탈 거리. */
    const val CORRIDOR_KM = 8.0

    fun estimate(from: GeoPoint, to: Poi, energy: EnergySnapshot, settings: EnergySettings, candidates: List<Poi> = PoiRepository.RESTS + PoiRepository.CHARGERS): RouteEstimate {
        val straight = RestAreaRepository.haversineKm(from.lat, from.lon, to.lat, to.lon)
        val road = straight * ROAD_FACTOR
        val usedSoc = if (settings.efficiencyKmPerKwh > 0) road / settings.efficiencyKmPerKwh / energy.capacityKwh * 100 else 0.0
        val lock = EnergyMath.effectiveLockSoc(settings, energy.capacityKwh)
        val stops = candidates.mapNotNull { project(from, GeoPoint(to.lat, to.lon), it, road) }
            .filter { it.offsetKm <= CORRIDOR_KM && it.progress in 0.02..0.98 }
            .sortedBy { it.progress }
        return RouteEstimate(
            distanceKm = (road * 10).roundToInt() / 10.0,
            etaMinutes = (road / AVG_SPEED_KMH * 60).roundToInt(),
            arrivalSoc = ((energy.socPercent - usedSoc) * 10).roundToInt() / 10.0,
            lockSoc = lock,
            stops = stops,
        )
    }

    /** 출발-도착 직선 위로 p 를 투영 (국지 평면 근사). */
    internal fun project(a: GeoPoint, b: GeoPoint, p: Poi, roadKm: Double): OnRoute? {
        val kx = 111.32 * cos(Math.toRadians((a.lat + b.lat) / 2))
        val ky = 110.57
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        if (len2 < 1e-9) return null
        val t = (px * bx + py * by) / len2
        val cx = bx * t
        val cy = by * t
        val off = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
        return OnRoute(p, t, off, max(0.0, t) * roadKm)
    }
}
