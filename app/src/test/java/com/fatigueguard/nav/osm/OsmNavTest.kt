package com.fatigueguard.nav.osm

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OsmNavTest {

    /** 경도 127.0 을 따라 북쪽으로 약 2.2km (위도 37.000 → 37.020). 1km 지점에서 우회전, 끝이 도착. */
    private fun straightRoute(): OsmRoute {
        val pts = (0..20).map { LatLon(37.0 + it * 0.001, 127.0) }
        val cum = Geo.cumulative(pts)
        val steps = listOf(
            RouteStep("depart", null, "출발로", cum[10], 60.0, 0.0),
            RouteStep("turn", "right", "테헤란로", cum[20] - cum[10], 60.0, cum[10]),
            RouteStep("arrive", null, "테헤란로", 0.0, 0.0, cum[20]),
        )
        return OsmRoute(pts, steps, cum[20], 120.0)
    }

    @Test fun parsesOsrmRouteWithSteps() {
        val json = """{"code":"Ok","routes":[{"distance":300.0,"duration":40.0,"legs":[{"steps":[
          {"distance":200.0,"duration":25.0,"name":"A로","maneuver":{"type":"depart","location":[127.0,37.0]},
           "geometry":{"coordinates":[[127.0,37.0],[127.0,37.001],[127.0,37.002]]}},
          {"distance":100.0,"duration":15.0,"name":"B로","maneuver":{"type":"turn","modifier":"left","location":[127.0,37.002]},
           "geometry":{"coordinates":[[127.0,37.002],[126.999,37.002]]}},
          {"distance":0.0,"duration":0.0,"name":"B로","maneuver":{"type":"arrive","location":[126.999,37.002]},
           "geometry":{"coordinates":[[126.999,37.002]]}}]}]}]}"""
        val r = OsrmClient.parse(json)
        assertEquals(4, r.points.size)            // 겹치는 접점은 한 번만
        assertEquals(3, r.steps.size)
        assertEquals("turn", r.steps[1].type)
        assertEquals("left", r.steps[1].modifier)
        assertEquals(r.cum[2], r.steps[1].startAlongM, 0.01)
        assertEquals(r.cum[3], r.steps[2].startAlongM, 0.01)
        assertTrue(r.steps[1].text.contains("좌회전"))
        assertTrue(r.steps[1].text.contains("B로"))
        assertEquals(300.0, r.distanceM, 0.001)
    }

    @Test fun noRouteBecomesReadableError() {
        try {
            OsrmClient.parse("""{"code":"NoRoute","message":"x"}""")
            fail("예외가 나야 한다")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("경로"))
        }
    }

    @Test fun viaLegDoesNotDuplicateDepartAndMarksViaArrive() {
        val json = """{"code":"Ok","routes":[{"distance":200.0,"duration":30.0,"legs":[
          {"steps":[
            {"distance":100.0,"duration":15.0,"name":"A","maneuver":{"type":"depart","location":[127.0,37.0]},"geometry":{"coordinates":[[127.0,37.0],[127.0,37.001]]}},
            {"distance":0.0,"duration":0.0,"name":"A","maneuver":{"type":"arrive","location":[127.0,37.001]},"geometry":{"coordinates":[[127.0,37.001]]}}]},
          {"steps":[
            {"distance":100.0,"duration":15.0,"name":"B","maneuver":{"type":"depart","location":[127.0,37.001]},"geometry":{"coordinates":[[127.0,37.001],[127.0,37.002]]}},
            {"distance":0.0,"duration":0.0,"name":"B","maneuver":{"type":"arrive","location":[127.0,37.002]},"geometry":{"coordinates":[[127.0,37.002]]}}]}]}]}"""
        val r = OsrmClient.parse(json)
        assertEquals(3, r.points.size)
        assertEquals(listOf("depart", "arrive", "arrive"), r.steps.map { it.type })
        assertTrue(r.steps[1].via)
        assertFalse(r.steps[2].via)
    }

    @Test fun snapStaysOnRouteAndMeasuresOffset() {
        val r = straightRoute()
        val on = Geo.snap(r, LatLon(37.0055, 127.0), 0.0)!!
        assertEquals(0.0, on.offM, 0.5)
        assertEquals(Geo.distanceM(r.points[0], LatLon(37.0055, 127.0)), on.alongM, 1.0)
        val off = Geo.snap(r, LatLon(37.0055, 127.002), 0.0)!!   // 동쪽으로 약 178m
        assertEquals(178.0, off.offM, 8.0)
    }

    @Test fun announcesTurnOnceAtThreshold() {
        val r = straightRoute()
        val e = GuidanceEngine(r)
        val far = e.update(LatLon(37.000, 127.0), null)
        assertNull(far.speak)                                   // 1.1km 전: 아직 말하지 않음
        assertEquals("turn", far.nextStep!!.type)
        assertEquals(r.cum[10], far.distToNextM, 2.0)
        val near = e.update(LatLon(37.0075, 127.0), null)       // 약 278m 전
        assertNotNull(near.speak)
        assertTrue(near.speak!!.contains("우회전"))
        assertNull(e.update(LatLon(37.0076, 127.0), null).speak) // 같은 임계값은 다시 말하지 않음
        assertNotNull(e.update(LatLon(37.0093, 127.0), null).speak) // 80m 이내에서 한 번 더
    }

    @Test fun passingTheTurnMovesToArrive() {
        val e = GuidanceEngine(straightRoute())
        val g = e.update(LatLon(37.0105, 127.0), null)           // 회전 지점 통과
        assertEquals("arrive", g.nextStep!!.type)
    }

    @Test fun offRouteNeedsThreeConsecutiveFixes() {
        val e = GuidanceEngine(straightRoute())
        val away = LatLon(37.005, 127.002)
        assertFalse(e.update(away, null).offRoute)
        assertFalse(e.update(away, null).offRoute)
        assertTrue(e.update(away, null).offRoute)
        // 경로로 돌아오면 해제
        assertFalse(e.update(LatLon(37.006, 127.0), null).offRoute)
    }

    @Test fun arrivesAtEnd() {
        val r = straightRoute()
        val e = GuidanceEngine(r)
        e.update(LatLon(37.019, 127.0), null)
        val g = e.update(r.points.last(), null)
        assertTrue(g.arrived)
        assertTrue(g.remainingM < GuidanceEngine.ARRIVE_M)
    }

    @Test fun remainingTimeScalesWithDistance() {
        val r = straightRoute()
        val g = GuidanceEngine(r).update(LatLon(37.010, 127.0), null)
        assertEquals(60.0, g.remainingS, 3.0)   // 총 120초의 절반 지점
    }

    @Test fun simulationPointsFollowRoute() {
        val r = straightRoute()
        val mid = Geo.pointAt(r, r.lengthM / 2)
        assertEquals(37.010, mid.lat, 0.0002)
        assertEquals(0.0, Geo.headingAt(r, 100.0), 0.5)           // 정북
    }

    @Test fun koreanInstructions() {
        assertEquals("우회전", KoreanInstructions.action(RouteStep("turn", "right", "", 0.0, 0.0, 0.0)))
        assertEquals("유턴", KoreanInstructions.action(RouteStep("turn", "uturn", "", 0.0, 0.0, 0.0)))
        assertEquals("회전교차로에서 2번째 출구로 나가세요", KoreanInstructions.action(RouteStep("roundabout", "right", "", 0.0, 0.0, 0.0, exit = 2)))
        assertEquals("갈림길에서 왼쪽으로 가세요", KoreanInstructions.action(RouteStep("fork", "slight left", "", 0.0, 0.0, 0.0)))
        assertEquals("500미터", KoreanInstructions.distWords(512.0))
        assertEquals("1.2킬로미터", KoreanInstructions.distWords(1234.0))
        assertEquals("350 m", KoreanInstructions.distShort(347.0))
    }

    @Test fun webMercatorRoundTripAndFit() {
        val z = 12.0
        val x = WebMercator.worldX(126.978, z)
        val y = WebMercator.worldY(37.5665, z)
        assertEquals(126.978, WebMercator.lon(x, z), 1e-9)
        assertEquals(37.5665, WebMercator.lat(y, z), 1e-9)
        // 서울→부산 전체가 1000x600 화면에 들어가는 줌은 도시 수준(7~9)이어야 한다
        val fit = WebMercator.fitZoom(35.1152, 37.5547, 126.9707, 129.0422, 1000.0, 600.0)
        assertTrue("fit=$fit", fit in 6.0..9.0)
    }

    @Test fun parsesPhotonAndFiltersNonKorea() {
        val json = """{"features":[
          {"geometry":{"coordinates":[127.0276,37.4979]},"properties":{"name":"강남역","city":"서울","state":"서울특별시","countrycode":"KR","street":"강남대로"}},
          {"geometry":{"coordinates":[2.35,48.85]},"properties":{"name":"Gangnam","countrycode":"FR"}}]}"""
        val r = Geocoder.parsePhoton(json)
        assertEquals(1, r.size)
        assertEquals("강남역", r[0].name)
        assertEquals(37.4979, r[0].lat, 1e-6)
        assertEquals(127.0276, r[0].lon, 1e-6)
    }

    @Test fun parsesNominatim() {
        val json = """[{"lat":"35.1152","lon":"129.0422","name":"부산역","display_name":"부산역, 중앙대로, 동구, 부산, 대한민국"}]"""
        val r = Geocoder.parseNominatim(json)
        assertEquals("부산역", r[0].name)
        assertEquals(35.1152, r[0].lat, 1e-6)
    }
}
