package com.hmgapps.care.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.hmgapps.care.domain.Cell
import com.hmgapps.care.domain.Forecast
import com.hmgapps.care.domain.SensorSeries
import com.hmgapps.core.ui.HmgColors
import kotlin.math.max
import kotlin.math.min

/** 건강 점수 원호 게이지 (240°). */
@Composable
fun ScoreGauge(score: Int, grade: String, color: Color, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        val d = size.minDimension - stroke
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawArc(HmgColors.PanelHi, 150f, 240f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(color, 150f, 240f * score / 100f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        center(tm, "$score", Offset(size.width / 2, size.height / 2 - size.minDimension * 0.08f), color, (size.minDimension / 5.5f).toSp())
        center(tm, "차량 건강 점수 · 등급 $grade", Offset(size.width / 2, size.height / 2 + size.minDimension * 0.16f), HmgColors.Sub, 14.sp)
    }
}

/** 셀 히트맵: 행 = 모듈, 열 = 셀. 평균 대비 편차를 색으로 (파랑=낮음, 초록=정상, 빨강=높음). */
@Composable
fun CellHeatmap(cells: List<Cell>, modules: Int, perModule: Int, useTemp: Boolean, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        if (cells.isEmpty()) return@Canvas
        val values = cells.map { if (useTemp) it.tempC else it.voltage * 1000 }
        val mean = values.average()
        val range = if (useTemp) 4.0 else 20.0 // ±범위(℃ 또는 mV)에서 색 포화
        val labelW = 46f
        val gap = 4f
        val cw = (size.width - labelW) / perModule
        val ch = size.height / modules
        val worst = cells.indices.maxBy { kotlin.math.abs(values[it] - mean) }
        cells.forEachIndexed { i, c ->
            val dv = ((values[i] - mean) / range).coerceIn(-1.0, 1.0).toFloat()
            val col = if (dv < 0) lerp(HmgColors.Good, HmgColors.Down, -dv) else lerp(HmgColors.Good, HmgColors.Up, dv)
            val tl = Offset(labelW + c.slot * cw + gap / 2, c.module * ch + gap / 2)
            drawRoundRect(col, tl, Size(cw - gap, ch - gap), CornerRadius(5f))
            if (i == worst) drawRoundRect(Color.White, tl, Size(cw - gap, ch - gap), CornerRadius(5f), style = Stroke(3f))
        }
        for (m in 0 until modules) {
            drawText(tm, "M${m + 1}", Offset(0f, m * ch + ch / 2 - 9f), TextStyle(color = HmgColors.Sub, fontSize = 12.sp))
        }
    }
}

/** 실시간 스파크라인 + 정상 범위 띠. */
@Composable
fun LiveLine(values: List<Double>, lowOk: Double, highOk: Double, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = min(values.min(), lowOk) - (highOk - lowOk) * 0.15
        val hi = max(values.max(), highOk) + (highOk - lowOk) * 0.15
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        drawRect(HmgColors.Good.copy(alpha = 0.10f), Offset(0f, y(highOk)), Size(size.width, y(lowOk) - y(highOk)))
        val step = size.width / 59f
        val p = Path()
        values.forEachIndexed { i, v -> if (i == 0) p.moveTo(i * step, y(v)) else p.lineTo(i * step, y(v)) }
        drawPath(p, color, style = Stroke(3f))
    }
}

/** 일별 추세 + 임계선 + horizon 까지의 추세 외삽(점선). */
@Composable
fun TrendChart(series: SensorSeries, forecast: Forecast, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val n = series.daily.size
        val total = n + forecast.horizonDays
        val projected = series.daily.last() + (if (series.component.higherIsWorse) 1 else -1) * forecast.worseningPerDay * forecast.horizonDays
        val all = series.daily + listOf(series.threshold, projected)
        val pad = (all.max() - all.min()) * 0.1 + 1e-6
        val lo = all.min() - pad
        val hi = all.max() + pad
        val axisW = 70f
        val w = size.width - axisW
        fun x(i: Int) = w * i / (total - 1).toFloat()
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()

        // 미래 구간 배경
        drawRect(HmgColors.PanelHi, Offset(x(n - 1), 0f), Size(w - x(n - 1), size.height))
        label(tm, "예측 ${forecast.horizonDays}일", Offset(x(n - 1) + 8f, 6f), HmgColors.Sub)
        label(tm, "최근 ${n}일", Offset(6f, 6f), HmgColors.Sub)
        // 임계선
        val ty = y(series.threshold)
        drawLine(HmgColors.Bad, Offset(0f, ty), Offset(w, ty), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
        label(tm, "임계 ${fmt(series.threshold)}", Offset(w + 6f, ty - 10f), HmgColors.Bad)
        // 실측
        val p = Path()
        series.daily.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(i), y(v)) else p.lineTo(x(i), y(v)) }
        drawPath(p, HmgColors.Accent, style = Stroke(2.5f))
        // 외삽
        drawLine(HmgColors.Warn, Offset(x(n - 1), y(series.daily.last())), Offset(x(total - 1), y(projected)), 3f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        label(tm, fmt(series.daily.last()), Offset(w + 6f, y(series.daily.last()) - 10f), HmgColors.Accent)
        forecast.daysToThreshold?.takeIf { it in 0..forecast.horizonDays }?.let { d ->
            val cx = x(n - 1 + d)
            drawCircle(HmgColors.Bad, 8f, Offset(cx, ty))
            label(tm, "D+$d", Offset(cx - 18f, ty + 10f), HmgColors.Bad)
        }
    }
}

private fun fmt(v: Double) = if (kotlin.math.abs(v) >= 100) "%.0f".format(v) else "%.2f".format(v)

private fun DrawScope.label(tm: TextMeasurer, s: String, at: Offset, c: Color) =
    drawText(tm, s, at, TextStyle(color = c, fontSize = 12.sp))

private fun DrawScope.center(tm: TextMeasurer, s: String, mid: Offset, c: Color, fontSize: TextUnit) {
    val style = TextStyle(color = c, fontSize = fontSize, fontWeight = FontWeight.ExtraBold)
    val m = tm.measure(s, style)
    drawText(m, topLeft = Offset(mid.x - m.size.width / 2, mid.y - m.size.height / 2))
}
