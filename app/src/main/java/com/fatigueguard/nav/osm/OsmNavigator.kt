package com.fatigueguard.nav.osm

import android.content.Context
import android.os.SystemClock
import com.fatigueguard.data.AndroidLocationSource
import com.fatigueguard.ui.TtsSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OsmNavState(
    val running: Boolean = false,
    val route: OsmRoute? = null,
    val g: GuidanceState = GuidanceState(),
    /** GPS 대신 경로를 따라 가상으로 이동 중 (에뮬레이터/시연용). */
    val simulated: Boolean = false,
    val simKmh: Double = 80.0,
    val rerouting: Boolean = false,
    val message: String? = null,
)

/**
 * 내장 턴바이턴 안내. 위치(GPS 또는 시뮬레이션) → GuidanceEngine → 화면 상태 + 한국어 음성.
 * 경로 이탈이 연속 3회 감지되면(10초 쿨다운) OSRM 으로 자동 재탐색한다.
 * 모든 상태 갱신은 전달받은 scope(메인)에서 일어난다.
 */
class OsmNavigator(
    context: Context,
    private val scope: CoroutineScope,
    /** 사용자가 위치 사용에 동의했는지 (동의가 꺼져 있으면 GPS 를 읽지 않고 시뮬레이션으로 안내). */
    private val gpsAllowed: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private val tts = TtsSpeaker(appContext) {}
    private val _state = MutableStateFlow(OsmNavState())
    val state: StateFlow<OsmNavState> = _state.asStateFlow()

    private var job: Job? = null
    private var engine: GuidanceEngine? = null
    private var destination: LatLon? = null
    private var lastRerouteAt = 0L
    @Volatile private var simKmh = 80.0

    fun start(route: OsmRoute, simulate: Boolean) {
        cancelJob()
        destination = route.points.last()
        val gps = !simulate && gpsAllowed() && AndroidLocationSource(appContext).isAvailable()
        engine = GuidanceEngine(route)
        _state.value = OsmNavState(
            running = true, route = route, g = GuidanceState(active = true),
            simulated = !gps, simKmh = simKmh,
            message = if (!gps && !simulate) "GPS를 쓸 수 없어(위치 동의 꺼짐 또는 신호 없음) 시뮬레이션 주행으로 안내합니다." else null,
        )
        tts.speak("경로 안내를 시작합니다. 총 ${KoreanInstructions.distWords(route.distanceM)}입니다.")
        job = scope.launch { if (gps) runGps() else runSim() }
    }

    fun stop() {
        cancelJob()
        engine = null
        destination = null
        _state.value = OsmNavState(simKmh = simKmh)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    fun cycleSimSpeed() {
        simKmh = when (simKmh) { 80.0 -> 240.0; 240.0 -> 800.0; else -> 80.0 }
        _state.update { it.copy(simKmh = simKmh) }
    }

    /** 현재 위치에서 목적지까지 다시 탐색 (수동 버튼 또는 이탈 시 자동). */
    fun reroute(auto: Boolean = false) {
        val dest = destination ?: return
        val start = _state.value.g.position ?: return
        if (_state.value.rerouting) return
        _state.update { it.copy(rerouting = true) }
        if (auto) tts.speak("경로를 이탈했습니다. 경로를 다시 탐색합니다.")
        scope.launch {
            try {
                val r = OsrmClient.route(listOf(start, dest))
                engine = GuidanceEngine(r)
                _state.update { it.copy(route = r, rerouting = false, message = null, g = GuidanceState(active = true, position = start)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(rerouting = false, message = "재탐색 실패: ${e.message}") }
            }
        }
    }

    /**
     * 안내 중에 경유지 하나를 넣어 다시 탐색한다 (Family Care '다음 휴게소에서 쉬어갈까요?' → 네).
     * 현재 위치 → 경유지 → 목적지. 결과 문구는 onMessage.
     */
    fun addWaypoint(name: String, via: LatLon, onMessage: (String) -> Unit) {
        val dest = destination ?: run { onMessage("내장 안내 중이 아닙니다."); return }
        val start = _state.value.g.position ?: run { onMessage("현재 위치를 아직 받지 못했습니다."); return }
        if (_state.value.rerouting) { onMessage("경로를 다시 찾는 중입니다. 잠시 후 다시 시도해 주세요."); return }
        _state.update { it.copy(rerouting = true) }
        scope.launch {
            try {
                val r = OsrmClient.route(listOf(start, via, dest))
                engine = GuidanceEngine(r)
                _state.update { it.copy(route = r, rerouting = false, message = null, g = GuidanceState(active = true, position = start)) }
                tts.speak("${name}을 경유지로 추가했습니다.")
                onMessage("${name}을(를) 경유지로 추가했습니다 (내장 안내).")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(rerouting = false) }
                onMessage("경유지 추가 실패: ${e.message}")
            }
        }
    }

    private fun cancelJob() { job?.cancel(); job = null }

    private suspend fun runGps() {
        try {
            AndroidLocationSource(appContext).updates(1_000L).collect { loc ->
                val hdg = if (loc.hasBearing() && loc.speed > 1.5f) loc.bearing.toDouble() else null
                onFix(LatLon(loc.latitude, loc.longitude), hdg)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = "위치 수신이 중단되었습니다: ${e.message}") }
        }
    }

    private suspend fun runSim() {
        var cur = _state.value.route ?: return
        var along = 0.0
        while (true) {
            val r = _state.value.route ?: return
            if (r !== cur) { cur = r; along = 0.0 } // 재탐색으로 경로가 바뀌면 새 경로의 처음부터
            onFix(Geo.pointAt(r, along), Geo.headingAt(r, along))
            if (_state.value.g.arrived) return
            delay(500)
            along = (along + simKmh / 3.6 * 0.5).coerceAtMost(r.lengthM)
        }
    }

    private fun onFix(p: LatLon, heading: Double?) {
        val eng = engine ?: return
        val g = eng.update(p, heading)
        _state.update { it.copy(g = g) }
        g.speak?.let { tts.speak(it) }
        if (g.arrived) {
            tts.speak("목적지에 도착했습니다. 안내를 종료합니다.")
            cancelJob()
            return
        }
        if (g.offRoute && !_state.value.simulated) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRerouteAt >= 10_000L) { lastRerouteAt = now; reroute(auto = true) }
        }
    }
}
