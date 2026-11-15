package com.fatigueguard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FatigueEngineTest {
    @Test fun levelBoundaries() {
        assertEquals(0, FatigueScoring.level(19)); assertEquals(1, FatigueScoring.level(20))
        assertEquals(1, FatigueScoring.level(39)); assertEquals(2, FatigueScoring.level(40))
        assertEquals(2, FatigueScoring.level(59)); assertEquals(3, FatigueScoring.level(60))
        assertEquals(3, FatigueScoring.level(79)); assertEquals(4, FatigueScoring.level(80))
    }

    @Test fun scoreComponentsAreCapped() {
        assertEquals(100, FatigueScoring.score(999, 99f, 99))
        assertEquals(0, FatigueScoring.score(0, 0f, 0))
    }

    @Test fun steady90MinuteDriveIsLevel1() {
        val e = FatigueEngine()
        var a: FatigueAssessment? = null
        for (i in 0..2700) a = e.update(TelemetryEvent(i * 2000L, 100f))
        assertEquals(90, a!!.drivingMinutes)
        assertEquals(30, a.score)
        assertEquals(1, a.level)
        assertEquals(150.0, a.distanceKm, 0.5)
    }

    @Test fun suddenSpeedChangeCountsAsAccelEvent() {
        val e = FatigueEngine()
        e.update(TelemetryEvent(0, 100f))
        val a = e.update(TelemetryEvent(2000, 130f))
        assertEquals(1, a.accelEvents)
    }

    @Test fun longStopResetsSession() {
        val e = FatigueEngine()
        var t = 0L
        repeat(900) { e.update(TelemetryEvent(t, 100f)); t += 2000 }       // 30분 주행
        var a: FatigueAssessment? = null
        repeat(16 * 30) { a = e.update(TelemetryEvent(t, 0f)); t += 2000 } // 16분 정차
        assertEquals(0, a!!.drivingMinutes)
        assertTrue(a!!.level <= 1)
    }

    @Test fun dataGapIsIgnoredForDistanceAndAccel() {
        val e = FatigueEngine()
        e.update(TelemetryEvent(0, 100f))
        val a = e.update(TelemetryEvent(60_000, 20f)) // 1분 공백
        assertEquals(0, a.accelEvents)
        assertEquals(0.0, a.distanceKm, 0.001)
    }
}
