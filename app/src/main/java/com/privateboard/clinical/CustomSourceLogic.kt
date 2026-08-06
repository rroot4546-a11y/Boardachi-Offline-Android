package com.privateboard.clinical

/** Pure decisions kept outside Android so import behaviour remains unit-testable. */
object CustomSourceLogic {
    const val MIN_NATIVE_TEXT_CHARS = 80
    fun shouldUseOcr(nativeText: String, parsedQuestionCount: Int): Boolean =
        nativeText.count { !it.isWhitespace() } < MIN_NATIVE_TEXT_CHARS || parsedQuestionCount == 0

    fun safeDisplayName(requested: String, fileName: String): String =
        requested.trim().takeIf { it.isNotEmpty() }
            ?: fileName.substringBeforeLast('.').trim().takeIf { it.isNotEmpty() }
            ?: "Custom Source"
}

data class ParsedCustomQuestion(val sourceNumber: Int, val question: Question)
data class ParsedAnswerEntry(val sourceNumber: Int, val letter: String?, val explanation: String)
data class AnswerMergeResult(
    val questions: List<ParsedCustomQuestion>,
    val matchedEntries: Int,
    val skippedOrUnmatchedEntries: Int,
) {
    val verifiedCount: Int get() = questions.count { record -> record.question.choices.any { it.correct } }
    val missingCount: Int get() = questions.size - verifiedCount
}

/** Conservative parser which retains the printed question number for a separate-key merge. */
object CustomQuestionParser {
    private val questionStart = Regex("(?im)^\\s*(?:q(?:uestion)?\\s*)?(\\d{1,5})[.)\u2013:-]\\s+")
    private val choiceMarker = Regex("(?im)^\\s*([A-H])[.)\u2013:-]\\s+")
    private val correctMarker = Regex("(?im)^\\s*(?:correct\\s+)?answer\\s*[:\u2013-]\\s*([A-H])\\b")
    private val explanationMarker = Regex("(?im)^\\s*(?:explanation|rationale)\\s*[:\u2013-]\\s*")

    fun parse(text: String, bookId: Int, firstQuestionId: Int): List<Question> =
        parseRecords(text, bookId, firstQuestionId).map { it.question }

    fun parseRecords(text: String, bookId: Int, firstQuestionId: Int): List<ParsedCustomQuestion> {
        val starts = questionStart.findAll(text).toList()
        var nextId = firstQuestionId
        return starts.mapIndexedNotNull { index, match ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: text.length
            val number = match.groupValues[1].toInt()
            parseBlock(text.substring(match.range.last + 1, end).trim(), bookId, nextId)
                ?.also { nextId++ }
                ?.let { ParsedCustomQuestion(number, it) }
        }
    }

    private fun parseBlock(block: String, bookId: Int, id: Int): Question? {
        val answerMatches = choiceMarker.findAll(block).toList()
        if (answerMatches.size < 2) return null
        val stem = block.substring(0, answerMatches.first().range.first).trim()
        if (stem.length < 8) return null
        val correct = correctMarker.find(block)?.groupValues?.get(1)?.uppercase()
        val trailingStart = listOfNotNull(
            correctMarker.find(block)?.range?.first,
            explanationMarker.find(block)?.range?.first,
        ).minOrNull() ?: block.length
        val choices = answerMatches.mapIndexedNotNull { index, marker ->
            val letter = marker.groupValues[1].uppercase()
            val end = minOf(answerMatches.getOrNull(index + 1)?.range?.first ?: trailingStart, trailingStart)
            block.substring(marker.range.last + 1, end).trim().takeIf { it.isNotEmpty() }?.let {
                Choice(index + 1, index + 1, it, correct != null && letter == correct, "regular", null, null)
            }
        }
        if (choices.size < 2) return null
        val explanation = explanationMarker.find(block)?.let { block.substring(it.range.last + 1).trim() }.orEmpty()
        return Question(id, bookId, null, "Custom Source", if (choices.count { it.correct } > 1) "mcq" else "sba", "custom", stem, explanation, "", emptyList(), choices)
    }
}

