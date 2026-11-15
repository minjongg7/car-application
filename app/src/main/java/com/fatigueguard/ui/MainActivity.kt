package com.fatigueguard.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.fatigueguard.data.ConsentStore
import com.fatigueguard.data.GuideResult
import com.fatigueguard.data.NaviPhase
import com.fatigueguard.data.NaviSession
import com.fatigueguard.data.PleosNavigator
import com.fatigueguard.data.PleosSpeedBinding
import com.fatigueguard.domain.RestAreaCandidate
import com.fatigueguard.nav.NavActions
import com.fatigueguard.nav.NavHome
import com.fatigueguard.nav.NavViewModel
import com.fatigueguard.nav.GeoPoint
import com.fatigueguard.nav.Poi
import com.fatigueguard.nav.osm.LatLon
import com.fatigueguard.nav.osm.OsmNavState
import com.fatigueguard.nav.osm.OsmNavigator
import com.fatigueguard.nav.osm.OsmRoute
import com.hmgapps.core.energy.EnergySettingsStore
import com.hmgapps.core.energy.VehicleEnergy
import com.hmgapps.core.pleos.PleosVehicleHub
import com.hmgapps.core.trip.GeoPt
import com.hmgapps.core.trip.RestArea
import com.hmgapps.core.trip.TripBridge
import com.hmgapps.core.trip.TripUpdate
import com.hmgapps.core.ui.AppLinks
import com.hmgapps.core.ui.AppScaffold
import com.hmgapps.core.ui.CarPermissions
import com.hmgapps.core.ui.Dest
import com.hmgapps.core.ui.HmgShell
import com.hmgapps.core.ui.HmgTheme
import com.hmgapps.core.ui.Panel
import com.hmgapps.core.ui.SourcePill
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: FatigueViewModel by viewModels()
    private val navVm: NavViewModel by viewModels()

    // 결과와 무관하게 동작한다: 위치가 거부되면 속도만으로 피로도를 계산하고 휴게소 추천은 생략된다.
    // CAR_SPEED 가 거부되면 시작 시 "텔레메트리 오류" 안내가 뜬다. CAR_ENERGY 가 거부되면 배터리는 데모 값으로 동작한다.
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { VehicleEnergy.retry(this) }

    private val navigator by lazy { PleosNavigator(this) }

    // 내장(OSM) 턴바이턴. 위치 동의가 꺼져 있으면 GPS 를 읽지 않고 시뮬레이션으로 안내한다.
    private val osm by lazy { OsmNavigator(this, lifecycleScope) { ConsentStore(this).load().location } }

    /** Pleos 내비에 목적지로 요청한 곳 (onDrivingInfo 에는 좌표가 없어서 Family Care 경로상 휴게소 계산용으로 기억). */
    private var pleosDest: Poi? = null
    private var sharedRoute: OsmRoute? = null
    private var sharedRoutePts: List<GeoPt>? = null

    /** Family Care '다음 휴게소에서 쉬어갈까요?' → 네: 휴게소를 경유지로 추가. */
    private val restStopHandler = TripBridge.WaypointHandler { area, onMessage -> addRestStop(area, onMessage) }

    /** 필요한 런타임 권한 중 아직 없는 것만 요청한다. (CAR_SPEED 는 Automotive 에서만, 위치는 동의가 켜진 경우만) */
    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf<String>()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            wanted += PleosSpeedBinding.PERMISSION_CAR_SPEED
        }
        wanted += CarPermissions.missingEnergy(this)
        if (ConsentStore(this).load().location) {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
            wanted += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) applyTabExtra(intent)
        requestPermissionsIfNeeded()
        EnergySettingsStore.load(this)
        // 여정 공유: Pleos 내비(NaviHelper onDrivingInfo) 또는 내장 안내의 남은 거리·시간·위치 → Family Care 여정 지도/휴식 추천.
        // Activity 가 멈춰 있어도(다른 화면을 보는 중) 내비 콜백은 계속 오므로 onDestroy 까지 구독한다.
        TripBridge.waypointHandler = restStopHandler
        lifecycleScope.launch {
            combine(navigator.session, osm.state, vm.state) { n, o, s -> Triple(n, o, s) }
                .collect { (n, o, s) -> publishTrip(n, o, s) }
        }
        setContent {
            HmgTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                val nav by navVm.state.collectAsStateWithLifecycle()
                val energy by VehicleEnergy.state.collectAsStateWithLifecycle()
                val settings by EnergySettingsStore.settings.collectAsStateWithLifecycle()
                val vehicle by PleosVehicleHub.state.collectAsStateWithLifecycle()
                val navi by navigator.session.collectAsStateWithLifecycle()
                val osmState by osm.state.collectAsStateWithLifecycle()
                HmgShell(
                    current = if (nav.tab == HomeTab.NAV) Dest.NAV else Dest.FATIGUE,
                    onSelect = { d ->
                        when (d) {
                            Dest.NAV -> navVm.openTab(HomeTab.NAV)
                            Dest.FATIGUE -> navVm.openTab(HomeTab.FATIGUE)
                            else -> AppLinks.open(this@MainActivity, d)
                        }
                    },
                ) {
                    when (nav.tab) {
                        HomeTab.NAV -> AppScaffold(
                            title = "내비",
                            subtitle = "목적지 → 사전 점검(피로도·배터리·정차지) → 내장 안내(OSM) 또는 Pleos 내비",
                            actions = { SourcePill(energy.live) },
                        ) {
                            s.notice?.let { n ->
                                Panel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(n, Modifier.weight(1f), fontSize = 16.sp)
                                        OutlinedButton(onClick = vm::dismissNotice) { Text("닫기") }
                                    }
                                }
                            }
                            NavHome(
                                nav, s, energy, settings, vehicle, navi, osmState,
                                NavActions(
                                    onQuery = { q -> navVm.query(q, GeoPoint(s.lat, s.lon)) },
                                    onSelect = { p -> navVm.select(p, GeoPoint(s.lat, s.lon)) },
                                    onClear = navVm::clear,
                                    pleosNaviAvailable = PleosNavigator.isSupported(this@MainActivity),
                                    onGuide = { p -> guidePoi(p, preferWaypoint = false) },
                                    onAddStop = { p -> navVm.addVia(p, GeoPoint(s.lat, s.lon)) },
                                    onOpenFatigue = { navVm.openTab(HomeTab.FATIGUE) },
                                    onOpenEnergy = { AppLinks.open(this@MainActivity, Dest.ENERGY) },
                                    onCancelRoute = { navigator.cancelRoute(onMessage = vm::showNotice) },
                                    onReroute = { navigator.reroute(onMessage = vm::showNotice) },
                                    onRouteOption = { o -> navigator.setRouteOption(o, onMessage = vm::showNotice) },
                                    onStartOsm = { sim ->
                                        val r = navVm.state.value.route
                                        if (r == null) vm.showNotice("경로 서버에 연결되지 않아 안내를 시작할 수 없습니다. 네트워크를 확인하거나 'Pleos 내비로'를 사용하세요.")
                                        else osm.start(r, simulate = sim)
                                    },
                                    onStopOsm = osm::stop,
                                    onOsmReroute = { osm.reroute() },
                                    onOsmSimSpeed = osm::cycleSimSpeed,
                                    onRetryRoute = { navVm.retryRoute(GeoPoint(s.lat, s.lon)) },
                                ),
                            )
                        }
                        HomeTab.FATIGUE -> FatigueScreen(s, vm, onNavigate = ::navigateTo)
                    }
                }
            }
        }
    }

    // launchMode=singleTask: 다른 앱 화면의 레일에서 "피로도"/"내비"를 누르면 이 메서드로 탭이 전달된다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyTabExtra(intent)
    }

    private fun applyTabExtra(i: Intent?) {
        val name = i?.getStringExtra(AppLinks.EXTRA_TAB) ?: return
        HomeTab.entries.firstOrNull { it.name == name }?.let(navVm::openTab)
    }

    override fun onStart() { super.onStart(); VehicleEnergy.acquire(this) }
    override fun onStop() { VehicleEnergy.release(); super.onStop() }

    /** 내비 홈의 목적지/경유지 안내. 피로도 화면과 같은 NaviHelper → geo: 폴백 경로를 쓴다. */
    private fun guidePoi(p: Poi, preferWaypoint: Boolean) {
        navigator.guide(
            p.name, p.lat, p.lon,
            onResult = { result ->
                when (result) {
                    GuideResult.WAYPOINT_ADDED -> vm.showNotice("${p.name}을(를) 경유지로 추가했습니다.")
                    GuideResult.DESTINATION_SET -> { pleosDest = p; vm.showNotice("${p.name}(으)로 경로를 요청했습니다.") }
                    GuideResult.UNAVAILABLE -> openGeo(p.name, p.lat, p.lon)
                }
            },
            onMessage = { msg -> vm.showNotice(msg) },
            preferWaypoint = preferWaypoint,
        )
    }

    /**
     * 길안내. Pleos 내비(NaviHelper)에 요청한다.
     * 이미 안내 중인 목적지가 있으면 휴게소를 경유지로 추가하고(원래 목적지 유지), 없으면 휴게소를 목적지로 설정한다.
     * 불가능하면 geo: 인텐트로 기본 내비 앱에 위임한다.
     */
    private fun navigateTo(r: RestAreaCandidate) {
        vm.speakRestGuide(r)
        navigator.guide(
            r.name, r.lat, r.lon,
            onResult = { result ->
                when (result) {
                    GuideResult.WAYPOINT_ADDED ->
                        vm.showNotice("${r.name}을(를) 경유지로 추가했습니다. 기존 목적지는 그대로입니다.")
                    GuideResult.DESTINATION_SET -> {
                        pleosDest = Poi(r.name, com.fatigueguard.nav.PoiType.REST, r.lat, r.lon)
                        vm.showNotice("${r.name}(으)로 경로를 요청했습니다.")
                    }
                    GuideResult.UNAVAILABLE -> openGeo(r.name, r.lat, r.lon)
                }
            },
            onMessage = { msg -> vm.showNotice(msg) },
        )
    }

    /** 내비 상태 → TripBridge. 내장 안내가 우선, 없으면 Pleos 내비, 둘 다 없으면 여정 종료 + 현재 위치만. */
    private fun publishTrip(n: NaviSession, o: OsmNavState, s: UiState) {
        val route = o.route
        if (o.running && route != null) {
            val pts = if (route === sharedRoute) sharedRoutePts else route.points.map { GeoPt(it.lat, it.lon) }
            sharedRoute = route; sharedRoutePts = pts
            val started = o.g.position != null
            val pos = o.g.position
            TripBridge.update(
                TripUpdate(
                    source = TripBridge.SOURCE_OSM,
                    destination = navVm.state.value.destination?.name ?: "목적지",
                    destLat = route.points.lastOrNull()?.lat, destLon = route.points.lastOrNull()?.lon,
                    remainMeters = (if (started) o.g.remainingM else route.distanceM).toInt(),
                    remainSeconds = (if (started) o.g.remainingS else route.durationS).toInt(),
                    lat = pos?.lat ?: s.lat, lon = pos?.lon ?: s.lon,
                    route = pts,
                    arrived = o.g.arrived,
                )
            )
            return
        }
        TripBridge.end(TripBridge.SOURCE_OSM)
        val guiding = n.phase == NaviPhase.GUIDING || n.phase == NaviPhase.REQUESTED || n.phase == NaviPhase.ARRIVED
        if (guiding && (n.remainMeters != null || n.arrived)) {
            val dest = pleosDest
            TripBridge.update(
                TripUpdate(
                    source = TripBridge.SOURCE_PLEOS,
                    destination = n.destination ?: dest?.name,
                    destLat = dest?.lat, destLon = dest?.lon,
                    remainMeters = n.remainMeters, remainSeconds = n.remainSeconds,
                    lat = s.lat, lon = s.lon,
                    arrived = n.arrived,
                )
            )
            return
        }
        if (!guiding) TripBridge.end(TripBridge.SOURCE_PLEOS)
        TripBridge.reportPosition(s.lat, s.lon)
    }

    /** 경유지 추가: 내장 안내 중이면 OSRM 재탐색, 아니면 Pleos 내비 NaviHelper.addWaypoint (안내 중이 아니면 목적지로). */
    private fun addRestStop(area: RestArea, onMessage: (String) -> Unit) {
        if (osm.state.value.running) {
            osm.addWaypoint(area.name, LatLon(area.lat, area.lon), onMessage)
            return
        }
        navigator.guide(
            area.name, area.lat, area.lon,
            onResult = { result ->
                val msg = when (result) {
                    GuideResult.WAYPOINT_ADDED -> "${area.name}을(를) 경유지로 추가했어요. 목적지는 그대로예요."
                    GuideResult.DESTINATION_SET -> {
                        pleosDest = Poi(area.name, com.fatigueguard.nav.PoiType.REST, area.lat, area.lon)
                        "${area.name}(으)로 길안내를 요청했어요."
                    }
                    GuideResult.UNAVAILABLE -> "Pleos 내비에 연결하지 못했어요. 내비 화면에서 직접 경유지를 추가해 주세요."
                }
                vm.showNotice(msg)
                onMessage(msg)
            },
            onMessage = { msg -> vm.showNotice(msg) },
            preferWaypoint = true,
        )
    }

    private fun openGeo(name: String, lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            vm.showNotice("길안내 앱을 찾을 수 없습니다. 목적지: $name")
        }
    }

    override fun onDestroy() {
        if (TripBridge.waypointHandler === restStopHandler) TripBridge.waypointHandler = null
        TripBridge.end(TripBridge.SOURCE_OSM)
        TripBridge.end(TripBridge.SOURCE_PLEOS)
        navigator.release()
        osm.shutdown()
        super.onDestroy()
    }
}

enum class HomeTab { NAV, FATIGUE }
