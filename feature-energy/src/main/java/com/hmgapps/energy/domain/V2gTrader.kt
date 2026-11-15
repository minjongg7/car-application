package com.hmgapps.energy.domain

import kotlin.math.max
import kotlin.math.min

enum class TradeAction(val label: String) { SELL("판매(V2G)"), BUY("충전"), HOLD("대기") }

data class TradeDecision(
    val action: TradeAction,
    /** 이 구간에 이동한 에너지(kWh). SELL 은 차량→그리드, BUY 는 그리드→차량. 항상 0 이상. */
    val kWh: Double,
    /** 이 구간 손익(원). 판매 수익 − 배터리 마모 비용, 충전은 음수(비용). */
    val pnlWon: Double,
    val reason: String,
)

data class V2gLedger(
    val soldKwh: Double = 0.0,
    val boughtKwh: Double = 0.0,
    val revenueWon: Double = 0.0,
    val costWon: Double = 0.0,
    val wearWon: Double = 0.0,
) {
    val netWon: Double get() = revenueWon - costWon - wearWon
}

/**
 * 자동 거래 규칙 (설명 가능한 단순 규칙 기반).
 *
 * - SELL: 충전구 연결 + 잔량이 (락 + 여유) 초과 + (최대부하 구간 또는 RSI ≥ sellRsi)
 *         + 가격이 판매 최소가 이상 (마모 비용보다 남아야 판다)
 * - BUY : 충전구 연결 + 경부하 구간 + 잔량 < 목표 + (RSI ≤ buyRsi 또는 가격 ≤ 경부하 기본가)
 * - 그 외 HOLD. 판매량은 락 위 에너지를 넘지 않는다 (출근용 전력은 절대 팔지 않음).
 */
class V2gTrader(
    val powerKw: Double = 7.0,
    val sellRsi: Double = 70.0,
    val buyRsi: Double = 30.0,
    val lockMarginPercent: Double = 3.0,
    val chargeTargetPercent: Int = 90,
    /** 배터리 마모 비용(원/kWh), 시연용 가정치. */
    val wearWonPerKwh: Double = 35.0,
    val minSellWon: Double = 120.0,
) {
    fun decide(
        candle: Candle,
        rsi: Double?,
        socPercent: Double,
        capacityKwh: Double,
        lockSoc: Int,
        plugged: Boolean,
        offPeakBaseWon: Double,
        stepMinutes: Int,
    ): TradeDecision {
        val price = candle.close
        val stepKwh = powerKw * stepMinutes / 60.0
        if (!plugged) return TradeDecision(TradeAction.HOLD, 0.0, 0.0, "충전구 미연결")

        val sellable = max(0.0, (socPercent - lockSoc - lockMarginPercent) / 100.0 * capacityKwh)
        val sellSignal = candle.tier == TouTier.PEAK || (rsi != null && rsi >= sellRsi)
        if (sellSignal && sellable > 0.0 && price >= minSellWon) {
            val kwh = min(stepKwh, sellable)
            return TradeDecision(
                TradeAction.SELL, kwh, kwh * (price - wearWonPerKwh),
                if (candle.tier == TouTier.PEAK) "최대부하 시간대" else "RSI ${rsi?.toInt()} 과매수",
            )
        }
        if (sellSignal && sellable <= 0.0) return TradeDecision(TradeAction.HOLD, 0.0, 0.0, "방전 락 보호 중 (${lockSoc}%)")

        val room = max(0.0, (chargeTargetPercent - socPercent) / 100.0 * capacityKwh)
        val buySignal = candle.tier == TouTier.OFF_PEAK && ((rsi != null && rsi <= buyRsi) || price <= offPeakBaseWon)
        if (buySignal && room > 0.0) {
            val kwh = min(stepKwh, room)
            return TradeDecision(TradeAction.BUY, kwh, -kwh * price, "경부하 저가 충전")
        }
        return TradeDecision(TradeAction.HOLD, 0.0, 0.0, "신호 없음")
    }

    fun apply(ledger: V2gLedger, d: TradeDecision, price: Double): V2gLedger = when (d.action) {
        TradeAction.SELL -> ledger.copy(
            soldKwh = ledger.soldKwh + d.kWh,
            revenueWon = ledger.revenueWon + d.kWh * price,
            wearWon = ledger.wearWon + d.kWh * wearWonPerKwh,
        )
        TradeAction.BUY -> ledger.copy(boughtKwh = ledger.boughtKwh + d.kWh, costWon = ledger.costWon + d.kWh * price)
        TradeAction.HOLD -> ledger
    }
}
