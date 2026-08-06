package com.privateboard.clinical

data class Book(val id:Int,val title:String,val edition:String?,val year:String?,val authors:List<String>,val count:Int)
data class Choice(val id:Int,val order:Int,val text:String,val correct:Boolean,val kind:String,val match:Int?,val image:String?)
data class Question(val id:Int,val bookId:Int,val sectionId:Int?,val section:String,val type:String,val difficulty:String,val question:String,val explanation:String,val notes:String,val images:List<String>,val choices:List<Choice>)
data class Corpus(val specialty:String,val count:Int,val books:List<Book>,val questions:List<Question>)
data class UserState(val attempts:Int=0,val correct:Int=0,val favorited:Boolean=false,val interval:Int=0,val dueAt:Long=0)

enum class AppScreen { HOME, LIBRARY, SEARCH, STATS, BOOK, SESSION, SETTINGS }
enum class SessionMode { STUDY, EXAM }

data class SessionConfig(
    val bookId:Int?=null,
    val section:String?=null,
    val difficulty:String?=null,
    val count:Int=20,
    val mode:SessionMode=SessionMode.STUDY,
    val favoritesOnly:Boolean=false
)
