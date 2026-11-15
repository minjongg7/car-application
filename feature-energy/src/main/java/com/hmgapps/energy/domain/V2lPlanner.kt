package com.hmgapps.energy.domain

import kotlin.math.max

/** V2L 로 연결한 외부 기기. priority 1 = 필수(조명·의료기기 등), 숫자가 클수록 먼저 끊는다. */
data class V2lLoad(
    val id: String,
    val name: String,
    val watts: Int,
    val priority: Int,
    val requested: Boolean = true,
)

enum class GuardState {
    /** 락까지 여유 있음. */
    OK,
    /** 락까지 LOW_MARGIN 이내: 비필수 기기(priority ≥ 3)부터 차단해 남은 전력을 아낀다. */
    LOW,
    /** 락 도달: 모든 V2L 출력 차단 (출근용 전력 보호). */
    LOCKED,
}

enum class ShedReason { OVER_CAPACITY, LOW_ENERGY, LOCKED }

data class V2lPlan(
    val lockSoc: Int,
    val state: GuardState,
    val active: List<V2lLoad>,
    val shed: Map<String, ShedReason>,
    val totalWatts: Int,
    val usableKwh: Double,
    /** 현재 부하로 락까지 남은 시간(분). 부하가 없으면 null. */
    val runtimeMinutes: Int?,
)

/**
 * 스마트 방전 관리. "내일 출근용 최소 전력"을 락으로 잡고 그 위의 전력만 분배한다.
 *
 * 분배 규칙
 * 1. LOCKED(잔량 ≤ 락) → 전부 차단.
 * 2. LOW(잔량 ≤ 락 + LOW_MARGIN) → priority ≥ 3 차단.
 * 3. 남은 기기를 (우선순위 오름차순, 전력 오름차순)으로 V2L 최대 출력 안에서 채운다. 초과분은 OVER_CAPACITY 로 차단.
 */
object V2lPlanner {
    /** V2L 최대 출력 예시값(W). 차종마다 다르므로 실제 차량 사양으로 바꿀 것. */
    const val DEFAULT_MAX_WATTS = 3_600
    const val LOW_MARGIN = 5.0

    fun plan(
        loads: List<V2lLoad>,
        socPercent: Double,
        capacityKwh: Double,
        lockSoc: Int,
        maxWatts: Int = DEFAULT_MAX_WATTS,
    ): V2lPlan {
        val usable = max(0.0, (socPercent - lockSoc) / 100.0 * capacityKwh)
        val state = when {
            socPercent <= lockSoc -> GuardState.LOCKED
            socPercent <= lockSoc + LOW_MARGIN -> GuardState.LOW
            else -> GuardState.OK
        }
        val shed = linkedMapOf<String, ShedReason>()
        val active = mutableListOf<V2lLoad>()
        var total = 0
        loads.filter { it.requested }
            .sortedWith(compareBy<V2lLoad> { it.priority }.thenBy { it.watts })
            .forEach { l ->
                when {
                    state == GuardState.LOCKED -> shed[l.id] = ShedReason.LOCKED
                    state == GuardState.LOW && l.priority >= 3 -> shed[l.id] = ShedReason.LOW_ENERGY
                    total + l.watts > maxWatts -> shed[l.id] = ShedReason.OVER_CAPACITY
                    else -> { active += l; total += l.watts }
                }
            }
        val runtime = if (total > 0) (usable * 1000.0 / total * 60.0).toInt() else null
        return V2lPlan(lockSoc, state, active, shed, total, usable, runtime)
    }

    val DEFAULT_LOADS = listOf(
        V2lLoad("light", "LED 캠핑 조명", 60, 1),
        V2lLoad("fridge", "캠핑 냉장고", 120, 1),
        V2lLoad("blanket", "전기장판", 200, 2),
        V2lLoad("laptop", "노트북·충전기", 90, 2),
        V2lLoad("kettle", "전기포트", 1_500, 3),
        V2lLoad("grill", "전기 그릴", 1_800, 3),
        V2lLoad("heater", "전기 히터", 2_000, 4, requested = false),
    )
}
