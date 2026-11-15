package com.hmgapps.care.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CareDomainTest {
    @Test fun normalCdfKnownValues() {
        assertEquals(0.5, FailurePredictor.normalCdf(0.0), 1e-7)
        assertEquals(0.841345, FailurePredictor.normalCdf(1.0), 1e-5)
        assertEquals(0.022750, FailurePredictor.normalCdf(-2.0), 1e-5)
    }

    @Test fun flatSeriesFarFromThresholdIsSafe() {
        val s = SensorSeries(Component.LDC, List(60) { 100.0 + (it % 3) }, threshold = 200.0)
        val f = FailurePredictor.forecast(s)
        assertTrue(f.probability < 0.01)
        assertNull(f.daysToThreshold)
    }

    @Test fun risingSeriesCrossingWithinHorizonIsLikely() {
        // 하루 0.02 증가, 현재 2.0, 임계 3.0 → 50일 뒤 도달 (90일 horizon 안)
        val s = SensorSeries(Component.REDUCER_BEARING, List(100) { 0.0 + 0.02 * it + if (it % 2 == 0) 0.01 else -0.01 }, threshold = 3.0)
        val f = FailurePredictor.forecast(s, 90)
        assertTrue(f.probability > 0.95)
        assertEquals(51.0, f.daysToThreshold!!.toDouble(), 2.0)
    }

    @Test fun lowerIsWorseSignalIsHandled() {
        // 12V 전압이 하루 0.01V 하락, 현재 12.5V, 임계 12.2V → 30일
        val s = SensorSeries(Component.AUX_BATTERY, List(60) { 13.1 - 0.01 * it }, threshold = 12.2)
        val f = FailurePredictor.forecast(s, 90)
        assertTrue(f.probability > 0.95)
        assertTrue(f.worseningPerDay > 0)
    }

    @Test fun gentleDrivingLowersRisk() {
        val h = CareSimulator().history()
        val cur = FailurePredictor.forecastAll(h, 90, DrivePattern.CURRENT).first()
        val gentle = FailurePredictor.forecastAll(h, 90, DrivePattern.GENTLE).first { it.component == cur.component }
        assertEquals(Component.REDUCER_BEARING, cur.component)
        assertTrue(cur.probability > 0.5)
        assertTrue(gentle.probability < cur.probability)
        assertTrue(cur.sentence.contains("3개월 내 감속기 베어링 다운타임 발생 확률"))
    }

    @Test fun healthScoreIsBoundedAndPenalisesBadCells() {
        val good = CareSimulator().frame(0, 80.0)
        val g = HealthScorer.score(good)
        assertTrue(g.score in 70..100)
        val bad = good.copy(sohPercent = 70.0, aux12vVolt = 11.5, ldcRippleMv = 400.0, switchFaults = 5,
            cells = good.cells.mapIndexed { i, c -> if (i == 0) c.copy(voltage = c.voltage - 0.2, tempC = 60.0) else c })
        val b = HealthScorer.score(bad)
        assertTrue(b.score < g.score - 30)
        assertTrue(b.score >= 0)
        assertEquals(Status.ALERT, b.status)
    }
}

class VehicleSignalsTest {
    @Test fun brakeFluidLowsScoreOnlyWhenKnown() {
        val base = HealthScorer.score(CareSimulator().frame(0, 80.0))
        assertEquals(base, HealthScorer.withVehicleSignals(base, null))
        assertEquals(base.score, HealthScorer.withVehicleSignals(base, 0).score)
        assertTrue(HealthScorer.withVehicleSignals(base, 2).score <= base.score - 15)
        assertEquals(base.items.size + 1, HealthScorer.withVehicleSignals(base, 1).items.size)
    }
}
