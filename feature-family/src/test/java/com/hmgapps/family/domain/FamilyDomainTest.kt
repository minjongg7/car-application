package com.hmgapps.family.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyDomainTest {
    @Test fun everyAgeGroupHasContent() {
        AgeGroup.entries.forEach { a -> assertTrue("$a 콘텐츠 없음", FamilyCatalog.forAges(setOf(a)).isNotEmpty()) }
    }

    @Test fun contentIsPlayable() {
        FamilyCatalog.items.forEach { c ->
            when (c.type) {
                ContentType.QUIZ -> assertTrue(c.id, c.quiz.isNotEmpty())
                ContentType.GAME -> assertEquals(c.id, 9, c.bingo.size)
                else -> assertTrue(c.id, c.lines.isNotEmpty())
            }
        }
        assertEquals(FamilyCatalog.items.size, FamilyCatalog.items.map { it.id }.toSet().size)
    }

    @Test fun sharedContentComesFirst() {
        val list = FamilyCatalog.forAges(setOf(AgeGroup.KID, AgeGroup.CHILD))
        val first = list.first().ages
        assertTrue(AgeGroup.KID in first && AgeGroup.CHILD in first)
    }

    @Test fun restUsesYoungest() {
        // 영유아: 카시트 1.5~2시간 → 앱 기본 90분
        assertEquals(90, RestAdvisor.intervalMin(setOf(AgeGroup.TODDLER, AgeGroup.ADULT)))
        assertEquals(45, RestAdvisor.minutesUntilRest(setOf(AgeGroup.TODDLER), 45))
        assertEquals(0, RestAdvisor.minutesUntilRest(setOf(AgeGroup.TODDLER), 90))
        assertEquals(90, RestAdvisor.minutesUntilRest(setOf(AgeGroup.TODDLER), 0))
        assertNull(RestAdvisor.minutesUntilRest(setOf(AgeGroup.KID), null))
    }

    @Test fun bingoLines() {
        assertEquals(0, Bingo.lines(setOf(0, 1)))
        assertEquals(1, Bingo.lines(setOf(0, 1, 2)))
        assertEquals(2, Bingo.lines(setOf(0, 1, 2, 4, 8)))
    }
}
