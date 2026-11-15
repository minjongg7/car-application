package com.fatigueguard.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fatigueguard.data.NaviPhase
import com.fatigueguard.data.NaviRouteOption
import com.fatigueguard.data.NaviSession
import com.fatigueguard.data.RestAreaRepository
import com.fatigueguard.domain.Messages
import com.fatigueguard.ui.HomeTab
import com.fatigueguard.ui.UiState
import com.hmgapps.core.energy.EnergyMath
import com.hmgapps.core.energy.EnergySettings
import com.hmgapps.core.energy.EnergySnapshot
import com.hmgapps.core.pleos.PleosVehicleState
import com.hmgapps.core.pleos.label
import com.hmgapps.core.ui.HmgColors
import com.hmgapps.core.ui.Metric
import com.hmgapps.core.ui.Panel
import com.hmgapps.core.ui.Pill
import com.fatigueguard.nav.osm.Geocoder
import com.fatigueguard.nav.osm.GuidanceState
import com.fatigueguard.nav.osm.KoreanInstructions
import com.fatigueguard.nav.osm.LatLon
import com.fatigueguard.nav.osm.CarMapLibre
import com.fatigueguard.nav.osm.OsmNavState
import com.fatigueguard.nav.osm.OsmRoute
import com.fatigueguard.nav.osm.OsrmClient
import com.fatigueguard.nav.osm.estimateOnRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NavUi(
    val query: String = "",
    val results: List<Poi> = PoiRepository.search(""),
    val destination: Poi? = null,
    /** 내비/피로도 화면 전환 상태 (화면 회전·다른 앱에서 돌아올 때도 유지). */
    val tab: HomeTab = HomeTab.NAV,
    /** 경유지 (목적지 앞에 순서대로 들른다). */
    val vias: List<Poi> = emptyList(),
    /** OSRM 이 돌려준 실제 도로 경로. null 이면 아직 없거나 서버에 닿지 못해 직선 추정으로 표시 중. */
    val route: OsmRoute? = null,
    val routing: Boolean = false,
    val routeError: String? = null,
    val searching: Boolean = false,
    val searchError: String? = null,
)

class NavViewModel : ViewModel() {
    private val _state = MutableStateFlow(NavUi())
    val state: StateFlow<NavUi> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var routeJob: Job? = null

