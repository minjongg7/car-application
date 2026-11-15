package com.hmgapps.energy.domain

/** 금융앱 형태 지표. 순수 함수라 단위 테스트한다. */
object Indicators {
    /** 단순이동평균. 앞쪽 period-1 개는 null. */
    fun sma(values: List<Double>, period: Int): List<Double?> =
        values.indices.map { i -> if (i + 1 < period) null else values.subList(i + 1 - period, i + 1).average() }

    /**
     * RSI (Wilder 평활). 앞쪽 period 개는 null. 손실이 0 이면 100, 이득이 0 이면 0.
     * 70 이상 = 과매수(가격이 높게 형성 → 판매 유리), 30 이하 = 과매도(충전 유리).
     */
    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        val out = MutableList<Double?>(closes.size) { null }
        if (closes.size <= period) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        gain /= period; loss /= period
        out[period] = value(gain, loss)
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            gain = (gain * (period - 1) + maxOf(d, 0.0)) / period
            loss = (loss * (period - 1) + maxOf(-d, 0.0)) / period
            out[i] = value(gain, loss)
        }
        return out
    }

    private fun value(gain: Double, loss: Double): Double = when {
        loss == 0.0 && gain == 0.0 -> 50.0
        loss == 0.0 -> 100.0
        else -> 100.0 - 100.0 / (1.0 + gain / loss)
    }
}
