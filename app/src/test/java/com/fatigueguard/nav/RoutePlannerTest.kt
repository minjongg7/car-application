package com.fatigueguard.nav

import com.hmgapps.core.energy.EnergySettings
import com.hmgapps.core.energy.EnergySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {
    private val pangyo = GeoPoint(37.40, 127.10)
    private val settings = EnergySettings(lockSocPercent = 30, efficiencyKmPerKwh = 5.5, commuteKm = 0.0)

    @Test fun searchIgnoresSpacesAndFindsChargers() {
        assertEquals("부산역", PoiRepository.search("부산 역").first().name)
        assertTrue(PoiRepository.search("충전").all { it.type == PoiType.CHARGER })
        assertTrue(PoiRepository.search("").isNotEmpty())
    }

    @Test fun busanTripFindsRestStopsInOrderAndWarnsBattery() {
        val busan = PoiRepository.search("부산역").first()
        val e = RoutePlanner.estimate(pangyo, busan, EnergySnapshot(60.0, 77.4), settings)
        assertTrue(e.distanceKm in 380.0..460.0)
        assertTrue(e.restStops.isNotEmpty())
        assertEquals(e.stops.map { it.progress }.sorted(), e.stops.map { it.progress })
        assertTrue(e.unreachable || e.belowLock) // 60% 로는 부산까지 무리
    }

    @Test fun shortTripIsFine() {
        val suwon = PoiRepository.search("화성행궁").first()
        val e = RoutePlanner.estimate(pangyo, suwon, EnergySnapshot(80.0, 77.4), settings)
        assertTrue(e.distanceKm < 30)
        assertFalse(e.belowLock)
        assertTrue(e.etaMinutes in 5..30)
    }
}
