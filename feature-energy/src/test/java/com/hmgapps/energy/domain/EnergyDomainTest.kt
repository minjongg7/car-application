package com.hmgapps.energy.domain

import com.hmgapps.core.energy.EnergyMath
import com.hmgapps.core.energy.EnergySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyDomainTest {
    private val cap = 77.4

    @Test fun commuteRaisesLockAboveUserLock() {
        // 200km / 5km/kWh = 40kWh = 51.7% → 52 + 10 여유 = 62 > 30
        assertEquals(62, EnergyMath.effectiveLockSoc(EnergySettings(lockSocPercent = 30, efficiencyKmPerKwh = 5.0, commuteKm = 200.0), cap))
        assertEquals(30, EnergyMath.effectiveLockSoc(EnergySettings(lockSocPercent = 30, commuteKm = 0.0), cap))
    }

    @Test fun lockedStateShedsEverything() {
        val p = V2lPlanner.plan(V2lPlanner.DEFAULT_LOADS, socPercent = 29.0, capacityKwh = cap, lockSoc = 30)
        assertEquals(GuardState.LOCKED, p.state)
        assertTrue(p.active.isEmpty())
        assertEquals(0, p.totalWatts)
        assertNull(p.runtimeMinutes)
    }

    @Test fun lowStateKeepsOnlyEssentials() {
        val p = V2lPlanner.plan(V2lPlanner.DEFAULT_LOADS, socPercent = 33.0, capacityKwh = cap, lockSoc = 30)
        assertEquals(GuardState.LOW, p.state)
        assertTrue(p.active.all { it.priority <= 2 })
        assertEquals(ShedReason.LOW_ENERGY, p.shed["grill"])
    }

    @Test fun capacityLimitShedsLowestPriorityFirst() {
        val p = V2lPlanner.plan(V2lPlanner.DEFAULT_LOADS, socPercent = 80.0, capacityKwh = cap, lockSoc = 30)
        assertEquals(GuardState.OK, p.state)
        assertTrue(p.totalWatts <= V2lPlanner.DEFAULT_MAX_WATTS)
        // 60+120+90+200+1500 = 1970, 그릴 1800 추가 시 3770 > 3600 → 그릴 대기
        assertEquals(1970, p.totalWatts)
        assertEquals(ShedReason.OVER_CAPACITY, p.shed["grill"])
        assertTrue("heater" !in p.shed) // 사용자가 끈 기기는 차단 목록에 없음
        // 사용 가능 = (80-30)% × 77.4 = 38.7kWh → 38.7/1.97kW ≈ 19.6h
        assertEquals(1178, p.runtimeMinutes)
    }

    @Test fun rsiExtremes() {
        val up = List(20) { it.toDouble() }
        assertEquals(100.0, Indicators.rsi(up).last()!!, 1e-9)
        val down = List(20) { -it.toDouble() }
        assertEquals(0.0, Indicators.rsi(down).last()!!, 1e-9)
        assertNull(Indicators.rsi(up)[13])
    }

    @Test fun traderNeverSellsBelowLock() {
        val t = V2gTrader()
        val peak = Candle(14 * 60, 200.0, 220.0, 190.0, 210.0, TouTier.PEAK)
        val d = t.decide(peak, 80.0, socPercent = 32.0, capacityKwh = cap, lockSoc = 30, plugged = true, offPeakBaseWon = 95.0, stepMinutes = 15)
        assertEquals(TradeAction.HOLD, d.action)
        val ok = t.decide(peak, 80.0, socPercent = 70.0, capacityKwh = cap, lockSoc = 30, plugged = true, offPeakBaseWon = 95.0, stepMinutes = 15)
        assertEquals(TradeAction.SELL, ok.action)
        assertEquals(1.75, ok.kWh, 1e-9) // 7kW × 15분
        assertEquals(1.75 * (210.0 - 35.0), ok.pnlWon, 1e-6)
    }

    @Test fun traderChargesCheapAndHoldsWhenUnplugged() {
        val t = V2gTrader()
        val night = Candle(2 * 60, 95.0, 96.0, 88.0, 90.0, TouTier.OFF_PEAK)
        assertEquals(TradeAction.BUY, t.decide(night, 25.0, 50.0, cap, 30, true, 95.0, 15).action)
        assertEquals(TradeAction.HOLD, t.decide(night, 25.0, 50.0, cap, 30, false, 95.0, 15).action)
    }

    @Test fun fullDayIsProfitableAndRespectsLock() {
        val sim = PriceSimulator(seed = 7)
        val t = V2gTrader()
        var soc = 82.0
        var minSoc = soc
        var ledger = V2gLedger()
        val candles = mutableListOf<Candle>()
        var m = 9 * 60
        repeat(96) {
            val c = sim.next(m); candles += c
            val d = t.decide(c, Indicators.rsi(candles.map { it.close }).last(), soc, cap, 30, true, 95.0, 15)
            soc += when (d.action) { TradeAction.SELL -> -d.kWh; TradeAction.BUY -> d.kWh; TradeAction.HOLD -> 0.0 } / cap * 100
            minSoc = minOf(minSoc, soc)
            ledger = t.apply(ledger, d, c.close)
            m = (m + 15) % 1440
        }
        assertTrue(ledger.netWon > 0)
        assertTrue(minSoc >= 30.0)
    }
}