/** Parses terse keys and repeated-question solutions, then merges strictly by printed number. */
object CustomAnswerParser {
    private val keyLine = Regex(
        "(?im)^\\s*(?:q(?:uestion)?\\s*)?(\\d{1,5})\\s*(?:[.)\u2013-]\\s*|:\\s*)?(?:answer\\s*[:\u2013-]?\\s*)?([A-H])\\s*[.)]?(?:\\s*(?:[\u2013:-]|(?:explanation|rationale)\\s*:)\\s*(.*))?\\s*$",
    )
    private val questionStart = Regex("(?im)^\\s*(?:q(?:uestion)?\\s*)?(\\d{1,5})[.)\u2013:-]\\s+")
    private val correctMarker = Regex("(?im)^\\s*(?:correct\\s+)?answer\\s*[:\u2013-]\\s*([A-H])\\b")
    private val explanationMarker = Regex("(?im)^\\s*(?:explanation|rationale)\\s*[:\u2013-]\\s*")

    fun parse(text: String): List<ParsedAnswerEntry> {
        val entries = mutableListOf<ParsedAnswerEntry>()
        val keys = keyLine.findAll(text).toList()
        keys.forEachIndexed { index, match ->
            val end = keys.getOrNull(index + 1)?.range?.first ?: text.length
            val segment = text.substring(match.range.last + 1, end)
            val explanation = match.groupValues.getOrNull(3)?.trim().orEmpty().ifEmpty {
                explanationMarker.find(segment)?.let { segment.substring(it.range.last + 1).trim() }.orEmpty()
            }
            entries += ParsedAnswerEntry(match.groupValues[1].toInt(), match.groupValues[2].uppercase(), explanation)
        }
        val starts = questionStart.findAll(text).toList()
        starts.forEachIndexed { index, match ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: text.length
            val block = text.substring(match.range.last + 1, end)
            val answer = correctMarker.find(block) ?: return@forEachIndexed
            val explanation = explanationMarker.find(block)?.let { block.substring(it.range.last + 1).trim() }.orEmpty()
            val number = match.groupValues[1].toInt()
            if (entries.none { it.sourceNumber == number }) {
                entries += ParsedAnswerEntry(number, answer.groupValues[1].uppercase(), explanation)
            }
        }
        return entries.sortedBy { it.sourceNumber }
    }

    fun merge(questions: List<ParsedCustomQuestion>, answers: List<ParsedAnswerEntry>): AnswerMergeResult {
        val byNumber = questions.associateBy { it.sourceNumber }
        var matched = 0
        var skipped = 0
        val accepted = mutableMapOf<Int, ParsedAnswerEntry>()
        answers.forEach { entry ->
            val record = byNumber[entry.sourceNumber]
            val index = entry.letter?.singleOrNull()?.minus('A') ?: -1
            if (record == null || index !in record.question.choices.indices) skipped++
            else {
                matched++
                val previous = accepted[entry.sourceNumber]
                if (previous == null || entry.explanation.length > previous.explanation.length) accepted[entry.sourceNumber] = entry
            }
        }
        val merged = questions.map { record ->
            val entry = accepted[record.sourceNumber] ?: return@map record
            val question = record.question
            val hasInlineAnswer = question.choices.any { it.correct }
            val correctIndex = entry.letter!!.single() - 'A'
            val choices = if (hasInlineAnswer) question.choices else question.choices.mapIndexed { index, choice -> choice.copy(correct = index == correctIndex) }
            // Inline correctness wins. A separate explanation replaces inline text only when fuller.
            val explanation = if (entry.explanation.length > question.explanation.length) entry.explanation else question.explanation
            record.copy(question = question.copy(choices = choices, explanation = explanation))
        }
        return AnswerMergeResult(merged, matched, skipped)
    }
}
