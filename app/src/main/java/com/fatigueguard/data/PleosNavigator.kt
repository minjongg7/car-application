package com.fatigueguard.data

import ai.pleos.playground.navi.constants.*
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fatigueguard.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 내비 요청 결과. UNAVAILABLE 이면 호출자가 geo: 인텐트로 폴백한다. */
internal enum class GuideResult { WAYPOINT_ADDED, DESTINATION_SET, UNAVAILABLE }

/**
 * Pleos NaviHelper SDK(ai.pleos.playground:NaviHelper:3.0.5) 직접 호출 버전 (리플렉션 제거).
 *
 * 흐름 (공식 문서 순서): NaviHelper(context) → initialize(onConnected, onFailed) → addListener
 *  → getDestinationInfo() 로 "이미 안내 중인 목적지가 있는지" 확인 (결과는 onDestinationInfo 콜백)
 *  → 목적지 있음: addWaypoint(FIRST) 로 휴게소를 경유지로 추가 (운전자의 원래 목적지 유지)
 *  → 목적지 없음/응답 없음: requestRoute(RouteInfo) 로 휴게소를 목적지로 설정
 *  → release() 시 removeListener → release.
 *
 * requestRoute/addWaypoint 호출 성공이 탐색 완료는 아니다. 완료/오류는 onRoutingCompleted/onError 로 알린다.
 * 모든 상태는 메인 스레드에서만 접근한다 (SDK 콜백은 main.post 로 옮긴다). Logcat 태그: PleosNavi.
 */
internal class PleosNavigator(context: Context) {

