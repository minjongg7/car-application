package com.hmgapps.family.domain

import com.hmgapps.core.trip.RestAhead
import com.hmgapps.core.trip.RestArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 여정 지도 · 음성 놀이 · 동승자 맞춤 휴식 추천. */
class FamilyTripFeaturesTest {

    // ---------------- 1. 여정 진행 지도
    @Test fun applesLeft() {
        assertEquals(10, JourneyMap.unitsLeft(400_000, 400_000))
        assertEquals(3, JourneyMap.unitsLeft(100_000, 400_000)) // 25% 남음 → 2.5 → 올림 3
        assertEquals(1, JourneyMap.unitsLeft(1_000, 400_000))
        assertEquals(0, JourneyMap.unitsLeft(300, 400_000))
        assertNull(JourneyMap.unitsLeft(null, 400_000))
        assertEquals("할머니 집까지 사과 3개 남았어!", JourneyMap.phrase("할머니 집", 3, JourneyToken.APPLE))
        assertEquals("1시간 10분", JourneyMap.durationText(4_200))
        assertEquals("25분", JourneyMap.durationText(1_500))
        assertEquals("만화 2편 보면 도착해요", JourneyMap.cartoonText(2_400))
    }

    @Test fun regionLocator() {
        assertEquals(Region.SEOUL, RegionLocator.locate(37.5547, 126.9707))
        assertEquals(Region.GYEONGGI, RegionLocator.locate(37.2427, 127.0990))  // 기흥
        assertEquals(Region.CHUNGNAM, RegionLocator.locate(36.8000, 127.1420))  // 천안
        assertEquals(Region.CHUNGBUK, RegionLocator.locate(36.6300, 127.3600))  // 옥산(청주)
        assertEquals(Region.GYEONGBUK, RegionLocator.locate(35.9700, 128.4200)) // 칠곡
        assertEquals(Region.BUSAN, RegionLocator.locate(35.1152, 129.0422))
        assertNull(RegionLocator.locate(30.0, 120.0))
        Region.entries.forEach { r -> assertTrue("$r 퀴즈 없음", RegionQuizBank.ALL.any { it.region == r } || r == Region.SEOUL) }
    }

    // ---------------- 2. 음성 놀이
    @Test fun dueum() {
        assertTrue('역' in Hangul.allowedStarts('력'))
        assertTrue('내' in Hangul.allowedStarts('래'))
        assertTrue('여' in Hangul.allowedStarts('녀'))
        assertEquals(setOf('차'), Hangul.allowedStarts('차'))
        assertEquals("사과", Hangul.stripEnding("사과요"))
        assertEquals("동요", Hangul.stripEnding("동요"))
    }

    @Test fun wordChainAcceptsRejectsAndReplies() {
        val g = WordChainGame(Random(1))
        val first = g.start()
        assertTrue(first.listen)
        val appWord = g.history.last().second
        val need = appWord.last()
        // 틀린 시작 글자
        val bad = g.onHeard("음 바나나")
        assertTrue(bad.listen)
        assertEquals(0, g.score)
        // 맞는 낱말 하나를 사전에서 골라 대답
        val good = WordBank.startingWith(need).first { it != appWord }
        val t = g.onHeard("$good 요")
        assertEquals(1, g.score)
        assertTrue(t.say.startsWith(good))
        if (!t.finished) {
            val reply = g.history.last()
            assertFalse(reply.first)
            assertTrue(reply.second.first() in Hangul.allowedStarts(good.last()))
        }
        // 이미 나온 말은 안 된다
        val dup = g.onHeard(good)
        assertTrue(dup.say.contains("벌써"))
    }

    @Test fun wordBankIsClean() {
        assertTrue(WordBank.WORDS.size > 300)
        assertTrue(WordBank.WORDS.all { w -> w.length >= 2 && w.all(Hangul::isSyllable) })
        WordBank.STARTERS.forEach { assertTrue(it, it in WordBank.WORDS) }
    }

    @Test fun riddleHintsThenAnswer() {
        val r = Riddle("기린", emptyList(), listOf("a", "b", "c"))
        val g = RiddleGame(Random(0), listOf(r))
        g.start()
        val wrong = g.onHeard("코끼리")
        assertTrue(wrong.say.contains("2번째 힌트"))
        val right = g.onHeard("기린이에요")
        assertTrue(right.finished)
        assertEquals(1, g.score)
    }

    @Test fun regionQuiz() {
        val g = RegionQuizGame(Region.CHUNGNAM, Random(0))
        val t = g.start()
        assertTrue(t.say.contains("충청남도"))
        val ans = g.current.keywords.first()
        val r = g.onHeard("정답은 $ans")
        assertTrue(r.finished)
        assertEquals(1, g.score)
    }

    // ---------------- 3. 동승자 맞춤 휴식 추천
    private fun ahead(name: String, km: Double, min: Int, nursing: Boolean = false, kids: Boolean = false) =
        RestAhead(RestArea(name, 0.0, 0.0, nursingRoom = nursing, kidsZone = kids), km, min)

    @Test fun intervalsFromCompanions() {
        assertEquals(90, CompanionRest.intervalMin(Companions(setOf(AgeGroup.TODDLER, AgeGroup.ADULT))))
        assertEquals(60, CompanionRest.intervalMin(Companions(setOf(AgeGroup.ADULT), elderly = true)))
        assertEquals(60, CompanionRest.intervalMin(Companions(setOf(AgeGroup.KID), motionSick = true)))
        assertEquals(120, CompanionRest.intervalMin(Companions(emptySet())))
        assertEquals(30, CompanionRest.minutesUntilRest(Companions(setOf(AgeGroup.TODDLER)), 60))
        assertEquals(0, CompanionRest.minutesUntilRest(Companions(setOf(AgeGroup.TODDLER)), 200))
        assertEquals(80, CompanionRest.minutesUntilRest(Companions(setOf(AgeGroup.TODDLER)), 100, lastRestAtMin = 90))
    }

    @Test fun pickPrefersAmenityBeforeDue() {
        val c = Companions(setOf(AgeGroup.TODDLER))
        val list = listOf(
            ahead("가", 10.0, 8),
            ahead("나", 30.0, 22, nursing = true),
            ahead("다", 50.0, 38, nursing = true),
            ahead("라", 90.0, 70, nursing = true, kids = true),
        )
        val p = CompanionRest.pick(c, list, minutesLeft = 35)!!
        assertEquals("다", p.ahead.area.name) // 수유실 + 휴식 시각(35분)에 가장 가까움 (38분, 10분 여유 안)
        assertEquals(listOf("수유실"), p.matched)
        assertTrue(p.onTime)
        val late = CompanionRest.pick(c, listOf(ahead("마", 120.0, 90)), minutesLeft = 10)!!
        assertFalse(late.onTime)
        assertTrue(CompanionRest.question(p).startsWith("다음 휴게소에서 쉬어갈까요?"))
    }

    @Test fun yesNo() {
        assertEquals(true, CompanionRest.parseYesNo("네"))
        assertEquals(true, CompanionRest.parseYesNo("응 좋아"))
        assertEquals(true, CompanionRest.parseYesNo("그래 쉬어 가자"))
        assertEquals(false, CompanionRest.parseYesNo("아니요"))
        assertEquals(false, CompanionRest.parseYesNo("아니 괜찮아"))
        assertEquals(false, CompanionRest.parseYesNo("안 쉬어도 돼"))
        assertNull(CompanionRest.parseYesNo("음"))
    }
}
