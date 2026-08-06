package com.privateboard.clinical

data class Book(val id:Int,val title:String,val edition:String?,val year:String?,val authors:List<String>,val count:Int)
data class Choice(val id:Int,val order:Int,val text:String,val correct:Boolean,val kind:String,val match:Int?,val image:String?)
data class Question(val id:Int,val bookId:Int,val sectionId:Int?,val section:String,val type:String,val difficulty:String,val question:String,val explanation:String,val notes:String,val images:List<String>,val choices:List<Choice>,val aiGenerated:Boolean=false,val aiConfidence:Double?=null)
data class Corpus(val specialty:String,val count:Int,val books:List<Book>,val questions:List<Question>)
data class UserState(val attempts:Int=0,val correct:Int=0,val favorited:Boolean=false,val interval:Int=0,val dueAt:Long=0)
data class CustomSource(
    val id:String,
    val name:String,
    val fileName:String,
    val importedAt:Long,
    val questionCount:Int,
    val usedOcr:Boolean,
    val answersFileName:String?=null,
    val verifiedAnswerCount:Int=0,
    val missingAnswerCount:Int=0,
    val unmatchedAnswerCount:Int=0,
)
data class AiAnswer(val letters:Set<String>,val explanation:String,val confidence:Double)

enum class ImportStage { IDLE, READING, OCR, PARSING, SAVING, COMPLETE }
data class CustomImportProgress(val stage:ImportStage=ImportStage.IDLE,val page:Int=0,val totalPages:Int=0,val message:String="")
sealed interface CustomImportState {
    data object Idle : CustomImportState
    data class Working(val progress:CustomImportProgress) : CustomImportState
    data class Success(val source:CustomSource) : CustomImportState
    data class Failed(val message:String) : CustomImportState
}

enum class AppScreen { HOME, LIBRARY, SEARCH, STATS, BOOK, SESSION, SETTINGS, CUSTOM_SOURCE }
enum class SessionMode { STUDY, EXAM }

data class SessionConfig(
    val bookId:Int?=null,
    val section:String?=null,
    val difficulty:String?=null,
    val count:Int=20,
    val mode:SessionMode=SessionMode.STUDY,
    val favoritesOnly:Boolean=false
)
