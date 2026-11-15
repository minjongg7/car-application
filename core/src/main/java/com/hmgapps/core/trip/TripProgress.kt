package com.hmgapps.core.trip

import kotlin.math.max
import kotlin.math.min

data class GeoPt(val lat: Double, val lon: Double)

/** 경로 선(꼭짓점 목록)과 누적 거리. 경로상 휴게소가 '몇 km 앞'인지 계산할 때 쓴다. */
class RouteLine(val points: List<GeoPt>) {
    /** cumKm[i] = points[0]..points[i] 누적 거리(km). */
    val cumKm: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) {
            c[i] = c[i - 1] + GeoMath.haversineKm(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
    }
    val lengthKm: Double get() = if (cumKm.isEmpty()) 0.0 else cumKm.last()

    /** p 에서 가장 가까운 꼭짓점의 (누적 거리, 떨어진 거리) — 둘 다 km. */
    fun nearest(lat: Double, lon: Double, fromKm: Double = Double.NEGATIVE_INFINITY): Pair<Double, Double>? {
        var best: Pair<Double, Double>? = null
        for (i in points.indices) {
            if (cumKm[i] < fromKm) continue
            val d = GeoMath.haversineKm(lat, lon, points[i].lat, points[i].lon)
            if (best == null || d < best.second) best = cumKm[i] to d
        }
        return best
    }
}

/** 지나온 휴게소 스탬프. fraction = 찍힌 지점의 여정 진행률(0~1). */
data class Stamp(val name: String, val fraction: Double)

/**
 * 지금 진행 중인 여정 (내비 → Family Care 공유 상태).
 * 출처: Pleos 내비(NaviHelper onDrivingInfo 의 남은 거리·시간) / 내장 OSM 안내 / Family Care 시연 여정.
 */
data class TripProgress(
    val active: Boolean = false,
    val source: String? = null,
    val destination: String? = null,
    val destLat: Double? = null,
    val destLon: Double? = null,
    val remainMeters: Int? = null,
    val remainSeconds: Int? = null,
    /** 여정 전체 거리(m). 내비가 주지 않으면 처음 받은 남은 거리를 전체로 본다. */
    val totalMeters: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val route: RouteLine? = null,
    val startedAtMs: Long? = null,
    val stamps: List<Stamp> = emptyList(),
    val arrived: Boolean = false,
) {
    /** 0 = 출발, 1 = 도착. 정보가 없으면 null. */
    val fraction: Double?
        get() {
            if (arrived) return 1.0
            val r = remainMeters ?: return null
            val t = totalMeters ?: return null
            if (t <= 0) return null
            return (1.0 - r.toDouble() / t).coerceIn(0.0, 1.0)
        }

    fun elapsedMinutes(nowMs: Long): Int? = startedAtMs?.let { ((nowMs - it) / 60_000L).toInt().coerceAtLeast(0) }
}

/** 내비 쪽에서 보내는 갱신 한 번. */
data class TripUpdate(
    val source: String,
    val destination: String?,
    val destLat: Double? = null,
    val destLon: Double? = null,
    val remainMeters: Int?,
    val remainSeconds: Int?,
    val totalMeters: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val route: List<GeoPt>? = null,
    val arrived: Boolean = false,
)

/** 경로상 휴게소 하나 (지금 위치 기준 몇 km·몇 분 앞). */
data class RestAhead(val area: RestArea, val aheadKm: Double, val aheadMin: Int)

/** 여정 상태 계산 (순수 Kotlin, 단위 테스트 대상). */
object TripMath {
    /** 휴게소 반경 이 안에 들어오면 '지나갔다'고 보고 스탬프를 찍는다. */
    const val STAMP_RADIUS_KM = 1.5
    /** 경로 정보가 없을 때의 평균 속도 가정 (km/h). */
    const val FALLBACK_KMH = 80.0
    /** 직선 → 도로 거리 보정 (내비 RoutePlanner.ROAD_FACTOR 와 같은 값). */
    const val ROAD_FACTOR = 1.25
    /** 경로 선과 이 거리 이내인 휴게소만 '경로상'으로 본다. */
    const val ROUTE_CORRIDOR_KM = 2.0
    const val LINE_CORRIDOR_KM = 8.0

