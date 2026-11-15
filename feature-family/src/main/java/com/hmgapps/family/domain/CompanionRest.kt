package com.hmgapps.family.domain

import com.hmgapps.core.trip.RestAhead
import kotlin.math.abs
import kotlin.math.min

/** 함께 탄 사람들. ages 는 기존 '탑승자 나이대', 나머지는 휴식 간격에 영향을 주는 상황. */
data class Companions(
    val ages: Set<AgeGroup>,
    val elderly: Boolean = false,
    val motionSick: Boolean = false,
)

/** 휴식 간격 근거 한 줄. intervalMin 은 앱 기본값(가정치)이며 의학적 기준이 아니다. */
data class RestReason(val who: String, val intervalMin: Int, val why: String)

/** 경로 위 휴게소 추천 결과. */
data class RestPick(val ahead: RestAhead, val matched: List<String>, val onTime: Boolean)

/**
 * 3. 동승자 맞춤 휴식 추천 (컨디션 케어).
 * 기존 휴식 간격(운전자 피로·나이대)을 동승자 기준으로도 넓히고, 경로 위 휴게소 중 수유실·놀이방이 있는 곳을 골라 준다.
 */
object CompanionRest {
    const val ELDERLY_MIN = 60
    const val MOTION_SICK_MAX_MIN = 60
    const val DEFAULT_MIN = 120
    /** 휴식 시각보다 이만큼 늦게 닿는 휴게소까지는 '제때'로 본다. */
    const val LATE_OK_MIN = 10
    /** 남은 시간이 이 안으로 들어오면 음성으로 먼저 물어본다. */
    const val ASK_BEFORE_MIN = 15

    fun reasons(c: Companions): List<RestReason> {
        val out = mutableListOf<RestReason>()
        c.ages.sortedBy { it.ordinal }.forEach { a ->
            out += when (a) {
                AgeGroup.TODDLER -> RestReason("영유아", a.restEveryMin, "카시트에 오래 앉지 않도록 1.5~2시간마다")
                AgeGroup.KID -> RestReason("유아", a.restEveryMin, "몸을 풀고 화장실에 들르도록")
                AgeGroup.CHILD, AgeGroup.TEEN -> RestReason(a.label, a.restEveryMin, "스트레칭하고 바람 쐬기")
                AgeGroup.ADULT -> RestReason("어른", a.restEveryMin, "장거리 운전 기본 휴식")
            }
        }
        if (c.elderly) out += RestReason("어르신", ELDERLY_MIN, "화장실 주기에 맞춘 휴식")
        if (c.motionSick) {
            val base = c.ages.minOfOrNull { it.restEveryMin } ?: DEFAULT_MIN
            out += RestReason("멀미가 잦은 아이", min(MOTION_SICK_MAX_MIN, base * 2 / 3), "더 짧은 간격으로 바깥 공기 쐬기")
        }
        if (out.isEmpty()) out += RestReason("운전자", DEFAULT_MIN, "장거리 운전 기본 휴식")
        return out
    }

    /** 가장 짧은 간격을 따른다. */
    fun intervalMin(c: Companions): Int = reasons(c).minOf { it.intervalMin }

    /** 가장 짧은 간격을 정한 사람 (안내 문구용). */
    fun strictest(c: Companions): RestReason = reasons(c).minBy { it.intervalMin }

    /**
     * 다음 휴식까지 남은 분. 0 이면 지금 쉴 때(이미 지났어도 0).
     * lastRestAtMin: 마지막으로 쉰 시점의 주행 시간(분). 없으면 출발부터 잰다.
     */
    fun minutesUntilRest(c: Companions, drivingMin: Int?, lastRestAtMin: Int? = null): Int? {
        val d = drivingMin ?: return null
        val since = (d - (lastRestAtMin ?: 0)).coerceAtLeast(0)
        return (intervalMin(c) - since).coerceAtLeast(0)
    }

    /** 이 동승자에게 필요한 편의시설. */
    fun wanted(c: Companions): List<String> = buildList {
        if (AgeGroup.TODDLER in c.ages) add("수유실")
        if (AgeGroup.KID in c.ages || AgeGroup.CHILD in c.ages || c.motionSick) add("놀이방")
    }

    private fun matches(c: Companions, a: RestAhead): List<String> = wanted(c).filter {
        when (it) {
            "수유실" -> a.area.nursingRoom
            "놀이방" -> a.area.kidsZone
            else -> false
        }
    }

    /**
     * 경로 위 휴게소 중에서 고른다.
     * 1) 휴식 시각(+10분)까지 닿는 곳 중 2) 필요한 편의시설이 많은 곳, 3) 휴식 시각에 가장 가까운 곳.
     * 제때 닿는 곳이 없으면 가장 가까운 곳(onTime=false).
     */
    fun pick(c: Companions, ahead: List<RestAhead>, minutesLeft: Int?): RestPick? {
        val usable = ahead.filter { it.aheadKm >= 0.5 }
        if (usable.isEmpty()) return null
        val due = minutesLeft ?: Int.MAX_VALUE / 2
        val inTime = usable.filter { it.aheadMin <= due + LATE_OK_MIN }
        if (inTime.isEmpty()) {
            val first = usable.minBy { it.aheadKm }
            return RestPick(first, matches(c, first), onTime = false)
        }
        val best = inTime.sortedWith(
            compareByDescending<RestAhead> { matches(c, it).size }
                .thenBy { if (minutesLeft == null) it.aheadMin else abs(due - it.aheadMin) }
        ).first()
        return RestPick(best, matches(c, best), onTime = true)
    }

    /** "다음 휴게소에서 쉬어갈까요?" 음성 질문. */
    fun question(p: RestPick): String {
        val a = p.ahead
        val where = if (a.aheadMin <= 1) "${a.area.name}가 바로 앞에 있어요." else "${a.area.name}가 ${kmWords(a.aheadKm)}, 약 ${a.aheadMin}분 앞에 있어요."
        val extra = if (p.matched.isNotEmpty()) " ${p.matched.joinToString("과 ")}이 있는 곳이에요." else ""
        return "다음 휴게소에서 쉬어갈까요? $where$extra 네, 또는 아니요로 대답해 주세요."
    }

    private fun kmWords(km: Double): String = if (km < 10) "${(km * 10).toInt() / 10.0}킬로미터" else "${km.toInt()}킬로미터"

    /** 음성 대답 → 네(true) / 아니요(false) / 모름(null). 부정어를 먼저 본다 ("아니 안 쉴래"). */
    fun parseYesNo(text: String): Boolean? {
        val s = Hangul.compact(text)
        if (s.isEmpty()) return null
        val no = listOf("아니", "싫", "안돼", "안쉬", "안가", "안들", "다음에", "괜찮", "나중에", "됐어")
        if (no.any { s.contains(it) } || s == "노") return false
        val yes = listOf("네", "예", "응", "어", "좋아", "그래", "쉬자", "쉬어", "들러", "들르", "가자", "오케이", "콜", "당연")
        if (yes.any { s.startsWith(it) || (it.length >= 2 && s.contains(it)) }) return true
        return null
    }
}
