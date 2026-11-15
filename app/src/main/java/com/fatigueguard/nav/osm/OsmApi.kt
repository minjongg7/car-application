package com.fatigueguard.nav.osm

import com.fatigueguard.nav.Poi
import com.fatigueguard.nav.PoiType
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code")

internal object Http {
    /** OSM 계열 공개 서버는 앱을 식별하는 User-Agent 를 요구한다. 제출 전에 연락처를 실제 값으로 바꿀 것. */
    const val USER_AGENT = "FatigueGuardPro/0.4 (vehicle app challenge; contact: your-email@example.com)"

    fun bytes(url: String, timeoutMs: Int = 8_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept-Language", "ko")
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                throw HttpStatusException(code, err)
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    fun text(url: String, timeoutMs: Int = 8_000): String = bytes(url, timeoutMs).toString(Charsets.UTF_8)
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/**
 * OSRM(Open Source Routing Machine) 경로 요청.
 * 기본값은 공개 데모 서버(router.project-osrm.org)라서 시연용이다. 실서비스는 한국 OSM 추출본으로 직접 띄운 서버 주소를 baseUrl 에 넣는다.
 */
object OsrmClient {
    var baseUrl = "https://router.project-osrm.org"

    suspend fun route(points: List<LatLon>): OsmRoute = withContext(Dispatchers.IO) {
        require(points.size >= 2) { "출발지와 목적지가 필요합니다" }
        val coords = points.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val url = "$baseUrl/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true&alternatives=false"
        try {
            parse(Http.text(url, 12_000))
        } catch (e: HttpStatusException) {
            // OSRM 은 NoRoute/NoSegment 를 400 + JSON 으로 돌려준다 → 사람이 읽을 메시지로 바꾼다
            try { parse(e.body) } catch (inner: IOException) { throw inner } catch (_: Exception) { throw e }
            throw e
        }
    }

    fun parse(json: String): OsmRoute {
        val root = JSONObject(json)
        val code = root.optString("code")
        if (code != "Ok") {
            throw IOException(
                when (code) {
                    "NoRoute" -> "두 지점을 잇는 도로 경로를 찾지 못했습니다"
                    "NoSegment" -> "출발/도착 지점 근처에 도로가 없습니다"
                    else -> "경로 서버 응답 오류($code)"
                },
            )
        }
        val r = root.getJSONArray("routes").getJSONObject(0)
        val pts = ArrayList<LatLon>()
        val pending = ArrayList<Array<Any?>>() // [type, modifier, name, dist, dur, startIdx, exit, via]
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).getJSONArray("steps")
            val lastLeg = li == legs.length() - 1
            for (si in 0 until steps.length()) {
                val s = steps.getJSONObject(si)
                val m = s.getJSONObject("maneuver")
                val type = m.optString("type")
                val coords = s.getJSONObject("geometry").getJSONArray("coordinates")
                // 이전 스텝의 마지막 점과 이번 스텝의 첫 점은 같다 → 첫 점은 건너뛴다
                val startIdx = if (pts.isEmpty()) 0 else pts.size - 1
                for (j in 0 until coords.length()) {
                    if (j == 0 && pts.isNotEmpty()) continue
                    val c = coords.getJSONArray(j)
                    pts += LatLon(c.getDouble(1), c.getDouble(0))
                }
                // 두 번째 leg 부터의 depart 는 경유지 출발이라 기동으로 세지 않는다
                if (type == "depart" && li > 0) continue
                pending += arrayOf<Any?>(
                    type,
                    if (m.has("modifier")) m.getString("modifier") else null,
                    s.optString("name"),
                    s.optDouble("distance", 0.0),
                    s.optDouble("duration", 0.0),
                    startIdx,
                    if (m.has("exit")) m.getInt("exit") else null,
                    type == "arrive" && !lastLeg,
                )
            }
        }
        if (pts.size < 2) {
            // steps 가 비어 있는 응답: 전체 geometry 사용
            val g = r.getJSONObject("geometry").getJSONArray("coordinates")
            pts.clear()
            for (j in 0 until g.length()) { val c = g.getJSONArray(j); pts += LatLon(c.getDouble(1), c.getDouble(0)) }
        }
        val cum = Geo.cumulative(pts)
        val steps = pending.map {
            RouteStep(
                type = it[0] as String, modifier = it[1] as String?, name = it[2] as String,
                distanceM = it[3] as Double, durationS = it[4] as Double,
                startAlongM = cum[(it[5] as Int).coerceIn(0, cum.size - 1)],
                exit = it[6] as Int?, via = it[7] as Boolean,
            )
        }
        return OsmRoute(pts, steps, r.getDouble("distance"), r.getDouble("duration"))
    }
}

/** 장소 검색. 입력 중에는 Photon(자동완성 가능), 실패/무결과면 Nominatim 으로 한 번 더 찾는다. */
object Geocoder {
    var photonUrl = "https://photon.komoot.io"
    var nominatimUrl = "https://nominatim.openstreetmap.org"

    suspend fun search(query: String, nearLat: Double, nearLon: Double): List<Poi> = withContext(Dispatchers.IO) {
        val q = query.trim()
        val photon = try {
            val url = "$photonUrl/api/?q=${Http.enc(q)}&limit=8" +
                String.format(Locale.US, "&lat=%.5f&lon=%.5f", nearLat, nearLon)
            parsePhoton(Http.text(url))
        } catch (e: Exception) {
            emptyList()
        }
        if (photon.isNotEmpty()) return@withContext photon
        val url = "$nominatimUrl/search?q=${Http.enc(q)}&format=jsonv2&limit=8&accept-language=ko&countrycodes=kr"
        parseNominatim(Http.text(url))
    }

    fun parsePhoton(json: String): List<Poi> {
        val feats = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Poi>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val p = f.optJSONObject("properties") ?: continue
            val cc = p.optString("countrycode")
            if (cc.isNotEmpty() && cc != "KR") continue
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val note = listOf(p.optString("state"), p.optString("city"), p.optString("district"), street)
                .filter { it.isNotBlank() }.distinct().joinToString(" ")
            val name = p.optString("name").ifBlank { street.ifBlank { note } }
            if (name.isBlank()) continue
            out += Poi(name, PoiType.PLACE, c.getDouble(1), c.getDouble(0), note)
        }
        return out
    }

    fun parseNominatim(json: String): List<Poi> {
        val arr = JSONArray(json)
        val out = ArrayList<Poi>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(",").trim() }
            if (name.isBlank()) continue
            out += Poi(name, PoiType.PLACE, o.getString("lat").toDouble(), o.getString("lon").toDouble(), display.substringAfter(",", "").trim().take(60))
        }
        return out
    }
}
