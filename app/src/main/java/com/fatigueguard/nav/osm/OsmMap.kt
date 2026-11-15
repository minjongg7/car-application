package com.fatigueguard.nav.osm

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fatigueguard.nav.Poi
import com.fatigueguard.nav.PoiType
import com.hmgapps.core.ui.HmgColors
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class TileKey(val z: Int, val x: Int, val y: Int) { val id: String get() = "$z/$x/$y" }

/**
 * OSM 래스터 타일 로더 + 디스크 캐시(앱 캐시 폴더, 7일).
 * 기본 서버(tile.openstreetmap.org)는 OSM 재단의 공개 서버라 시연/소규모용이다 (대량 사용 금지 정책).
 * 실서비스는 타일 서버를 직접 두거나 상용/자체 호스팅 타일 URL 로 tileUrl 을 바꾼다.
 */
class TileStore private constructor(private val dir: File) {
    companion object {
        // tile.openstreetmap.org 는 앱 트래픽을 차단(403), CARTO 는 API 키 요구, Esri 는 한국 데이터 없음 →
        // OSM 데이터(한국 포함)를 그대로 쓰는 OpenStreetMap France 타일로 교체. 시연용이며 실서비스는 자체/상용 타일 서버로 바꿀 것.
        // {z}/{x}/{y} 자리표시자 템플릿.
        // 앞에서부터 시도해서 처음 성공한 서버를 계속 쓴다.
        var tileUrls = listOf(
            "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
            "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
            "https://tile.opentopomap.org/{z}/{x}/{y}.png",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        )
        @Volatile private var working = 0
        private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        @Volatile private var instance: TileStore? = null
        fun get(context: Context): TileStore = instance ?: synchronized(this) {
            instance ?: TileStore(File(context.applicationContext.cacheDir, "osm-tiles-v2")).also { instance = it }
        }
    }

    private val gate = Semaphore(6)

    suspend fun load(k: TileKey): ImageBitmap? = withContext(Dispatchers.IO) {
        val f = File(dir, "${k.z}/${k.x}/${k.y}.png")
        val fresh = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
        var data: ByteArray? = if (fresh) f.readBytes() else null
        if (data == null) {
            data = gate.withPermit { download(k, f) }
            if (data == null && f.exists()) data = f.readBytes() // 오프라인이면 오래된 캐시라도 사용
        }
        data?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    private fun download(k: TileKey, f: File): ByteArray? {
        val errors = mutableListOf<String>()
        for (n in tileUrls.indices) {
            val idx = (working + n) % tileUrls.size
            val url = tileUrls[idx].replace("{z}", "${k.z}").replace("{x}", "${k.x}").replace("{y}", "${k.y}")
            try {
                val bytes = Http.bytes(url)
                if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) throw IllegalStateException("not an image")
                working = idx
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
                MapDiag.tileError.value = "OK " + java.net.URL(url).host
                return bytes
            } catch (e: Exception) {
                errors += java.net.URL(url).host + "=" + ((e as? HttpStatusException)?.let { "HTTP ${it.code}" } ?: e.javaClass.simpleName)
            }
        }
        MapDiag.tileError.value = errors.joinToString(", ")
        return null
    }
}

/**
 * OSM 지도. 드래그/핀치로 이동·확대, 경로 폴리라인, 현재 위치(진행 방향), 목적지 핀, 경로상 휴게소·충전소 표시.
 * follow=true 이면 현재 위치를 따라가고, 손으로 움직이면 "현재 위치" 버튼이 나타난다.
 */
