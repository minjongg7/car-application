package com.fatigueguard.nav.osm

import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/*
 * 내장 내비(OSM 기반)의 순수 Kotlin 코어. 안드로이드 의존성이 없어 JVM 단위 테스트로 검증한다.
 * 경로: OSRM, 지도: OpenStreetMap 타일, 검색: Photon/Nominatim (모두 오픈소스).
 */

data class LatLon(val lat: Double, val lon: Double)

/** OSRM 기동(maneuver) 한 칸. startAlongM = 경로 시작부터 이 기동 지점까지의 누적 거리. */
data class RouteStep(
    val type: String,
    val modifier: String?,
    val name: String,
    val distanceM: Double,
    val durationS: Double,
    val startAlongM: Double,
    val exit: Int? = null,
    /** 경유지 도착(마지막 leg 가 아닌 arrive). */
    val via: Boolean = false,
) {
    val text: String get() = KoreanInstructions.text(this)
}

class OsmRoute(
    val points: List<LatLon>,
    val steps: List<RouteStep>,
    val distanceM: Double,
    val durationS: Double,
) {
    /** cum[i] = points[0]..points[i] 누적 거리(m). */
    val cum: DoubleArray = Geo.cumulative(points)
    val lengthM: Double get() = if (cum.isEmpty()) 0.0 else cum[cum.size - 1]
}

data class Snap(val index: Int, val offM: Double, val alongM: Double, val point: LatLon)

object Geo {
    private const val R = 6_371_000.0
    /** 위도 1도 = R·π/180 (하버사인과 같은 지구 반경을 써서 누적 거리와 어긋나지 않게 한다). */
    private const val M_PER_DEG = R * PI / 180.0

    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    fun bearing(a: LatLon, b: LatLon): Double {
        val la = Math.toRadians(a.lat)
        val lb = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun cumulative(points: List<LatLon>): DoubleArray {
        val c = DoubleArray(points.size)
        for (i in 1 until points.size) c[i] = c[i - 1] + distanceM(points[i - 1], points[i])
        return c
    }

    private fun segIndexAt(route: OsmRoute, alongM: Double): Int {
        var i = java.util.Arrays.binarySearch(route.cum, alongM)
        if (i < 0) i = -i - 2
        return i.coerceIn(0, route.points.size - 2)
    }

    /** 경로 위 누적 거리 alongM 지점의 좌표. */
    fun pointAt(route: OsmRoute, alongM: Double): LatLon {
        val pts = route.points
        if (pts.isEmpty()) return LatLon(0.0, 0.0)
        if (pts.size == 1) return pts[0]
        val a = alongM.coerceIn(0.0, route.lengthM)
        val i = segIndexAt(route, a)
        val seg = route.cum[i + 1] - route.cum[i]
        val t = if (seg < 1e-9) 0.0 else (a - route.cum[i]) / seg
        return LatLon(pts[i].lat + (pts[i + 1].lat - pts[i].lat) * t, pts[i].lon + (pts[i + 1].lon - pts[i].lon) * t)
    }

    fun headingAt(route: OsmRoute, alongM: Double): Double {
        if (route.points.size < 2) return 0.0
        val i = segIndexAt(route, alongM.coerceIn(0.0, route.lengthM))
        return bearing(route.points[i], route.points[i + 1])
    }

    private fun projectOnSegment(route: OsmRoute, i: Int, p: LatLon): Snap {
        val a = route.points[i]
        val b = route.points[i + 1]
        val kx = cos(Math.toRadians(a.lat)) * M_PER_DEG
        val ky = M_PER_DEG
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 < 1e-6) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t
        val dy = py - by * t
        return Snap(
            index = i,
            offM = sqrt(dx * dx + dy * dy),
            alongM = route.cum[i] + t * (route.cum[i + 1] - route.cum[i]),
            point = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t),
        )
    }

    private fun scan(route: OsmRoute, p: LatLon, lo: Double, hi: Double): Snap? {
        var best: Snap? = null
        for (i in 0 until route.points.size - 1) {
            if (route.cum[i + 1] < lo) continue
            if (route.cum[i] > hi) break
            val s = projectOnSegment(route, i, p)
            if (best == null || s.offM < best.offM) best = s
        }
        return best
    }

    /** 경로 전체에서 p 에 가장 가까운 점 (POI 투영용). */
    fun nearest(route: OsmRoute, p: LatLon): Snap? = scan(route, p, 0.0, Double.MAX_VALUE)

    /**
     * 주행 중 위치 스냅. 직전 진행 거리 근처만 먼저 보고(평행 도로·되돌아오는 구간 오인 방지),
     * 120m 넘게 벗어나 있으면 전체에서 다시 찾는다.
     */
    fun snap(route: OsmRoute, p: LatLon, lastAlongM: Double): Snap? {
        if (route.points.size < 2) return null
        val local = scan(route, p, lastAlongM - 300.0, lastAlongM + 3_000.0)
        if (local != null && local.offM <= 120.0) return local
        return nearest(route, p) ?: local
    }
}

