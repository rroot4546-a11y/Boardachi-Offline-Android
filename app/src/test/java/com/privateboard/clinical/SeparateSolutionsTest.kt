package com.privateboard.clinical

import org.junit.Assert.*
import org.junit.Test

class SeparateSolutionsTest {
    private val questionsText = """
        Q10. A sufficiently long first clinical question?
        A. Alpha
        B. Beta
        C. Gamma
        Q3. A sufficiently long second clinical question?
        A. One
        B. Two
        C. Three
        Answer: B
        Explanation: Short.
        Q99. A sufficiently long unanswered clinical question?
        A. Red
        B. Blue
    """.trimIndent()

    @Test fun parsesCommonKeyFormatsAndMergesByPrintedNumber() {
        val entries = CustomAnswerParser.parse("""
            10-A
            3. C
            44) B
            Q99 Answer: B
            Question 7: C
        """.trimIndent())
        assertEquals(listOf(3, 7, 10, 44, 99), entries.map { it.sourceNumber })
        val questions = CustomQuestionParser.parseRecords(questionsText, -1, 100)
        val result = CustomAnswerParser.merge(questions, entries)
        assertTrue(result.questions.first { it.sourceNumber == 10 }.question.choices[0].correct)
        // The inline B answer has precedence over separate C.
        assertTrue(result.questions.first { it.sourceNumber == 3 }.question.choices[1].correct)
        assertTrue(result.questions.first { it.sourceNumber == 99 }.question.choices[1].correct)
        assertEquals(2, result.skippedOrUnmatchedEntries)
    }

    @Test fun invalidLettersAreIgnoredAndLongerExplanationIsMerged() {
        val questions = CustomQuestionParser.parseRecords(questionsText, -1, 100)
        val answers = listOf(
            ParsedAnswerEntry(10, "H", "Invalid"),
            ParsedAnswerEntry(3, "C", "This is a substantially fuller rationale than the inline note."),
        )
        val result = CustomAnswerParser.merge(questions, answers)
        assertTrue(result.questions.first { it.sourceNumber == 10 }.question.choices.none { it.correct })
        val inline = result.questions.first { it.sourceNumber == 3 }.question
        assertTrue(inline.choices[1].correct)
        assertTrue(inline.explanation.startsWith("This is a substantially"))
        assertEquals(1, result.skippedOrUnmatchedEntries)
    }

    @Test fun rationaleBlocksParseAndUnansweredRemainForAi() {
        val entries = CustomAnswerParser.parse("""
            Question 10: A
            Rationale: A is supported by the clinical finding.
        """.trimIndent())
        assertTrue(entries.single().explanation.contains("supported"))
        val records = CustomQuestionParser.parseRecords(questionsText, -1, 100)
        val result = CustomAnswerParser.merge(records, entries)
        assertEquals(1, AiAnswerLogic.missingAnswers(result.questions.map { it.question }).size)
    }
}
