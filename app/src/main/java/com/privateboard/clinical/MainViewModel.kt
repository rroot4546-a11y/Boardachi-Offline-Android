package com.privateboard.clinical

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.*

class MainViewModel(app:Application):AndroidViewModel(app) {
    private val repo=CorpusRepository(app)
    val corpus get()=repo.corpus
    var screen by mutableStateOf(AppScreen.HOME)
    var selectedBook by mutableStateOf<Book?>(null)
    var search by mutableStateOf("")
    var searchBookId by mutableStateOf<Int?>(null)
    var searchDifficulty by mutableStateOf<String?>(null)
    var dark by mutableStateOf(repo.isDark())
    var stateVersion by mutableIntStateOf(0)
    var sessionQuestions by mutableStateOf<List<Question>>(emptyList())
    var sessionMode by mutableStateOf(SessionMode.STUDY)

    fun state(q:Question)=repo.state(q.id)
    fun favorite(q:Question) { repo.setFavorite(q.id,!state(q).favorited); stateVersion++ }
    fun record(q:Question,correct:Boolean) { repo.record(q.id,correct); stateVersion++ }
    fun toggleDark(){ dark=!dark; repo.setDark(dark) }
    fun reset(){ repo.reset(); dark=false; stateVersion++ }
    fun openBook(book:Book){selectedBook=book;screen=AppScreen.BOOK}
    fun start(config:SessionConfig) {
        sessionMode=config.mode
        val eligible=corpus.questions.asSequence().filter { config.bookId==null||it.bookId==config.bookId }
            .filter { config.section==null||it.section==config.section }
            .filter { config.difficulty==null||it.difficulty==config.difficulty }
            .filter { !config.favoritesOnly||state(it).favorited }
            .toList().shuffled()
        sessionQuestions=eligible.take(config.count.coerceAtMost(eligible.size)); screen=AppScreen.SESSION
    }
    fun searchResults():List<Question> {
        val words=search.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return corpus.questions.asSequence().filter{searchBookId==null||it.bookId==searchBookId}
            .filter{searchDifficulty==null||it.difficulty==searchDifficulty}
            .filter { q -> words.isEmpty() || words.all { w -> q.question.lowercase().contains(w)||q.section.lowercase().contains(w)||q.explanation.lowercase().contains(w) } }
            .take(250).toList()
    }
}