    /** 입력 즉시 오프라인 목록(휴게소·충전소·주요 지점)을 보여 주고, 450ms 쉬었다가 온라인 검색 결과를 덧붙인다. */
    fun query(q: String, near: GeoPoint) {
        _state.update { it.copy(query = q, results = PoiRepository.search(q), searchError = null, searching = false) }
        searchJob?.cancel()
        if (q.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(450)
            _state.update { it.copy(searching = true) }
            try {
                val online = Geocoder.search(q, near.lat, near.lon)
                _state.update { st ->
                    if (st.query != q) st else st.copy(searching = false, results = mergeResults(PoiRepository.search(q), online))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { st -> if (st.query != q) st else st.copy(searching = false, searchError = "온라인 검색 실패: ${e.message ?: "네트워크 오류"}") }
            }
        }
    }

    fun select(p: Poi, from: GeoPoint) {
        _state.update { it.copy(destination = p, vias = emptyList(), route = null) }
        fetchRoute(from)
    }

    fun addVia(p: Poi, from: GeoPoint) {
        if (_state.value.destination == null || _state.value.vias.any { it.name == p.name }) return
        _state.update { it.copy(vias = it.vias + p) }
        fetchRoute(from)
    }

    fun retryRoute(from: GeoPoint) = fetchRoute(from)

    fun clear() {
        routeJob?.cancel()
        _state.update { it.copy(destination = null, vias = emptyList(), route = null, routing = false, routeError = null) }
    }

    fun openTab(t: HomeTab) = _state.update { it.copy(tab = t) }

    private fun fetchRoute(from: GeoPoint) {
        val st = _state.value
        val dest = st.destination ?: return
        routeJob?.cancel()
        _state.update { it.copy(routing = true, routeError = null, route = null) }
        routeJob = viewModelScope.launch {
            try {
                val pts = listOf(LatLon(from.lat, from.lon)) + st.vias.map { LatLon(it.lat, it.lon) } + LatLon(dest.lat, dest.lon)
                val r = OsrmClient.route(pts)
                _state.update { it.copy(route = r, routing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(routing = false, routeError = e.message ?: "경로 서버에 연결하지 못했습니다") }
            }
        }
    }

    private fun mergeResults(local: List<Poi>, online: List<Poi>): List<Poi> {
        val out = ArrayList(local)
        online.forEach { o ->
            val dup = out.any { it.name.replace(" ", "") == o.name.replace(" ", "") &&
                RestAreaRepository.haversineKm(it.lat, it.lon, o.lat, o.lon) < 0.3 }
            if (!dup) out += o
        }
        return out.take(14)
    }
}

/** 화면에서 호출하는 동작들 (MainActivity 가 내비 SDK·화면 전환과 연결). */
class NavActions(
    val onQuery: (String) -> Unit,
    val onSelect: (Poi) -> Unit,
    val onClear: () -> Unit,
    val onGuide: (Poi) -> Unit,
    val onAddStop: (Poi) -> Unit,
    val onOpenFatigue: () -> Unit,
    val onOpenEnergy: () -> Unit,
    val onCancelRoute: () -> Unit,
    val onReroute: () -> Unit,
    val onRouteOption: (NaviRouteOption) -> Unit,
    /** 내장(OSM) 안내. simulate=true 면 GPS 대신 경로를 따라 가상 주행. */
    val onStartOsm: (Boolean) -> Unit,
    val onStopOsm: () -> Unit,
    val onOsmReroute: () -> Unit,
    val onOsmSimSpeed: () -> Unit,
    val onRetryRoute: () -> Unit,
    /** 차량(에뮬레이터 포함)에 Pleos 내비가 있으면 true → '안내 시작'이 Pleos 내비(NaviHelper)로 길안내한다. */
    val pleosNaviAvailable: Boolean = false,
)

private fun levelColor(level: Int?) = when {
    level == null -> HmgColors.Sub
    level <= 1 -> HmgColors.Good
    level == 2 -> HmgColors.Warn
    else -> HmgColors.Bad
}

/** 네비게이션 베이스 홈: 목적지 검색 → 경로 사전 점검(거리·시간·도착 배터리·피로도) → Pleos 내비로 안내 시작. */
@Composable
fun NavHome(nav: NavUi, fatigue: UiState, energy: EnergySnapshot, settings: EnergySettings, vehicle: PleosVehicleState, navi: NaviSession, osm: OsmNavState, a: NavActions) {
    val from = GeoPoint(fatigue.lat, fatigue.lon)
    val dest = nav.destination
    // 실측 전비(Pleos)가 있으면 도착 배터리 계산에 그것을 쓴다
    val effSettings = settings.copy(efficiencyKmPerKwh = EnergyMath.efficiency(energy, settings))
    // OSRM 경로가 있으면 실제 도로 기준, 없으면(서버 연결 실패) 직선 추정으로 대체
    val est = dest?.let { d -> nav.route?.let { RoutePlanner.estimateOnRoute(it, energy, effSettings) } ?: RoutePlanner.estimate(from, d, energy, effSettings) }
    val level = fatigue.assessment?.level
    val fColor = levelColor(level)

    if (osm.running) {
        GuidanceLayout(osm, dest, fColor, fatigue, energy, effSettings, a)
        return
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---------- 검색
        Panel(Modifier.weight(0.95f).fillMaxHeight(), title = "목적지 검색") {
            OutlinedTextField(
                value = nav.query, onValueChange = a.onQuery, singleLine = true,
                placeholder = { Text("예: 부산역, 대전, 휴게소, 충전") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (nav.searching) Text("온라인 검색 중…", color = HmgColors.Sub, fontSize = 13.sp)
            nav.searchError?.let { Text("$it (오프라인 목록만 표시)", color = HmgColors.Warn, fontSize = 13.sp) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(nav.results) { p ->
                    val km = RestAreaRepository.haversineKm(from.lat, from.lon, p.lat, p.lon) * RoutePlanner.ROAD_FACTOR
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (p == dest) HmgColors.PanelHi else Color.Transparent)
                            .clickable { a.onSelect(p) }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.type.icon, fontSize = 22.sp, modifier = Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            if (p.note.isNotEmpty()) Text(p.note, fontSize = 12.sp, color = HmgColors.Sub)
                        }
                        Text("${km.toInt()} km", color = HmgColors.Sub)
                    }
                }
            }
        }

        // ---------- 경로 개략도 + 요약
        Column(Modifier.weight(1.7f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(HmgColors.Panel)) {
                CarMapLibre(
                    route = nav.route, me = LatLon(from.lat, from.lon), heading = null,
                    dest = dest?.let { LatLon(it.lat, it.lon) }, destName = dest?.name,
                    pois = est?.stops.orEmpty().map { it.poi }, follow = false, ringColor = fColor,
                    modifier = Modifier.fillMaxSize(),
                )
                val status = when {
                    dest == null -> "왼쪽에서 목적지를 검색하세요"
                    nav.routing -> "OSRM 도로 경로 탐색 중…"
                    nav.routeError != null -> "경로 서버 연결 실패: ${nav.routeError} · 직선 추정치로 표시 중"
                    nav.route != null -> "OSRM 도로 경로 (OpenStreetMap)"
                    else -> null
                }
                if (status != null) {
                    Text(
                        status, fontSize = 13.sp, color = if (nav.routeError != null) HmgColors.Warn else HmgColors.Text,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(8.dp))
                            .background(HmgColors.Bg.copy(alpha = 0.8f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            if (dest != null && est != null) Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Metric(if (nav.route != null) "거리" else "거리(추정)", "${est.distanceKm}", "km")
                    Metric("예상 시간", "${est.etaMinutes / 60}:${"%02d".format(est.etaMinutes % 60)}", "")
                    Metric("도착 배터리", "%.0f".format(est.arrivalSoc.coerceAtLeast(0.0)), "%",
                        when { est.unreachable -> HmgColors.Bad; est.belowLock -> HmgColors.Warn; else -> HmgColors.Good })
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        if (nav.routeError != null) {
                            OutlinedButton(onClick = a.onRetryRoute, modifier = Modifier.heightIn(min = 48.dp)) { Text("경로 다시 요청") }
                        }
                        // Pleos 내비가 있으면 '안내 시작' = Pleos NaviHelper 길안내 (경로 탐색·턴 안내를 차량 내비가 수행).
                        // 없으면(일반 폰/태블릿) 앱 내장 OSM 안내.
                        Button(
                            onClick = { if (a.pleosNaviAvailable) a.onGuide(dest) else a.onStartOsm(false) },
                            enabled = a.pleosNaviAvailable || (nav.route != null && !nav.routing),
                            modifier = Modifier.heightIn(min = 64.dp).width(220.dp),
                        ) { Text("안내 시작", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { a.onStartOsm(true) }, enabled = nav.route != null && !nav.routing, modifier = Modifier.heightIn(min = 48.dp)) { Text("시뮬레이션") }
                            if (a.pleosNaviAvailable) {
                                OutlinedButton(onClick = { a.onStartOsm(false) }, enabled = nav.route != null && !nav.routing, modifier = Modifier.heightIn(min = 48.dp)) { Text("내장 안내") }
                            }
                            OutlinedButton(onClick = a.onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("지우기") }
                        }
                        if (nav.vias.isNotEmpty()) Text("경유 ${nav.vias.joinToString(" → ") { it.name }}", color = HmgColors.Sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        // ---------- 사전 점검 (피로도 · 에너지 · 경로상 정차지)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NaviPanel(navi, a)
            Panel(Modifier.fillMaxWidth().clickable(onClick = a.onOpenFatigue), title = "피로도 가드", trailing = {
                Pill(if (fatigue.running) "모니터링 중" else "대기", if (fatigue.running) HmgColors.Good else HmgColors.Sub)
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${fatigue.assessment?.score ?: "-"}", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = fColor)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(level?.let { "Level $it · ${Messages.levelName[it]}" } ?: "주행 시작 전", fontWeight = FontWeight.Bold, color = fColor)
                        Text(fatigue.assessment?.let { "주행 ${it.drivingMinutes}분" } ?: "탭해서 시작", color = HmgColors.Sub, fontSize = 13.sp)
                        if (vehicle.live) Text(
                            "차량 트립 ${vehicle.tripDrivingMinutes?.let { "${it}분" } ?: "-"} · 기어 ${vehicle.gear?.label() ?: "-"}",
                            color = HmgColors.Sub, fontSize = 13.sp,
                        )
                    }
                }
                if (level != null && level >= 2 && est != null) {
                    Text("경로상 휴게소에서 쉬어 가세요.", color = fColor, fontSize = 14.sp)
                }
            }
            Panel(Modifier.fillMaxWidth().clickable(onClick = a.onOpenEnergy), title = "배터리") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Metric("잔량", "%.0f".format(energy.socPercent), "%")
                    Metric("주행가능", "%.0f".format(EnergyMath.range(energy, settings)), "km")
                }
                Text(
                    energy.measuredEfficiency?.let { "실측 전비 ${"%.1f".format(it)} km/kWh (Pleos)" }
                        ?: "전비 ${"%.1f".format(settings.efficiencyKmPerKwh)} km/kWh (설정값)",
                    color = HmgColors.Sub, fontSize = 13.sp,
                )
                val lock = EnergyMath.effectiveLockSoc(settings, energy.capacityKwh)
                Text("방전 락 $lock% (Energy Hub 설정 공유)", color = HmgColors.Sub, fontSize = 13.sp)
                if (est != null && (est.belowLock || est.unreachable)) {
                    Text(
                        if (est.unreachable) "이 배터리로는 도착할 수 없습니다. 충전소를 경유하세요."
                        else "도착 시 ${"%.0f".format(est.arrivalSoc)}% — 내일 출근용 락(${lock}%)보다 낮습니다.",
                        color = if (est.unreachable) HmgColors.Bad else HmgColors.Warn, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (est != null) Panel(Modifier.fillMaxWidth(), title = "경로상 정차지") {
                val shown = (if (est.belowLock || est.unreachable) est.chargers + est.restStops else est.restStops + est.chargers).take(6)
                if (shown.isEmpty()) Text("경로 근처에 등록된 휴게소/충전소가 없습니다.", color = HmgColors.Sub, fontSize = 14.sp)
                shown.forEach { s ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.poi.type.icon, fontSize = 20.sp, modifier = Modifier.width(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.poi.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("출발 후 약 ${s.fromStartKm.toInt()}km", color = HmgColors.Sub, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { a.onAddStop(s.poi) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("경유") }
                    }
                }
            }
        }
    }
}

/** 주행 중 화면: 지도(현재 위치 추적) + 다음 회전 카드 + 남은 거리/시간 + 제어. */
@Composable
private fun GuidanceLayout(osm: OsmNavState, dest: Poi?, fColor: Color, fatigue: UiState, energy: EnergySnapshot, settings: EnergySettings, a: NavActions) {
    val g = osm.g
    val next = g.nextStep
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.weight(2.1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(HmgColors.Panel)) {
            CarMapLibre(
                route = osm.route, me = g.position, heading = g.heading,
                dest = osm.route?.points?.lastOrNull(), destName = dest?.name,
                pois = emptyList(), follow = true, ringColor = fColor, modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth(), title = if (g.arrived) "도착" else "다음 안내", trailing = {
                Pill(if (osm.simulated) "시뮬레이션 ×${(osm.simKmh / 80).toInt()}" else "GPS", if (osm.simulated) HmgColors.Warn else HmgColors.Good)
            }) {
                if (g.arrived) {
                    Text("목적지에 도착했습니다", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Good)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(KoreanInstructions.arrow(next), fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Accent, modifier = Modifier.width(84.dp))
                        Column {
                            Text(KoreanInstructions.distShort(g.distToNextM), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                            Text(next?.text ?: "경로를 따라 직진", fontSize = 17.sp, color = HmgColors.Sub)
                        }
                    }
                }
                osm.message?.let { Text(it, color = HmgColors.Warn, fontSize = 13.sp) }
                if (osm.rerouting) Text("경로 재탐색 중…", color = HmgColors.Warn, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                else if (g.offRoute) Text("경로를 벗어났습니다", color = HmgColors.Bad, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Panel(Modifier.fillMaxWidth(), title = "남은 경로") {
                val etaSec = g.remainingS.toLong()
                val arrive = java.time.LocalTime.now().plusSeconds(etaSec)
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Metric("남은 거리", "%.1f".format(g.remainingM / 1000.0), "km")
                    Metric("남은 시간", "${etaSec / 3600}:${"%02d".format(etaSec / 60 % 60)}", "")
                    Metric("도착", "%02d:%02d".format(arrive.hour, arrive.minute), "")
                }
                // 도착 배터리: 남은 거리 기준 (실측 전비가 있으면 그 값)
                val usedSoc = if (settings.efficiencyKmPerKwh > 0) g.remainingM / 1000.0 / settings.efficiencyKmPerKwh / energy.capacityKwh * 100 else 0.0
                val arrSoc = energy.socPercent - usedSoc
                Text("도착 시 배터리 약 ${"%.0f".format(arrSoc.coerceAtLeast(0.0))}%", fontSize = 14.sp,
                    color = if (arrSoc <= 0) HmgColors.Bad else HmgColors.Sub)
                val level = fatigue.assessment?.level
                if (level != null && level >= 2) Text("피로도 Level $level — 가까운 휴게소에서 쉬어 가세요", color = fColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (osm.simulated) OutlinedButton(onClick = a.onOsmSimSpeed, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("속도 ×${(osm.simKmh / 80).toInt()}") }
                OutlinedButton(onClick = a.onOsmReroute, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("재탐색") }
            }
            Button(onClick = a.onStopOsm, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                Text(if (g.arrived) "닫기" else "안내 종료", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Pleos 내비에 요청한 안내의 상태와 제어(재탐색·경로 옵션·취소). 내비 앱에서 직접 바꾼 경로는 여기에 반영되지 않는다. */
@Composable
private fun NaviPanel(navi: NaviSession, a: NavActions) {
    val (label, color) = when (navi.phase) {
        NaviPhase.IDLE -> "안내 없음" to HmgColors.Sub
        NaviPhase.REQUESTED -> "경로 탐색 중" to HmgColors.Warn
        NaviPhase.GUIDING -> "안내 중" to HmgColors.Good
        NaviPhase.ARRIVED -> "도착" to HmgColors.Good
        NaviPhase.ERROR -> "오류" to HmgColors.Bad
    }
    Panel(Modifier.fillMaxWidth(), title = "내비 안내", trailing = { Pill(label, color) }) {
        if (navi.phase == NaviPhase.IDLE) {
            Text("요청한 경로가 없습니다. 목적지를 고르고 '안내 시작'을 누르세요.", color = HmgColors.Sub, fontSize = 14.sp)
            return@Panel
        }
        Text(navi.destination ?: "목적지", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (navi.waypoints.isNotEmpty()) {
            Text("경유 ${navi.waypoints.joinToString(" → ")}", color = HmgColors.Sub, fontSize = 14.sp)
        }
        // Pleos 내비 실시간 안내 (다음 회전 / 남은 거리·시간)
        navi.nextTurn?.let { turn ->
            val dist = navi.nextTurnMeters?.let { m -> if (m >= 1000) "%.1f km".format(m / 1000) else "${m.toInt()} m" }
            Text(listOfNotNull(dist, turn).joinToString(" 후 "), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = HmgColors.Text)
        }
        if (navi.remainMeters != null || navi.remainSeconds != null) {
            val km = navi.remainMeters?.let { "%.1f km".format(it / 1000.0) }
            val min = navi.remainSeconds?.let { s -> "${s / 3600}:${"%02d".format((s % 3600) / 60)}" }
            Text(listOfNotNull(km?.let { "남은 거리 $it" }, min?.let { "남은 시간 $it" }).joinToString(" · "), color = HmgColors.Sub, fontSize = 14.sp)
        }
        navi.message?.let { Text(it, color = if (navi.phase == NaviPhase.ERROR) HmgColors.Bad else HmgColors.Warn, fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NaviRouteOption.entries.forEach { o ->
                val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                if (o == navi.option) Button(onClick = { a.onRouteOption(o) }, modifier = modifier) { Text(o.label) }
                else OutlinedButton(onClick = { a.onRouteOption(o) }, modifier = modifier) { Text(o.label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = a.onReroute, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("재탐색") }
            OutlinedButton(onClick = a.onCancelRoute, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("경로 취소") }
        }
    }
}
