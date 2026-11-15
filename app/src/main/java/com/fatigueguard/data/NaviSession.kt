package com.fatigueguard.data

/** 우리 앱이 Pleos 내비에 요청한 안내의 진행 상태. */
enum class NaviPhase { IDLE, REQUESTED, GUIDING, ARRIVED, ERROR }

/** 경로 옵션. 문서 예제에서 확인한 값만 쓴다 (RouteOption.RECOMMENDED / HIGHWAY). */
enum class NaviRouteOption(val label: String) { RECOMMENDED("추천"), HIGHWAY("고속도로") }

data class NaviSession(
    val phase: NaviPhase = NaviPhase.IDLE,
    val destination: String? = null,
    val waypoints: List<String> = emptyList(),
    val option: NaviRouteOption = NaviRouteOption.RECOMMENDED,
    val message: String? = null,
    /** Pleos 내비가 보내는 실시간 안내 (onTBTInfo / onDrivingInfo). */
    val nextTurn: String? = null,
    val nextTurnMeters: Double? = null,
    val remainMeters: Int? = null,
    val remainSeconds: Int? = null,
    val arrived: Boolean = false,
)
