package com.hmgapps.core.trip

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 내비(:app) ↔ Family Care(:feature-family) 연결 다리. 두 모듈은 서로 모르고 :core 만 공유한다.
 *
 * - 내비 → 여기: [update] 로 남은 거리·도착 예정 시간·위치를 올린다
 *   (Pleos 내비 NaviHelper `onDrivingInfo` 의 destination.distance/duration, 또는 내장 OSM 안내 GuidanceState).
 * - Family Care → 여기: [state] 를 구독해 여정 지도·스탬프·경로상 휴게소 추천을 그린다.
 * - Family Care → 내비: [addRestStop] 로 휴게소를 경유지에 넣어 달라고 부탁한다 (MainActivity 가 [waypointHandler] 를 등록,
 *   Pleos 내비가 있으면 NaviHelper.addWaypoint).
 *
 * 메인 스레드에서만 호출한다.
 */
object TripBridge {
    const val SOURCE_PLEOS = "Pleos 내비"
    const val SOURCE_OSM = "내장 안내"
    const val SOURCE_DEMO = "시연 여정"

    private val _state = MutableStateFlow(TripProgress())
    val state: StateFlow<TripProgress> = _state.asStateFlow()

    /** 휴게소를 경유지로 추가하는 실제 동작. 결과 문구는 onMessage 로 돌려준다. */
    fun interface WaypointHandler {
        fun add(area: RestArea, onMessage: (String) -> Unit)
    }

    var waypointHandler: WaypointHandler? = null

    fun update(u: TripUpdate, nowMs: Long = System.currentTimeMillis()) {
        // 시연 여정이 도는 동안에는 실제 내비 갱신이 섞이지 않게 한다 (시연은 Family Care 에서 끌 수 있다).
        val cur = _state.value
        if (cur.active && cur.source == SOURCE_DEMO && u.source != SOURCE_DEMO && !cur.arrived) return
        _state.update { TripMath.reduce(it, u, nowMs) }
    }

    /** 길안내가 없을 때의 현재 위치 (지역 퀴즈·가까운 휴게소용). 진행 중인 여정이 있으면 무시한다. */
    fun reportPosition(lat: Double, lon: Double) {
        _state.update { if (it.active) it else it.copy(lat = lat, lon = lon) }
    }

    /** source 가 보낸 여정을 끝낸다. 다른 출처의 여정이면 건드리지 않는다. 스탬프는 다음 여정 전까지 남긴다. */
    fun end(source: String) {
        _state.update { if (it.active && it.source == source) it.copy(active = false) else it }
    }

    /** 경유지 추가 요청. 처리할 내비가 없으면 false. */
    fun addRestStop(area: RestArea, onMessage: (String) -> Unit): Boolean {
        val h = waypointHandler ?: return false
        h.add(area, onMessage)
        return true
    }
}
