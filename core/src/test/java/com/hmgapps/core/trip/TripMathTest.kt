package com.hmgapps.core.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMathTest {
    private val seoul = GeoPt(37.5547, 126.9707)
    private val busan = GeoPt(35.1152, 129.0422)
    private val path: List<GeoPt> = listOf(seoul) +
        listOf("죽전휴게소", "기흥휴게소", "오산휴게소", "안성휴게소", "천안삼거리휴게소", "옥산휴게소", "신탄진휴게소", "금강휴게소", "추풍령휴게소", "칠곡휴게소")
            .map { RestAreaCatalog.byName(it)!! }.map { GeoPt(it.lat, it.lon) } + busan

    @Test fun firstUpdateSetsTotalAndLaterKeepsIt() {
        var t = TripMath.reduce(TripProgress(), TripUpdate("Pleos 내비", "부산역", remainMeters = 400_000, remainSeconds = 16_000), nowMs = 0)
        assertEquals(400_000, t.totalMeters)
        assertEquals(0.0, t.fraction!!, 1e-9)
        t = TripMath.reduce(t, TripUpdate("Pleos 내비", "부산역", remainMeters = 100_000, remainSeconds = 4_000), nowMs = 60_000)
        assertEquals(400_000, t.totalMeters)
        assertEquals(0.75, t.fraction!!, 1e-9)
        assertEquals(1, t.elapsedMinutes(60_000))
    }

    @Test fun newDestinationResetsTrip() {
        var t = TripMath.reduce(TripProgress(), TripUpdate("Pleos 내비", "부산역", remainMeters = 400_000, remainSeconds = 1, lat = 37.2427, lon = 127.0990), 0)
        assertEquals(1, t.stamps.size)
        t = TripMath.reduce(t, TripUpdate("Pleos 내비", "강릉역", remainMeters = 200_000, remainSeconds = 1, lat = 37.5, lon = 127.5), 10)
        assertEquals(200_000, t.totalMeters)
        assertTrue(t.stamps.isEmpty())
    }

    @Test fun waypointIncreasesTotal() {
        var t = TripMath.reduce(TripProgress(), TripUpdate("Pleos 내비", "부산역", remainMeters = 100_000, remainSeconds = 1), 0)
        t = TripMath.reduce(t, TripUpdate("Pleos 내비", "부산역", remainMeters = 120_000, remainSeconds = 1), 1)
        assertEquals(120_000, t.totalMeters)
        assertEquals(0.0, t.fraction!!, 1e-9)
    }

    @Test fun stampOnceWhenPassingRestArea() {
        val a = RestAreaCatalog.byName("안성휴게소")!!
        var t = TripMath.reduce(TripProgress(), TripUpdate("시연 여정", "부산역", remainMeters = 300_000, remainSeconds = 1, totalMeters = 400_000, lat = a.lat + 0.005, lon = a.lon), 0)
        t = TripMath.reduce(t, TripUpdate("시연 여정", "부산역", remainMeters = 299_000, remainSeconds = 1, lat = a.lat, lon = a.lon), 1)
        assertEquals(listOf("안성휴게소"), t.stamps.map { it.name })
        assertEquals(0.25, t.stamps.first().fraction, 1e-9)
    }

    @Test fun aheadAlongRouteSkipsPassedAndSorts() {
        val line = RouteLine(path)
        val here = RestAreaCatalog.byName("오산휴게소")!!
        val t = TripMath.reduce(
            TripProgress(),
            TripUpdate("시연 여정", "부산역", remainMeters = ((line.lengthKm - line.nearest(here.lat, here.lon)!!.first) * 1000).toInt(), remainSeconds = 3 * 3600,
                totalMeters = (line.lengthKm * 1000).toInt(), lat = here.lat, lon = here.lon, route = path),
            0,
        )
        val ahead = TripMath.restAreasAhead(t)
        val names = ahead.map { it.area.name }
        assertFalse("오산은 방금 지남", "오산휴게소" in names)
        assertFalse("기흥은 뒤", "기흥휴게소" in names)
        assertEquals("안성휴게소", names.first())
        assertTrue(ahead.zipWithNext().all { (x, y) -> x.aheadKm <= y.aheadKm })
        assertTrue(ahead.first().aheadMin in 1..60)
    }

    @Test fun aheadByStraightLineForPleos() {
        val t = TripMath.reduce(TripProgress(), TripUpdate("Pleos 내비", "대전역", destLat = 36.3324, destLon = 127.4344,
            remainMeters = 150_000, remainSeconds = 6_000, lat = 37.40, lon = 127.10), 0)
        val names = TripMath.restAreasAhead(t).map { it.area.name }
        assertTrue(names.isNotEmpty())
        assertTrue("안성휴게소" in names)
        assertFalse("칠곡휴게소" in names) // 대전 너머
    }

    @Test fun noTripGivesNearby() {
        val t = TripProgress(lat = 37.40, lon = 127.10)
        val near = TripMath.restAreasAhead(t)
        assertEquals("죽전휴게소", near.first().area.name)
        assertNull(t.fraction)
    }
}
