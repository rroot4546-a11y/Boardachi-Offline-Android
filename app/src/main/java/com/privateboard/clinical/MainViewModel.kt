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
    fun reset() { repo.reset(); dark = false; stateVersion++ }
    fun openBook(book: Book) { selectedBook = book; screen = AppScreen.BOOK }

    fun start(config: SessionConfig) {
        sessionMode = config.mode
        val eligible = corpus.questions.asSequence()
            .filter { config.bookId == null || it.bookId == config.bookId }
            .filter { config.section == null || it.section == config.section }
            .filter { config.difficulty == null || it.difficulty == config.difficulty }
            .filter { !config.favoritesOnly || state(it).favorited }
            .toList().shuffled()
        sessionQuestions = eligible.take(config.count.coerceAtMost(eligible.size))
        screen = AppScreen.SESSION
    }

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
