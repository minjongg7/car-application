package com.hmgapps.energy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.hmgapps.core.ui.HmgColors
import com.hmgapps.energy.domain.Candle
import com.hmgapps.energy.domain.TouTier
import com.hmgapps.energy.domain.TradeAction
import com.hmgapps.energy.domain.TradeDecision
import kotlin.math.max

/**
 * 증권앱 형태 차트: 위 = 전력 가격 캔들(15분 봉) + 이동평균, 가운데 = 거래량(판매 빨강/충전 파랑), 아래 = RSI(14).
 * 배경 띠 = 계시별 요금 구간(최대부하 구간은 붉게).
 */
@Composable
fun TradingChart(candles: List<Candle>, trades: List<TradeDecision>, rsi: List<Double?>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val axisW = 64f
        val w = size.width - axisW
        val priceH = size.height * 0.62f
        val volTop = priceH + 10f
        val volH = size.height * 0.14f
        val rsiTop = volTop + volH + 14f
        val rsiH = size.height - rsiTop

        val slots = 48 // 화면에 12시간(15분 × 48) 표시
        val shown = candles.takeLast(slots)
        val shownTrades = trades.takeLast(shown.size)
        val shownRsi = rsi.takeLast(shown.size)
        val slotW = w / slots

        if (shown.isEmpty()) {
            label(tm, "‘거래 시작’을 누르면 실시간 시세가 그려집니다", Offset(16f, priceH / 2), HmgColors.Sub, 16)
            return@Canvas
        }

        val hi = shown.maxOf { it.high } * 1.03
        val lo = shown.minOf { it.low } * 0.97
        fun py(p: Double) = (priceH * (1 - (p - lo) / (hi - lo))).toFloat()

        // 요금 구간 배경
        shown.forEachIndexed { i, c ->
            val col = when (c.tier) {
                TouTier.PEAK -> HmgColors.Up.copy(alpha = 0.10f)
                TouTier.MID -> Color.White.copy(alpha = 0.03f)
                TouTier.OFF_PEAK -> HmgColors.Down.copy(alpha = 0.07f)
            }
            drawRect(col, Offset(i * slotW, 0f), Size(slotW, priceH))
        }
        // 가격 격자 + 축
        for (k in 0..4) {
            val p = lo + (hi - lo) * k / 4
            val y = py(p)
            drawLine(HmgColors.Line, Offset(0f, y), Offset(w, y), 1f)
            label(tm, "${p.toInt()}", Offset(w + 6f, y - 9f), HmgColors.Sub, 11)
        }
        // 캔들
        shown.forEachIndexed { i, c ->
            val x = i * slotW + slotW / 2
            val up = c.close >= c.open
            val col = if (up) HmgColors.Up else HmgColors.Down
            drawLine(col, Offset(x, py(c.high)), Offset(x, py(c.low)), 2f)
            val top = py(max(c.open, c.close))
            val bodyH = max(2f, py(minOf(c.open, c.close)) - top)
            drawRect(col, Offset(x - slotW * 0.32f, top), Size(slotW * 0.64f, bodyH))
        }
        // 이동평균(5)
        val closes = shown.map { it.close }
        val ma = Path()
        var started = false
        closes.indices.forEach { i ->
            if (i < 4) return@forEach
            val v = closes.subList(i - 4, i + 1).average()
            val pt = Offset(i * slotW + slotW / 2, py(v))
            if (!started) { ma.moveTo(pt.x, pt.y); started = true } else ma.lineTo(pt.x, pt.y)
        }
        drawPath(ma, HmgColors.Warn, style = Stroke(2f))
        // 시간 라벨
        shown.forEachIndexed { i, c ->
            if (c.minuteOfDay % 120 == 0) label(tm, "%02d:00".format(c.minuteOfDay / 60), Offset(i * slotW, priceH - 18f), HmgColors.Sub, 11)
        }

        // 거래량
        val maxKwh = max(0.1, shownTrades.maxOfOrNull { it.kWh } ?: 0.1)
        drawLine(HmgColors.Line, Offset(0f, volTop + volH), Offset(w, volTop + volH), 1f)
        shownTrades.forEachIndexed { i, t ->
            if (t.kWh <= 0) return@forEachIndexed
            val h = (t.kWh / maxKwh * volH).toFloat()
            val col = if (t.action == TradeAction.SELL) HmgColors.Up else HmgColors.Down
            drawRoundRect(col.copy(alpha = 0.85f), Offset(i * slotW + slotW * 0.15f, volTop + volH - h), Size(slotW * 0.7f, h), CornerRadius(2f))
        }
        label(tm, "거래량", Offset(w + 6f, volTop), HmgColors.Sub, 11)

        // RSI
        fun ry(v: Double) = (rsiTop + rsiH * (1 - v / 100.0)).toFloat()
        drawRect(HmgColors.PanelHi, Offset(0f, rsiTop), Size(w, rsiH))
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        drawLine(HmgColors.Up.copy(alpha = 0.7f), Offset(0f, ry(70.0)), Offset(w, ry(70.0)), 1.5f, pathEffect = dash)
        drawLine(HmgColors.Down.copy(alpha = 0.7f), Offset(0f, ry(30.0)), Offset(w, ry(30.0)), 1.5f, pathEffect = dash)
        label(tm, "70", Offset(w + 6f, ry(70.0) - 9f), HmgColors.Up, 11)
        label(tm, "30", Offset(w + 6f, ry(30.0) - 9f), HmgColors.Down, 11)
        val rp = Path()
        var rs = false
        shownRsi.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val pt = Offset(i * slotW + slotW / 2, ry(v))
            if (!rs) { rp.moveTo(pt.x, pt.y); rs = true } else rp.lineTo(pt.x, pt.y)
        }
        drawPath(rp, HmgColors.Energy, style = Stroke(2.5f))
        label(tm, "RSI(14)", Offset(6f, rsiTop + 2f), HmgColors.Sub, 11)
    }
}

/** 배터리 막대 + 방전 락 위치 표시. */
@Composable
fun BatteryBar(socPercent: Double, lockSoc: Int, color: Color, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val r = CornerRadius(14f)
        drawRoundRect(HmgColors.PanelHi, size = size, cornerRadius = r)
        val lockX = size.width * lockSoc / 100f
        drawRoundRect(HmgColors.Bad.copy(alpha = 0.22f), size = Size(lockX, size.height), cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * (socPercent / 100.0).toFloat(), size.height), cornerRadius = r)
        drawLine(Color.White, Offset(lockX, -6f), Offset(lockX, size.height + 6f), 4f)
        label(tm, "🔒 $lockSoc%", Offset(lockX + 8f, size.height / 2 - 11f), Color.White, 14)
    }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, at: Offset, color: Color, sizeSp: Int) {
    drawText(tm, text, at, TextStyle(color = color, fontSize = sizeSp.sp))
}