    companion object {
        private const val TAG = "PleosNavi"
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val DESTINATION_CHECK_TIMEOUT_MS = 1_500L

        /** Pleos Navigation 앱은 Automotive 기기에만 있다. -PnaviHelper=false 빌드에서는 NaviHelper 를 아예 호출하지 않는다. */
        fun isSupported(context: Context): Boolean =
            BuildConfig.NAVI_HELPER_ENABLED &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var helper: NaviHelper? = null
    private var connected = false
    private var busy = false
    private var destinationWaiter: ((Boolean) -> Unit)? = null
    private var messageSink: (String) -> Unit = {}

    /** 우리 앱이 요청한 안내의 상태 (화면이 구독). 내비 앱에서 직접 바꾼 경로는 반영하지 못한다. */
    private val _session = MutableStateFlow(NaviSession())
    val session: StateFlow<NaviSession> = _session.asStateFlow()

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "onRouteStarted $routeStartInfo")
            _session.update { it.copy(phase = NaviPhase.REQUESTED, message = null) }
            say("내비가 경로 탐색을 시작했습니다.")
        }

        override fun onRoutingCompleted() {
            Log.i(TAG, "onRoutingCompleted")
            _session.update { it.copy(phase = NaviPhase.GUIDING, message = null) }
            say("경로 탐색이 완료되었습니다.")
            main.post {
                helper?.let { navi ->
                    runCatching { navi.getTBTInfo() }.onFailure { e -> Log.w(TAG, "getTBTInfo 실패", e) }
                }
            }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            // 목적지가 없을 때 이 콜백이 오는지/빈 값이 오는지는 에뮬레이터에서 확인 필요 (로그: PleosNavi dest=...)
            Log.i(TAG, "dest=$destinationInfo")
            main.post { destinationWaiter?.invoke(true) }
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "onError $errorCode")
            val text = when (errorCode) {
                NaviErrorCode.CAUSE_LOW_BATTERY -> "배터리가 부족해 경로를 만들 수 없습니다."
                NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "검색 가능한 거리를 넘어 경로를 만들 수 없습니다."
                NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "목적지가 너무 가깝습니다."
                NaviErrorCode.CAUSE_SEARCH_ROUTE -> "경로 탐색 서버 오류가 발생했습니다."
                else -> "내비 오류: $errorCode"
            }
            _session.update { it.copy(phase = NaviPhase.ERROR, message = text) }
            say(text)
        }

        // ---- 실시간 안내 정보 (Pleos 내비 → 우리 앱 패널)
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val next = tbtInfoList.firstOrNull() ?: return
            _session.update { it.copy(phase = NaviPhase.GUIDING, nextTurn = next.description, nextTurnMeters = next.distance) }
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val d = drivingInfo.destination
            _session.update {
                it.copy(
                    phase = if (it.phase == NaviPhase.ARRIVED) it.phase else NaviPhase.GUIDING,
                    destination = d.name.ifBlank { it.destination },
                    remainMeters = d.distance, remainSeconds = d.duration,
                    waypoints = drivingInfo.waypoints.map { w -> w.name }.filter { n -> n.isNotBlank() }.ifEmpty { it.waypoints },
                )
            }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "onDestinationArrived $destinationArrivedInfo")
            _session.update { it.copy(phase = NaviPhase.ARRIVED, arrived = true, remainMeters = 0, remainSeconds = 0, nextTurn = null, nextTurnMeters = null) }
            say("${destinationArrivedInfo.name.ifBlank { "목적지" }}에 도착했습니다.")
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "onRouteCancelled")
            _session.update { NaviSession(option = it.option) }
        }

        override fun onRouteEnded() {
            Log.i(TAG, "onRouteEnded")
            _session.update { if (it.phase == NaviPhase.ARRIVED) it else NaviSession(option = it.option) }
        }

        // 문서 예제(onRouteOptionChanged(routeOption: RouteOption))와 같은 시그니처
        override fun onRouteOptionChanged(routeOption: RouteOption) {
            Log.i(TAG, "onRouteOptionChanged $routeOption")
            val mapped = if (routeOption == RouteOption.HIGHWAY) NaviRouteOption.HIGHWAY else NaviRouteOption.RECOMMENDED
            _session.update { it.copy(option = mapped) }
        }
    }

    /**
     * 휴게소 길안내. 반드시 메인 스레드에서 호출. 결과는 메인 스레드에서 onResult 로 한 번만 전달된다.
     * onMessage 는 내비 이벤트(탐색 완료/오류) 안내용으로 이후에도 호출될 수 있다.
     */
    fun guide(
        name: String, lat: Double, lon: Double,
        onResult: (GuideResult) -> Unit,
        onMessage: (String) -> Unit = {},
        /** true(휴게소·충전소): 안내 중이면 경유지로 추가. false(새 목적지): 항상 목적지로 경로 요청. */
        preferWaypoint: Boolean = true,
    ) {
        messageSink = onMessage
        if (!isSupported(appContext)) { onResult(GuideResult.UNAVAILABLE); return }
        if (busy) { onMessage("내비 요청을 처리 중입니다. 잠시 후 다시 눌러 주세요."); return }
        busy = true
        val finish: (GuideResult) -> Unit = { r -> busy = false; record(r, name); onResult(r) }

        withConnection(
            onReady = { navi ->
                if (!preferWaypoint) finish(send(navi, false, name, lat, lon))
                else checkDestination(navi) { hasDestination ->
                    finish(send(navi, hasDestination, name, lat, lon))
                }
            },
            onFailed = { finish(GuideResult.UNAVAILABLE) },
        )
    }

    /** 안내 중인 경로(목적지·경유지)를 취소한다. 반드시 메인 스레드에서 호출. */
    fun cancelRoute(onMessage: (String) -> Unit = {}) =
        command(onMessage, "경로를 취소했습니다.") { navi ->
            navi.cancelRoute()
            _session.update { NaviSession(option = it.option) }
        }

    /** 현재 위치 기준으로 경로를 다시 탐색한다. */
    fun reroute(onMessage: (String) -> Unit = {}) =
        command(onMessage, "현재 위치 기준으로 경로를 다시 탐색합니다.") { navi ->
            navi.requestReRoute()
            _session.update { it.copy(phase = NaviPhase.REQUESTED, message = null) }
        }

    /** 경로 안내 옵션(추천/고속도로) 변경. 적용 결과는 onRouteOptionChanged 로 확인한다. */
    fun setRouteOption(option: NaviRouteOption, onMessage: (String) -> Unit = {}) =
        command(onMessage, "경로 옵션을 ${option.label}(으)로 변경 요청했습니다.") { navi ->
            navi.changeRouteOption(
                when (option) {
                    NaviRouteOption.RECOMMENDED -> RouteOption.RECOMMENDED
                    NaviRouteOption.HIGHWAY -> RouteOption.HIGHWAY
                }
            )
        }

    fun release() {
        val navi = helper
        helper = null
        connected = false
        busy = false
        destinationWaiter = null
        if (navi == null) return
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    /** 연결돼 있으면 바로, 아니면 새로 initialize 해서 onReady 를 부른다. 시간 초과/실패는 onFailed. */
    private fun withConnection(onReady: (NaviHelper) -> Unit, onFailed: () -> Unit) {
        val existing = helper
        if (connected && existing != null) { onReady(existing); return }

        release() // 이전 시도가 연결에 실패했다면 정리하고 다시 만든다
        val navi = try {
            NaviHelper(appContext)
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper 생성 실패", t); onFailed(); return
        }
        helper = navi

        var settled = false
        fun settle(ok: Boolean) {
            if (settled) return
            settled = true
            if (ok) onReady(navi) else { release(); onFailed() }
        }
        main.postDelayed({
            if (!settled) Log.w(TAG, "NaviHelper 연결 시간 초과 (${CONNECT_TIMEOUT_MS}ms)")
            settle(false)
        }, CONNECT_TIMEOUT_MS)

        try {
            navi.initialize(
                onConnected = {
                    main.post {
                        if (helper === navi && !settled) {
                            connected = true
                            navi.addListener(listener)
                            settle(true)
                        }
                    }
                },
                onFailed = { t ->
                    Log.w(TAG, "NaviHelper 연결 실패", t)
                    main.post { settle(false) }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper.initialize 실패", t)
            settle(false)
        }
    }

    /** getDestinationInfo() 를 호출하고 onDestinationInfo 가 오면 true, 시간 안에 안 오면 false 로 done 을 한 번 부른다. */
    private fun checkDestination(navi: NaviHelper, done: (Boolean) -> Unit) {
        var fired = false
        val waiter: (Boolean) -> Unit = { has ->
            if (!fired) { fired = true; destinationWaiter = null; done(has) }
        }
        destinationWaiter = waiter
        main.postDelayed({ waiter(false) }, DESTINATION_CHECK_TIMEOUT_MS)
        try {
            navi.getDestinationInfo()
        } catch (t: Throwable) {
            Log.w(TAG, "getDestinationInfo 실패", t)
            waiter(false)
        }
    }

    /** 문서 예시와 같은 이름 있는 인자로 호출한다 (위·경도 순서 실수 방지). */
    private fun send(navi: NaviHelper, hasDestination: Boolean, name: String, lat: Double, lon: Double): GuideResult =
        try {
            if (hasDestination) {
                navi.addWaypoint(
                    RequestWaypointInfo(
                        latitude = lat,
                        longitude = lon,
                        waypointIndex = WaypointIndex.FIRST,
                        poiName = name,
                        // navi-data 3.0.4 에서 필수(String). 오프라인 좌표 POI 라 ID/주소가 없으므로 빈 값, 순번은 "0".
                        poiId = "",
                        address = "",
                        poiSubId = "0",
                    )
                )
                GuideResult.WAYPOINT_ADDED
            } else {
                navi.requestRoute(
                    RouteInfo(
                        latitude = lat,
                        longitude = lon,
                        poiName = name,
                        poiId = "",
                        address = "",
                        poiSubId = "0",
                    )
                )
                GuideResult.DESTINATION_SET
            }
        } catch (t: Throwable) {
            // SecurityException 이면 메시지에 필요한 권한 이름이 들어 있다.
            Log.w(TAG, "내비 요청 실패", t)
            say("내비 요청 실패: ${t.message}")
            GuideResult.UNAVAILABLE
        }

    /** guide() 결과를 화면 상태에 반영한다. */
    private fun record(r: GuideResult, name: String) {
        when (r) {
            GuideResult.DESTINATION_SET ->
                _session.update { NaviSession(phase = NaviPhase.REQUESTED, destination = name, option = it.option) }
            GuideResult.WAYPOINT_ADDED ->
                _session.update { it.copy(waypoints = (it.waypoints + name).takeLast(2), message = null) }
            GuideResult.UNAVAILABLE -> Unit
        }
    }

    /** 연결을 확보한 뒤 SDK 명령 하나를 실행하고 결과 문구를 전달한다. */
    private fun command(onMessage: (String) -> Unit, okMessage: String, call: (NaviHelper) -> Unit) {
        messageSink = onMessage
        if (!isSupported(appContext)) { onMessage("Pleos 내비가 없는 기기에서는 사용할 수 없습니다."); return }
        if (busy) { onMessage("내비 요청을 처리 중입니다. 잠시 후 다시 눌러 주세요."); return }
        busy = true
        withConnection(
            onReady = { navi ->
                busy = false
                try {
                    call(navi)
                    onMessage(okMessage)
                } catch (t: Throwable) {
                    // SecurityException 이면 메시지에 필요한 권한 이름이 들어 있다.
                    Log.w(TAG, "내비 명령 실패", t)
                    onMessage("내비 요청 실패: ${t.message}")
                }
            },
            onFailed = { busy = false; onMessage("Pleos 내비에 연결하지 못했습니다.") },
        )
    }

    private fun say(msg: String) { main.post { messageSink(msg) } }
}