    fun reduce(prev: TripProgress, u: TripUpdate, nowMs: Long, areas: List<RestArea> = RestAreaCatalog.ALL): TripProgress {
        val newTrip = !prev.active || prev.source != u.source || prev.destination != u.destination
        val base = if (newTrip) TripProgress(startedAtMs = nowMs) else prev

        val total = when {
            u.totalMeters != null && u.totalMeters > 0 -> max(u.totalMeters, u.remainMeters ?: 0)
            u.remainMeters == null -> base.totalMeters
            base.totalMeters == null -> u.remainMeters
            else -> max(base.totalMeters, u.remainMeters) // 경유지 추가로 남은 거리가 늘면 전체도 늘린다
        }
        val route = when {
            u.route == null -> if (newTrip) null else base.route
            base.route?.points === u.route -> base.route
            u.route.size >= 2 -> RouteLine(u.route)
            else -> null
        }
        val lat = u.lat ?: base.lat
        val lon = u.lon ?: base.lon
        val remain = if (u.arrived) 0 else u.remainMeters ?: base.remainMeters

        var stamps = base.stamps
        if (lat != null && lon != null) {
            val frac = if (remain != null && total != null && total > 0) (1.0 - remain.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
            areas.forEach { a ->
                if (stamps.none { it.name == a.name } && GeoMath.haversineKm(lat, lon, a.lat, a.lon) <= STAMP_RADIUS_KM) {
                    stamps = stamps + Stamp(a.name, frac)
                }
            }
        }

        return base.copy(
            active = true,
            source = u.source,
            destination = u.destination,
            destLat = u.destLat ?: base.destLat,
            destLon = u.destLon ?: base.destLon,
            remainMeters = remain,
            remainSeconds = if (u.arrived) 0 else u.remainSeconds ?: base.remainSeconds,
            totalMeters = total,
            lat = lat, lon = lon,
            route = route,
            stamps = stamps,
            arrived = u.arrived,
        )
    }

    /**
     * 앞으로 만날 휴게소 (가까운 순).
     * - 경로 선이 있으면(내장 안내·시연) 선을 따라 계산
     * - 없고 목적지 좌표가 있으면(Pleos 내비) 현재 위치→목적지 직선에 투영
     * - 여정이 없으면 반경 40km 안의 가까운 휴게소
     */
    fun restAreasAhead(t: TripProgress, areas: List<RestArea> = RestAreaCatalog.ALL): List<RestAhead> {
        val lat = t.lat ?: return emptyList()
        val lon = t.lon ?: return emptyList()
        val remainKm = t.remainMeters?.div(1000.0)
        val minPerKm = if (remainKm != null && remainKm > 0.5 && t.remainSeconds != null && t.remainSeconds > 0)
            t.remainSeconds / 60.0 / remainKm else 60.0 / FALLBACK_KMH
        val stamped = t.stamps.map { it.name }.toSet()

        val list: List<Pair<RestArea, Double>> = when {
            t.active && t.route != null -> {
                val here = t.route.nearest(lat, lon)?.first ?: 0.0
                areas.mapNotNull { a ->
                    if (a.name in stamped) return@mapNotNull null
                    val (along, off) = t.route.nearest(a.lat, a.lon, fromKm = here - 0.3) ?: return@mapNotNull null
                    if (off > ROUTE_CORRIDOR_KM) null else a to max(0.0, along - here)
                }
            }
            t.active && t.destLat != null && t.destLon != null -> {
                val roadKm = remainKm ?: (GeoMath.haversineKm(lat, lon, t.destLat, t.destLon) * ROAD_FACTOR)
                areas.mapNotNull { a ->
                    if (a.name in stamped) return@mapNotNull null
                    val (tt, off) = project(lat, lon, t.destLat, t.destLon, a.lat, a.lon) ?: return@mapNotNull null
                    if (off > LINE_CORRIDOR_KM || tt !in 0.0..1.0) null else a to tt * roadKm
                }
            }
            else -> areas.mapNotNull { a ->
                val km = GeoMath.haversineKm(lat, lon, a.lat, a.lon) * ROAD_FACTOR
                if (km > 40.0) null else a to km
            }
        }
        return list.filter { remainKm == null || !t.active || it.second <= remainKm + 0.5 }
            .sortedBy { it.second }
            .map { (a, km) -> RestAhead(a, (km * 10).toInt() / 10.0, (km * minPerKm).toInt()) }
    }

    /** 진행률 위치(0~1) — 여정 지도에 앞으로 만날 휴게소를 그릴 때. */
    fun fractionOf(t: TripProgress, aheadKm: Double): Double? {
        val total = t.totalMeters ?: return null
        val remain = t.remainMeters ?: return null
        if (total <= 0) return null
        return min(1.0, (total - remain + aheadKm * 1000.0) / total)
    }

    /** a→b 직선 위로 p 를 투영: (진행률 t, 떨어진 거리 km). 국지 평면 근사. */
    internal fun project(aLat: Double, aLon: Double, bLat: Double, bLon: Double, pLat: Double, pLon: Double): Pair<Double, Double>? {
        val kx = 111.32 * kotlin.math.cos(Math.toRadians((aLat + bLat) / 2))
        val ky = 110.57
        val bx = (bLon - aLon) * kx
        val by = (bLat - aLat) * ky
        val px = (pLon - aLon) * kx
        val py = (pLat - aLat) * ky
        val len2 = bx * bx + by * by
        if (len2 < 1e-9) return null
        val tt = (px * bx + py * by) / len2
        val dx = px - bx * tt
        val dy = py - by * tt
        return tt to kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