object KoreanInstructions {
    private fun dir(m: String?) = when (m) {
        "left" -> "좌회전"
        "right" -> "우회전"
        "slight left" -> "좌측 방향"
        "slight right" -> "우측 방향"
        "sharp left" -> "급좌회전"
        "sharp right" -> "급우회전"
        "uturn" -> "유턴"
        else -> "직진"
    }

    private fun sideGo(m: String?) = when {
        m == null -> "직진하세요"
        m.contains("left") -> "왼쪽으로 가세요"
        m.contains("right") -> "오른쪽으로 가세요"
        else -> "직진하세요"
    }

    fun action(s: RouteStep): String = when (s.type) {
        "depart" -> "경로 안내를 시작합니다"
        "arrive" -> if (s.via) "경유지에 도착합니다" else "목적지에 도착합니다"
        "roundabout", "rotary", "roundabout turn" ->
            if (s.exit != null) "회전교차로에서 ${s.exit}번째 출구로 나가세요" else "회전교차로에서 ${dir(s.modifier)}"
        "exit roundabout", "exit rotary" -> "회전교차로를 빠져나가세요"
        "fork" -> "갈림길에서 ${sideGo(s.modifier)}"
        "on ramp" -> "진입로로 ${sideGo(s.modifier)}"
        "off ramp" -> "출구로 ${sideGo(s.modifier)}"
        "merge" -> "합류하세요"
        "end of road" -> "도로 끝에서 ${dir(s.modifier)}"
        "continue", "new name" -> if (s.modifier == null || s.modifier == "straight") "직진" else dir(s.modifier)
        else -> dir(s.modifier)
    }

    fun text(s: RouteStep): String {
        val a = action(s)
        return if (s.name.isNotBlank() && s.type != "arrive" && s.type != "depart") "$a · ${s.name}" else a
    }

    fun arrow(s: RouteStep?): String = when {
        s == null -> "↑"
        s.type == "arrive" -> "●"
        s.type == "roundabout" || s.type == "rotary" || s.type == "roundabout turn" -> "⟳"
        else -> when (s.modifier) {
            "left" -> "↰"
            "right" -> "↱"
            "slight left" -> "↖"
            "slight right" -> "↗"
            "sharp left" -> "↙"
            "sharp right" -> "↘"
            "uturn" -> "↶"
            else -> "↑"
        }
    }

    /** 음성/화면용 거리. 200m 이상은 50m, 미만은 10m 단위로 반올림. */
    fun distWords(m: Double): String {
        if (m >= 1000) return String.format(Locale.US, "%.1f킬로미터", m / 1000.0)
        val unit = if (m >= 200) 50.0 else 10.0
        return "${((m / unit).roundToInt() * unit).toInt().coerceAtLeast(10)}미터"
    }

    fun distShort(m: Double): String =
        if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000.0) else "${(m / 10.0).roundToInt() * 10} m"
}

