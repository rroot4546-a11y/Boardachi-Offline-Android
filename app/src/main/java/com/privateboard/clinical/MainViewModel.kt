package com.privateboard.clinical

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CorpusLoadState {
    data object Loading : CorpusLoadState
    data class Ready(val corpus: Corpus) : CorpusLoadState
    data class Failed(val message: String) : CorpusLoadState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CorpusRepository(app)
    private val customRepo = CustomSourceRepository(app)
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
    var customSources by mutableStateOf(customRepo.list()); private set
    var customImportState by mutableStateOf<CustomImportState>(CustomImportState.Idle); private set
    var aiWorkingQuestionId by mutableStateOf<Int?>(null); private set
    var aiMessage by mutableStateOf<String?>(null); private set
    var aiBatchProgress by mutableStateOf<Pair<Int,Int>?>(null); private set
    private var aiBatchJob: Job? = null
    var sessionQuestions by mutableStateOf<List<Question>>(emptyList())
    var sessionMode by mutableStateOf(SessionMode.STUDY)
    var sessionIndex by mutableIntStateOf(0)
        private set
    private var durableSessionIndex = 0
    private var activeSessionIdentity: String? = null
    private var sessionExamAnswers = emptyMap<Int, List<Int>>()

    init { loadCorpus() }

    fun loadCorpus() {
        loadState = CorpusLoadState.Loading
        viewModelScope.launch {
            loadState = try {
                val loaded = withContext(Dispatchers.IO) { repo.loadCorpus() }
                val customQuestions = withContext(Dispatchers.IO) { customRepo.questions() }
                val customBooks = withContext(Dispatchers.IO) { customRepo.books() }
                CorpusLoadState.Ready(loaded.copy(count = loaded.count + customQuestions.size, books = loaded.books + customBooks, questions = loaded.questions + customQuestions))
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
    fun clearImportResult() { customImportState = CustomImportState.Idle }
    fun importCustomSource(questionUri: Uri, answerUri: Uri?, name: String) {
        if (customImportState is CustomImportState.Working) return
        customImportState = CustomImportState.Working(CustomImportProgress(ImportStage.READING, message = "Opening Questions PDF"))
        viewModelScope.launch {
            customImportState = try {
                val source = withContext(Dispatchers.IO) { customRepo.importPdf(questionUri, answerUri, name) { progress -> viewModelScope.launch { customImportState = CustomImportState.Working(progress) } } }
                customSources = customRepo.list(); loadCorpus(); CustomImportState.Success(source)
            } catch (error: Throwable) { CustomImportState.Failed(error.message ?: "The PDFs could not be imported.") }
        }
    }
    fun deleteCustomSource(source: CustomSource) { customRepo.delete(source); customSources = customRepo.list(); loadCorpus() }
    fun cancelAiBatch(){aiBatchJob?.cancel();aiBatchJob=null;aiBatchProgress=null;aiWorkingQuestionId=null;aiMessage="Batch cancelled."}
    fun requestAiBatch(questions:List<Question>){val missing=AiAnswerLogic.missingAnswers(questions);val settings=OpenRouterSettings(getApplication());if(!settings.hasToken()){aiMessage="Add your OpenRouter token in Settings first.";return};if(missing.isEmpty()){aiMessage="No unanswered custom questions.";return};aiBatchJob=viewModelScope.launch{var done=0;try{for(q in missing){aiBatchProgress=done to missing.size;aiWorkingQuestionId=q.id;val answer=withContext(Dispatchers.IO){OpenRouterClient().answer(q,settings.token,settings.model)};withContext(Dispatchers.IO){customRepo.saveAiAnswer(q,answer)};done++;aiBatchProgress=done to missing.size};aiMessage="$done AI-generated answers saved — verify medically.";loadCorpus()}catch(t:Throwable){if(t is kotlinx.coroutines.CancellationException)throw t;aiMessage="Batch stopped after $done: ${t.message}"}finally{aiBatchProgress=null;aiWorkingQuestionId=null;aiBatchJob=null}}}
    fun requestAiAnswer(question: Question) { val settings=OpenRouterSettings(getApplication());if(!settings.hasToken()){aiMessage="Add your OpenRouter token in Settings first.";return};aiWorkingQuestionId=question.id;aiMessage=null;viewModelScope.launch{try{val answer=withContext(Dispatchers.IO){OpenRouterClient().answer(question,settings.token,settings.model)};val updated=withContext(Dispatchers.IO){customRepo.saveAiAnswer(question,answer)};sessionQuestions=sessionQuestions.map{if(it.id==updated.id)updated else it};aiMessage="AI-generated answer saved — verify medically.";loadCorpus()}catch(t:Throwable){aiMessage=t.message?:"AI request failed."}finally{aiWorkingQuestionId=null}}}

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
        sessionExamAnswers = if (resume) saved!!.examAnswers else emptyMap()
        if (sessionQuestions.isNotEmpty()) persistSession() else repo.clearSession(identity)
        screen = AppScreen.SESSION
    }

    fun openSingleQuestion(question: Question) {
        sessionQuestions = listOf(question)
        sessionMode = SessionMode.STUDY
        sessionIndex = 0
        durableSessionIndex = 0
        activeSessionIdentity = null
        sessionExamAnswers = emptyMap()
        screen = AppScreen.SESSION
    }

    fun moveSessionTo(index: Int) {
        if (sessionQuestions.isEmpty()) return
        sessionIndex = index.coerceIn(sessionQuestions.indices)
        durableSessionIndex = sessionIndex
        persistSession()
    }

    fun restoredExamAnswers(): Map<Int, Set<Int>> = sessionExamAnswers.mapValues { it.value.toSet() }

    fun saveExamAnswer(questionId: Int, choiceIds: Set<Int>) {
        sessionExamAnswers = sessionExamAnswers + (questionId to choiceIds.toList())
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
        repo.saveSession(SessionSnapshot(identity, sessionQuestions.map { it.id }, durableSessionIndex, sessionMode, sessionExamAnswers))
    }

    fun finishSession() {
        activeSessionIdentity?.let(repo::clearSession)
        activeSessionIdentity = null
        sessionExamAnswers = emptyMap()
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
