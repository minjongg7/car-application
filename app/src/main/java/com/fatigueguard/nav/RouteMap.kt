package com.fatigueguard.nav

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.hmgapps.core.ui.HmgColors
import kotlin.math.cos
import kotlin.math.max

/**
 * 경로 개략도 (지도 타일 없이 그리는 사전 점검용 화면).
 * 실제 도로 경로·지도는 Pleos Navigation 앱이 그린다. 여기서는 출발/도착, 경로 근처 휴게소·충전소, 도착 배터리 위험을 한눈에 보여 준다.
 */
@Composable
fun RouteMap(from: GeoPoint, to: Poi?, stops: List<OnRoute>, fatigueColor: Color, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        // 배경 격자
        val grid = 48f
        var gx = 0f
        while (gx < size.width) { drawLine(HmgColors.Line.copy(alpha = 0.5f), Offset(gx, 0f), Offset(gx, size.height), 1f); gx += grid }
        var gy = 0f
        while (gy < size.height) { drawLine(HmgColors.Line.copy(alpha = 0.5f), Offset(0f, gy), Offset(size.width, gy), 1f); gy += grid }

        val pts = buildList {
            add(from)
            if (to != null) add(GeoPoint(to.lat, to.lon))
            stops.forEach { add(GeoPoint(it.poi.lat, it.poi.lon)) }
        }
        val kx = cos(Math.toRadians(from.lat))
        val minX = pts.minOf { it.lon * kx }; val maxX = pts.maxOf { it.lon * kx }
        val minY = pts.minOf { it.lat }; val maxY = pts.maxOf { it.lat }
        val span = max(max(maxX - minX, maxY - minY), 0.05)
        val pad = 70f
        val scale = minOf(size.width - 2 * pad, size.height - 2 * pad) / span
        val cxGeo = (minX + maxX) / 2; val cyGeo = (minY + maxY) / 2
        fun p(g: GeoPoint) = Offset(
            (size.width / 2 + (g.lon * kx - cxGeo) * scale).toFloat(),
            (size.height / 2 - (g.lat - cyGeo) * scale).toFloat(),
        )
        val start = p(from)

        if (to != null) {
            val end = p(GeoPoint(to.lat, to.lon))
            // 경로(개략) : 폭 넓은 밑선 + 점선
            drawLine(HmgColors.Accent.copy(alpha = 0.25f), start, end, 22f, cap = StrokeCap.Round)
            drawLine(HmgColors.Accent, start, end, 6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)))
            stops.forEach { s ->
                val o = p(GeoPoint(s.poi.lat, s.poi.lon))
                val c = if (s.poi.type == PoiType.CHARGER) HmgColors.Energy else HmgColors.Good
                drawCircle(c, 11f, o)
                drawCircle(HmgColors.Bg, 5f, o)
            }
            // 도착 핀
            val pin = Path().apply {
                moveTo(end.x, end.y); lineTo(end.x - 14f, end.y - 26f); lineTo(end.x + 14f, end.y - 26f); close()
            }
            drawPath(pin, HmgColors.Bad)
            drawCircle(HmgColors.Bad, 15f, Offset(end.x, end.y - 30f))
            drawCircle(Color.White, 6f, Offset(end.x, end.y - 30f))
            drawText(tm, to.name, Offset(end.x + 20f, end.y - 44f), TextStyle(color = HmgColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
        // 현재 위치 (테두리 색 = 피로도 신호)
        drawCircle(fatigueColor.copy(alpha = 0.25f), 30f, start)
        drawCircle(HmgColors.Accent, 14f, start)
        drawCircle(Color.White, 14f, start, style = Stroke(4f))
        drawText(tm, "현재 위치", Offset(start.x + 22f, start.y - 10f), TextStyle(color = HmgColors.Sub, fontSize = 13.sp))
        if (to == null) {
            drawText(tm, "왼쪽에서 목적지를 검색하세요", Offset(24f, 20f), TextStyle(color = HmgColors.Sub, fontSize = 16.sp))
        }
        drawText(tm, "경로 개략도 · 실제 지도/안내는 Pleos 내비", Offset(16f, size.height - 30f), TextStyle(color = HmgColors.Sub, fontSize = 12.sp))
    }
}