data class GuidanceState(
    val active: Boolean = false,
    val position: LatLon? = null,
    val heading: Double? = null,
    val alongM: Double = 0.0,
    val remainingM: Double = 0.0,
    val remainingS: Double = 0.0,
    val nextStep: RouteStep? = null,
    val distToNextM: Double = 0.0,
    val offRoute: Boolean = false,
    val arrived: Boolean = false,
    /** 이번 갱신에서 읽어야 할 음성 안내 (한 번만 나온다). */
    val speak: String? = null,
)

/** 위치가 들어올 때마다 경로 위 진행도·다음 기동·이탈·도착·음성 시점을 계산한다. */
class GuidanceEngine(val route: OsmRoute) {
    companion object {
        const val OFF_ROUTE_M = 50.0
        const val OFF_ROUTE_FIXES = 3
        const val ARRIVE_M = 30.0
        val SPEAK_AT = listOf(800.0, 300.0, 80.0)
    }

    private var along = 0.0
    private var offCount = 0
    private val spoken = HashSet<String>()

    fun update(p: LatLon, heading: Double?): GuidanceState {
        val snap = Geo.snap(route, p, along) ?: return GuidanceState(active = true, position = p, heading = heading)
        if (snap.offM <= OFF_ROUTE_M) {
            along = snap.alongM
            offCount = 0
        } else {
            offCount++
        }
        val total = route.lengthM.coerceAtLeast(1.0)
        val remainingM = max(0.0, total - along)
        val remainingS = route.durationS * remainingM / total
        val arrived = remainingM < ARRIVE_M && snap.offM <= OFF_ROUTE_M * 2

        val next = route.steps.firstOrNull { it.startAlongM > along + 5.0 }
        val distToNext = if (next != null) max(0.0, next.startAlongM - along) else remainingM

        var speak: String? = null
        if (next != null && !arrived) {
            val idx = route.steps.indexOf(next)
            val th = SPEAK_AT.filter { distToNext <= it && "$idx:$it" !in spoken }.minOrNull()
            if (th != null) {
                SPEAK_AT.filter { it >= th }.forEach { spoken += "$idx:$it" }
                speak = if (th <= 80.0) "${next.action()}" else "${KoreanInstructions.distWords(distToNext)} 앞, ${next.action()}"
            }
        }
        return GuidanceState(
            active = true,
            position = if (snap.offM <= OFF_ROUTE_M) snap.point else p,
            heading = heading ?: Geo.headingAt(route, along),
            alongM = along,
            remainingM = remainingM,
            remainingS = remainingS,
            nextStep = next,
            distToNextM = distToNext,
            offRoute = offCount >= OFF_ROUTE_FIXES,
            arrived = arrived,
            speak = speak,
        )
    }

    private fun RouteStep.action() = KoreanInstructions.action(this)
}

/** 웹 메르카토르(OSM 타일) 좌표 계산. */
object WebMercator {
    const val TILE = 256.0

    fun worldX(lon: Double, zoom: Double) = (lon + 180.0) / 360.0 * TILE * 2.0.pow(zoom)

    fun worldY(lat: Double, zoom: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878)))
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * TILE * 2.0.pow(zoom)
    }

    fun lon(worldX: Double, zoom: Double) = worldX / (TILE * 2.0.pow(zoom)) * 360.0 - 180.0

    fun lat(worldY: Double, zoom: Double): Double {
        val n = PI - 2 * PI * worldY / (TILE * 2.0.pow(zoom))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** 경계 상자가 화면(w×h px)에 들어가는 가장 큰 줌. */
    fun fitZoom(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, w: Double, h: Double, pad: Double = 60.0, maxZoom: Double = 16.0): Double {
        val dx = worldX(maxLon, 0.0) - worldX(minLon, 0.0)
        val dy = worldY(minLat, 0.0) - worldY(maxLat, 0.0)
        if (dx < 1e-9 && dy < 1e-9) return maxZoom
        val zx = log2(max(w - 2 * pad, 50.0) / max(dx, 1e-9))
        val zy = log2(max(h - 2 * pad, 50.0) / max(dy, 1e-9))
        return min(zx, zy).coerceIn(3.0, maxZoom)
    }
}
