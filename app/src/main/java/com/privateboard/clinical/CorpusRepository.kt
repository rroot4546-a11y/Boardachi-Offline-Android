package com.privateboard.clinical

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream

class CorpusRepository(private val context: Context) {
    val corpus: Corpus by lazy { loadCorpus() }
    private val prefs:SharedPreferences by lazy { context.getSharedPreferences("clinical_deck_progress",Context.MODE_PRIVATE) }

    private fun loadCorpus():Corpus {
        val text=GZIPInputStream(context.assets.open("corpus.json.gz")).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root=JSONObject(text)
        val books=root.getJSONArray("books").mapObjects { b -> Book(b.getInt("id"),b.getString("title"),b.optStringOrNull("edition"),b.optStringOrNull("year"),b.getJSONArray("authors").strings(),b.getInt("count")) }
        val questions=root.getJSONArray("questions").mapObjects { q ->
            val choices=q.getJSONArray("choices").mapObjects { c -> Choice(c.optInt("id"),c.optInt("order"),c.optString("text"),c.optBoolean("correct"),c.optString("kind","regular"),if(c.isNull("match")) null else c.getInt("match"),c.optStringOrNull("image")) }
            Question(q.getInt("id"),q.getInt("bookId"),if(q.isNull("sectionId")) null else q.getInt("sectionId"),q.optString("section"),q.optString("type","sba"),q.optString("difficulty","unknown"),q.getString("question"),q.optString("explanation"),q.optString("notes"),q.getJSONArray("images").strings(),choices)
        }
        return Corpus(root.getString("specialty"),root.getInt("count"),books,questions)
    }

    fun state(id:Int)=UserState(
        attempts=prefs.getInt("a$id",0),correct=prefs.getInt("c$id",0),favorited=prefs.getBoolean("f$id",false),
        interval=prefs.getInt("i$id",0),dueAt=prefs.getLong("d$id",0)
    )
    fun setFavorite(id:Int,value:Boolean)=prefs.edit().putBoolean("f$id",value).apply()
    fun record(id:Int,correct:Boolean) {
        val old=state(id); val interval=if(correct) when { old.interval<=0 -> 1; old.interval==1 -> 3; else -> (old.interval*2.1).toInt().coerceAtMost(120) } else 0
        prefs.edit().putInt("a$id",old.attempts+1).putInt("c$id",old.correct+if(correct)1 else 0).putInt("i$id",interval).putLong("d$id",System.currentTimeMillis()+interval*86_400_000L).apply()
    }
    fun isDark()=prefs.getBoolean("dark",false)
    fun setDark(v:Boolean)=prefs.edit().putBoolean("dark",v).apply()
    fun reset()=prefs.edit().clear().apply()
}

private fun JSONObject.optStringOrNull(key:String)=if(isNull(key)) null else get(key).toString()
private fun JSONArray.strings()=List(length()){ optString(it) }
private inline fun <T> JSONArray.mapObjects(block:(JSONObject)->T)=List(length()){ block(getJSONObject(it)) }