@Composable
fun OsmMap(
    route: OsmRoute?,
    me: LatLon?,
    heading: Double?,
    dest: LatLon?,
    destName: String?,
    pois: List<Poi>,
    follow: Boolean,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { TileStore.get(context) }
    val tm = rememberTextMeasurer()
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var center by remember { mutableStateOf(me ?: LatLon(37.5665, 126.9780)) }
    var zoom by remember { mutableDoubleStateOf(13.0) }
    var detached by remember { mutableStateOf(false) }
    val loaded = remember { mutableStateMapOf<String, ImageBitmap>() }

    // 경로/목적지가 바뀌면 전체가 보이게 맞춘다 (안내 중이 아닐 때)
    LaunchedEffect(route, dest, mapSize, follow) {
        if (follow || mapSize.width == 0) return@LaunchedEffect
        val pts = route?.points ?: listOfNotNull(me, dest)
        if (pts.isEmpty()) return@LaunchedEffect
        val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
        zoom = WebMercator.fitZoom(minLat, maxLat, minLon, maxLon, mapSize.width.toDouble(), mapSize.height.toDouble())
        center = LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2)
        detached = false
    }
    LaunchedEffect(follow) { if (follow) { detached = false; zoom = 16.5 } }
    LaunchedEffect(me, follow, detached) { if (follow && !detached && me != null) center = me }

    // 지금 화면에 필요한 타일
    val z = floor(zoom).toInt().coerceIn(3, 18)
    val f = 2.0.pow(zoom - z)
    val cx = WebMercator.worldX(center.lon, z.toDouble())
    val cy = WebMercator.worldY(center.lat, z.toDouble())
    val n = 1 shl z
    val keys: List<TileKey> = if (mapSize.width == 0) emptyList() else {
        val halfW = mapSize.width / 2.0 / f
        val halfH = mapSize.height / 2.0 / f
        val x0 = floor((cx - halfW) / 256).toInt().coerceAtLeast(0)
        val x1 = floor((cx + halfW) / 256).toInt().coerceAtMost(n - 1)
        val y0 = floor((cy - halfH) / 256).toInt().coerceAtLeast(0)
        val y1 = floor((cy + halfH) / 256).toInt().coerceAtMost(n - 1)
        buildList { for (y in y0..y1) for (x in x0..x1) add(TileKey(z, x, y)) }
    }
    LaunchedEffect(keys) {
        if (loaded.size > 240) {
            val keep = keys.map { it.id }.toSet()
            loaded.keys.filter { it !in keep }.forEach { loaded.remove(it) }
        }
        keys.filter { it.id !in loaded }.forEach { k ->
            launch { store.load(k)?.let { loaded[k.id] = it } }
        }
    }

    val dim = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.8f, 0.8f, 0.8f, 1f) }) }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { mapSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zc, _ ->
                        val z0 = zoom
                        if (pan != Offset.Zero) {
                            val nx = WebMercator.worldX(center.lon, z0) - pan.x
                            val ny = WebMercator.worldY(center.lat, z0) - pan.y
                            center = LatLon(WebMercator.lat(ny, z0), WebMercator.lon(nx, z0))
                            detached = true
                        }
                        zoom = (z0 + log2(zc.toDouble())).coerceIn(3.0, 18.0)
                    }
                },
        ) {
            drawRect(HmgColors.Panel)
            val w = size.width
            val h = size.height
            fun toScreen(p: LatLon) = Offset(
                (w / 2 + (WebMercator.worldX(p.lon, zoom) - WebMercator.worldX(center.lon, zoom))).toFloat(),
                (h / 2 + (WebMercator.worldY(p.lat, zoom) - WebMercator.worldY(center.lat, zoom))).toFloat(),
            )

            // 타일
            val ts = ceil(256 * f).toInt() + 1
            for (k in keys) {
                val img = loaded[k.id] ?: continue
                val left = floor((k.x * 256.0 - cx) * f + w / 2).toInt()
                val top = floor((k.y * 256.0 - cy) * f + h / 2).toInt()
                drawImage(
                    img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(left, top), dstSize = IntSize(ts, ts), colorFilter = dim,
                )
            }

            // 경로
            if (route != null && route.points.size >= 2) {
                val pts = route.points
                val step = max(1, pts.size / 1500)
                val path = Path()
                var i = 0
                while (i < pts.size) {
                    val o = toScreen(pts[i])
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    i += step
                }
                val last = toScreen(pts.last()); path.lineTo(last.x, last.y)
                drawPath(path, Color(0xCC0B1220), style = Stroke(16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, HmgColors.Accent, style = Stroke(9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // 경로상 휴게소·충전소
            pois.forEach { p ->
                if (p.type == PoiType.PLACE) return@forEach
                val o = toScreen(LatLon(p.lat, p.lon))
                if (o.x < -20 || o.y < -20 || o.x > w + 20 || o.y > h + 20) return@forEach
                val c = if (p.type == PoiType.CHARGER) HmgColors.Energy else HmgColors.Good
                drawCircle(Color.White, 13f, o)
                drawCircle(c, 10f, o)
                drawCircle(HmgColors.Bg, 4f, o)
            }

            // 목적지 핀
            if (dest != null) {
                val e = toScreen(dest)
                val pin = Path().apply { moveTo(e.x, e.y); lineTo(e.x - 14f, e.y - 26f); lineTo(e.x + 14f, e.y - 26f); close() }
                drawPath(pin, HmgColors.Bad)
                drawCircle(HmgColors.Bad, 15f, Offset(e.x, e.y - 30f))
                drawCircle(Color.White, 6f, Offset(e.x, e.y - 30f))
                // drawText(tm, text, topLeft) 는 남은 너비(size.width - x)를 제약으로 쓰므로 핀이 화면 밖이면 음수 → 크래시.
                // 글자를 먼저 측정하고 화면 안에 있을 때만 그린다.
                if (destName != null && e.x + 20f < w && e.y - 46f < h && e.x > -200f && e.y > 0f) {
                    val label = tm.measure(destName, TextStyle(color = HmgColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    drawText(label, topLeft = Offset(e.x + 20f, e.y - 46f))
                }
            }

            // 현재 위치
            if (me != null) {
                val o = toScreen(me)
                drawCircle(ringColor.copy(alpha = 0.25f), 30f, o)
                if (follow && heading != null) {
                    rotate(heading.toFloat(), pivot = o) {
                        val tri = Path().apply { moveTo(o.x, o.y - 26f); lineTo(o.x - 17f, o.y + 18f); lineTo(o.x, o.y + 9f); lineTo(o.x + 17f, o.y + 18f); close() }
                        drawPath(tri, Color.White)
                        val inner = Path().apply { moveTo(o.x, o.y - 18f); lineTo(o.x - 11f, o.y + 12f); lineTo(o.x, o.y + 6f); lineTo(o.x + 11f, o.y + 12f); close() }
                        drawPath(inner, HmgColors.Accent)
                    }
                } else {
                    drawCircle(HmgColors.Accent, 14f, o)
                    drawCircle(Color.White, 14f, o, style = Stroke(4f))
                }
            }

            // 저작자 표기 (OSM 라이선스 필수)
            val attr = tm.measure("© OpenStreetMap contributors", TextStyle(color = HmgColors.Text, fontSize = 11.sp))
            drawRect(Color(0x99000000), Offset(w - attr.size.width - 14f, h - attr.size.height - 10f), androidx.compose.ui.geometry.Size(attr.size.width + 12f, attr.size.height + 6f))
            drawText(attr, topLeft = Offset(w - attr.size.width - 8f, h - attr.size.height - 7f))
        }

        // 확대/축소 (차량 터치용 큰 버튼)
        Column(Modifier.align(Alignment.CenterEnd).padding(10.dp)) {
            OutlinedButton(onClick = { zoom = (zoom + 1).coerceAtMost(18.0) }, modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 52.dp)) { Text("＋", fontSize = 20.sp) }
            OutlinedButton(onClick = { zoom = (zoom - 1).coerceAtLeast(3.0) }, modifier = Modifier.padding(top = 6.dp).heightIn(min = 52.dp).widthIn(min = 52.dp)) { Text("－", fontSize = 20.sp) }
        }
        if (follow && detached) {
            Button(onClick = { detached = false; zoom = 16.5 }, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).heightIn(min = 52.dp)) {
                Text("현재 위치", fontWeight = FontWeight.Bold)
            }
        }
    }
}
