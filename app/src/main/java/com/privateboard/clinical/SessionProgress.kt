package com.privateboard.clinical

/** A durable in-progress round. Question IDs preserve the original order across process restarts. */
data class SessionSnapshot(
    val identity: String,
    val questionIds: List<Int>,
    val index: Int,
    val mode: SessionMode
)

data class SessionResumeInfo(val nextQuestion: Int, val totalQuestions: Int)

/** Pure session identity/selection rules, kept separate so they can be covered by JVM tests. */
object SessionProgressLogic {
    fun identity(config: SessionConfig): String = buildString {
        append("book=").append(config.bookId ?: "all")
        append("|section=").append(lengthPrefixed(config.section))
        append("|difficulty=").append(lengthPrefixed(config.difficulty))
        append("|count=").append(config.count)
        append("|mode=").append(config.mode.name)
        append("|favorites=").append(config.favoritesOnly)
    }

    /**
     * The caller may shuffle [eligibleIds] once to make a fresh round feel varied. This stable
     * ordering then puts unseen questions first and, after coverage is complete, least-attempted
     * questions first. Thus completing a chapter round naturally moves on instead of restarting.
     */
    fun selectNewQuestionIds(
        eligibleIds: List<Int>,
        attemptsById: Map<Int, Int>,
        count: Int
    ): List<Int> = eligibleIds
        .sortedBy { attemptsById[it] ?: 0 }
        .take(count.coerceAtLeast(0).coerceAtMost(eligibleIds.size))

    fun canResume(snapshot: SessionSnapshot?, config: SessionConfig, eligibleIds: Set<Int>): Boolean {
        if (snapshot == null || snapshot.identity != identity(config) || snapshot.mode != config.mode) return false
        if (snapshot.questionIds.isEmpty() || snapshot.index !in snapshot.questionIds.indices) return false
        if (snapshot.questionIds.distinct().size != snapshot.questionIds.size) return false
        return snapshot.questionIds.all(eligibleIds::contains)
    }

    fun resumeInfo(snapshot: SessionSnapshot?): SessionResumeInfo? {
        if (snapshot == null || snapshot.questionIds.isEmpty() || snapshot.index !in snapshot.questionIds.indices) return null
        return SessionResumeInfo(snapshot.index + 1, snapshot.questionIds.size)
    }

    private fun lengthPrefixed(value: String?): String = value?.let { "${it.length}:$it" } ?: "null"
}
