package com.hmgapps.family.domain

/** 한글 음절 분해·두음법칙·음성 인식 결과 정리. */
object Hangul {
    private const val BASE = 0xAC00
    private const val LAST = 0xD7A3

    // 초성 번호
    private const val N = 2   // ㄴ
    private const val R = 5   // ㄹ
    private const val O = 11  // ㅇ

    // 중성 번호: ㅑ2 ㅕ6 ㅖ7 ㅛ12 ㅠ17 ㅣ20  (ㄹ/ㄴ → ㅇ 으로 바뀌는 모음)
    private val TO_IEUNG = setOf(2, 6, 7, 12, 17, 20)
    // ㄹ → ㄴ: ㅏ0 ㅐ1 ㅗ8 ㅚ11 ㅜ13 ㅡ18
    private val R_TO_N = setOf(0, 1, 8, 11, 13, 18)

    fun isSyllable(c: Char): Boolean = c.code in BASE..LAST

    private fun parts(c: Char): Triple<Int, Int, Int> {
        val i = c.code - BASE
        return Triple(i / (21 * 28), (i / 28) % 21, i % 28)
    }

    private fun compose(ini: Int, med: Int, fin: Int): Char = (BASE + (ini * 21 + med) * 28 + fin).toChar()

    /** 끝말잇기에서 c 다음에 올 수 있는 첫 글자들 (두음법칙 허용). 예: 력 → {력, 역}, 래 → {래, 내}. */
    fun allowedStarts(c: Char): Set<Char> {
        if (!isSyllable(c)) return setOf(c)
        val (ini, med, fin) = parts(c)
        val out = linkedSetOf(c)
        when (ini) {
            R -> out += if (med in TO_IEUNG) compose(O, med, fin) else if (med in R_TO_N) compose(N, med, fin) else c
            N -> if (med in TO_IEUNG) out += compose(O, med, fin)
        }
        return out
    }

    /** 음성 인식 문장에서 한글 낱말들만 뽑는다 ("음… 사과요!" → [음, 사과요]). */
    fun words(text: String): List<String> =
        text.split(Regex("[^가-힣]+")).filter { it.isNotEmpty() }

    /** 대답 끝에 흔히 붙는 말을 뗀다 ("사과요" → "사과", "기차입니다" → "기차"). */
    fun stripEnding(w: String): String {
        val endings = listOf("입니다", "이에요", "예요", "이요", "이야", "요", "야")
        for (e in endings) if (w.length - e.length >= 2 && w.endsWith(e)) return w.dropLast(e.length)
        return w
    }

    /** 비교용: 공백 제거한 한글만. */
    fun compact(text: String): String = words(text).joinToString("")
}
