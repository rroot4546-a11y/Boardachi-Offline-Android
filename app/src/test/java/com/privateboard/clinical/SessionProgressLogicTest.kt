package com.privateboard.clinical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProgressLogicTest {
    private val chapter = SessionConfig(bookId = 7, section = "Cardiology", count = 20)

    @Test fun identityIncludesScopeModeAndConfiguration() {
        assertFalse(SessionProgressLogic.identity(chapter) == SessionProgressLogic.identity(chapter.copy(section = "Renal")))
        assertFalse(SessionProgressLogic.identity(chapter) == SessionProgressLogic.identity(chapter.copy(mode = SessionMode.EXAM)))
        assertFalse(SessionProgressLogic.identity(chapter) == SessionProgressLogic.identity(chapter.copy(count = 50)))
        assertFalse(SessionProgressLogic.identity(chapter) == SessionProgressLogic.identity(chapter.copy(favoritesOnly = true)))
    }

    @Test fun validSavedRoundResumesAtExactIndex() {
        val snapshot = SessionSnapshot(
            SessionProgressLogic.identity(chapter),
            (101..120).toList(),
            index = 10,
            mode = SessionMode.STUDY
        )
        assertTrue(SessionProgressLogic.canResume(snapshot, chapter, (100..130).toSet()))
        assertEquals(SessionResumeInfo(nextQuestion = 11, totalQuestions = 20), SessionProgressLogic.resumeInfo(snapshot))
    }

    @Test fun anotherTopicCannotTakeOverSavedRound() {
        val snapshot = SessionSnapshot(SessionProgressLogic.identity(chapter), listOf(1, 2), 1, SessionMode.STUDY)
        assertFalse(SessionProgressLogic.canResume(snapshot, chapter.copy(section = "Renal"), setOf(1, 2)))
    }

    @Test fun staleOrCompletedSnapshotDoesNotResume() {
        val identity = SessionProgressLogic.identity(chapter)
        assertFalse(SessionProgressLogic.canResume(SessionSnapshot(identity, listOf(1, 2), 2, SessionMode.STUDY), chapter, setOf(1, 2)))
        assertFalse(SessionProgressLogic.canResume(SessionSnapshot(identity, listOf(1, 99), 1, SessionMode.STUDY), chapter, setOf(1, 2)))
    }

    @Test fun freshSelectionPrefersUnseenThenLeastAttempted() {
        val shuffledEligible = listOf(5, 2, 4, 1, 3)
        val selected = SessionProgressLogic.selectNewQuestionIds(
            shuffledEligible,
            mapOf(1 to 3, 2 to 0, 3 to 1, 4 to 0, 5 to 8),
            count = 4
        )
        assertEquals(listOf(2, 4, 3, 1), selected)
    }

    @Test fun selectionIsBoundedByAvailability() {
        assertEquals(listOf(3, 2), SessionProgressLogic.selectNewQuestionIds(listOf(3, 2), emptyMap(), 50))
        assertTrue(SessionProgressLogic.selectNewQuestionIds(listOf(3, 2), emptyMap(), 0).isEmpty())
    }
}
