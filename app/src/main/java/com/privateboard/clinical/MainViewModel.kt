package com.privateboard.clinical

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CorpusLoadState {
    data object Loading : CorpusLoadState
    data class Ready(val corpus: Corpus) : CorpusLoadState
    data class Failed(val message: String) : CorpusLoadState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CorpusRepository(app)
    var loadState by mutableStateOf<CorpusLoadState>(CorpusLoadState.Loading)
        private set

    val corpus: Corpus
        get() = (loadState as? CorpusLoadState.Ready)?.corpus ?: EMPTY_CORPUS

    var screen by mutableStateOf(AppScreen.HOME)
    var selectedBook by mutableStateOf<Book?>(null)
    var search by mutableStateOf("")
    var searchBookId by mutableStateOf<Int?>(null)
    var searchDifficulty by mutableStateOf<String?>(null)
    var dark by mutableStateOf(repo.isDark())
    var stateVersion by mutableIntStateOf(0)
    var sessionQuestions by mutableStateOf<List<Question>>(emptyList())
    var sessionMode by mutableStateOf(SessionMode.STUDY)
    var sessionIndex by mutableIntStateOf(0)
        private set
    private var durableSessionIndex = 0
    private var activeSessionIdentity: String? = null

    init { loadCorpus() }

    fun loadCorpus() {
        if (loadState is CorpusLoadState.Loading && corpus.questions.isNotEmpty()) return
        loadState = CorpusLoadState.Loading
        viewModelScope.launch {
            loadState = try {
                val loaded = withContext(Dispatchers.IO) { repo.loadCorpus() }
                CorpusLoadState.Ready(loaded)
            } catch (error: Throwable) {
                CorpusLoadState.Failed(
                    error.message?.takeIf(String::isNotBlank)
                        ?: "The offline question library could not be opened."
                )
            }
        }
    }

    fun state(q: Question) = repo.state(q.id)
    fun favorite(q: Question) { repo.setFavorite(q.id, !state(q).favorited); stateVersion++ }
    fun record(q: Question, correct: Boolean) { repo.record(q.id, correct); stateVersion++ }
    fun toggleDark() { dark = !dark; repo.setDark(dark) }
    fun reset() { repo.reset(); dark = false; activeSessionIdentity = null; stateVersion++ }
    fun openBook(book: Book) { selectedBook = book; screen = AppScreen.BOOK }

    fun resumeInfo(config: SessionConfig): SessionResumeInfo? {
        val eligibleIds = eligible(config).mapTo(HashSet()) { it.id }
        val saved = repo.savedSession(config)
        return if (SessionProgressLogic.canResume(saved, config, eligibleIds)) {
            SessionProgressLogic.resumeInfo(saved)
        } else null
    }

    /** Resumes an exact saved round when possible; otherwise starts a fresh, unseen-first round. */
    fun start(config: SessionConfig) {
        val eligible = eligible(config)
        val byId = eligible.associateBy { it.id }
        val saved = repo.savedSession(config)
        val resume = SessionProgressLogic.canResume(saved, config, byId.keys)
        val questionIds = if (resume) {
            saved!!.questionIds
        } else {
            SessionProgressLogic.selectNewQuestionIds(
                eligible.shuffled().map { it.id },
                eligible.associate { it.id to repo.attemptCount(it.id) },
                config.count
            )
        }
        val identity = SessionProgressLogic.identity(config)
        sessionMode = config.mode
        sessionQuestions = questionIds.mapNotNull(byId::get)
        sessionIndex = if (resume) saved!!.index else 0
        durableSessionIndex = sessionIndex
        activeSessionIdentity = identity.takeIf { sessionQuestions.isNotEmpty() }
        if (sessionQuestions.isNotEmpty()) persistSession() else repo.clearSession(identity)
        screen = AppScreen.SESSION
    }

    fun openSingleQuestion(question: Question) {
        sessionQuestions = listOf(question)
        sessionMode = SessionMode.STUDY
        sessionIndex = 0
        durableSessionIndex = 0
        activeSessionIdentity = null
        screen = AppScreen.SESSION
    }

    fun moveSessionTo(index: Int) {
        if (sessionQuestions.isEmpty()) return
        sessionIndex = index.coerceIn(sessionQuestions.indices)
        durableSessionIndex = sessionIndex
        persistSession()
    }

    /**
     * Keep showing the explanation, but durably point resume at the following question. This is
     * why leaving immediately after answering question 10 comes back at question 11.
     */
    fun markSessionAnswered() {
        val identity = activeSessionIdentity ?: return
        if (sessionIndex == sessionQuestions.lastIndex) {
            repo.clearSession(identity)
            activeSessionIdentity = null
        } else {
            durableSessionIndex = sessionIndex + 1
            persistSession()
        }
    }

    /** Call on close as a final synchronous durability barrier. */
    fun persistSession() {
        val identity = activeSessionIdentity ?: return
        if (sessionQuestions.isEmpty()) return
        repo.saveSession(SessionSnapshot(identity, sessionQuestions.map { it.id }, durableSessionIndex, sessionMode))
    }

    fun finishSession() {
        activeSessionIdentity?.let(repo::clearSession)
        activeSessionIdentity = null
        screen = AppScreen.STATS
    }

    private fun eligible(config: SessionConfig) = corpus.questions.asSequence()
        .filter { config.bookId == null || it.bookId == config.bookId }
        .filter { config.section == null || it.section == config.section }
        .filter { config.difficulty == null || it.difficulty == config.difficulty }
        .filter { !config.favoritesOnly || state(it).favorited }
        .toList()

    fun searchResults(): List<Question> {
        val words = search.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return corpus.questions.asSequence()
            .filter { searchBookId == null || it.bookId == searchBookId }
            .filter { searchDifficulty == null || it.difficulty == searchDifficulty }
            .filter { q ->
                words.isEmpty() || words.all { word ->
                    q.question.lowercase().contains(word) ||
                        q.section.lowercase().contains(word) ||
                        q.explanation.lowercase().contains(word)
                }
            }
            .take(250).toList()
    }

    private companion object {
        val EMPTY_CORPUS = Corpus("Internal Medicine", 0, emptyList(), emptyList())
    }
}
