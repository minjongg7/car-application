package com.fatigueguard.nav.osm

import com.fatigueguard.nav.OnRoute
import com.fatigueguard.nav.Poi
import com.fatigueguard.nav.PoiRepository
import com.fatigueguard.nav.RouteEstimate
import com.fatigueguard.nav.RoutePlanner
import com.hmgapps.core.energy.EnergyMath
import com.hmgapps.core.energy.EnergySettings
import com.hmgapps.core.energy.EnergySnapshot
import kotlin.math.roundToInt

/** 경로 근처로 보는 최대 이탈 거리 (실제 도로 경로 기준이라 직선 추정보다 좁게 잡는다). */
const val ROUTE_CORRIDOR_M = 3_000.0

/** 실제 도로 경로(OSRM)로 거리·시간·도착 배터리·경로상 정차지를 계산한다. 직선 추정 `RoutePlanner.estimate` 의 대체. */
fun RoutePlanner.estimateOnRoute(
    route: OsmRoute,
    energy: EnergySnapshot,
    settings: EnergySettings,
    candidates: List<Poi> = PoiRepository.RESTS + PoiRepository.CHARGERS,
): RouteEstimate {
    val km = route.distanceM / 1000.0
    val usedSoc = if (settings.efficiencyKmPerKwh > 0) km / settings.efficiencyKmPerKwh / energy.capacityKwh * 100 else 0.0
    val total = route.lengthM.coerceAtLeast(1.0)
    val stops = candidates.mapNotNull { poi ->
        val s = Geo.nearest(route, LatLon(poi.lat, poi.lon)) ?: return@mapNotNull null
        val progress = s.alongM / total
        if (s.offM > ROUTE_CORRIDOR_M || progress !in 0.02..0.98) null
        else OnRoute(poi, progress, s.offM / 1000.0, s.alongM / 1000.0)
    }.sortedBy { it.progress }
    return RouteEstimate(
        distanceKm = (km * 10).roundToInt() / 10.0,
        etaMinutes = (route.durationS / 60.0).roundToInt(),
        arrivalSoc = ((energy.socPercent - usedSoc) * 10).roundToInt() / 10.0,
        lockSoc = EnergyMath.effectiveLockSoc(settings, energy.capacityKwh),
        stops = stops,
    )
}
