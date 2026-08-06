package com.privateboard.clinical

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

class CorpusRepository(private val context: Context) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("clinical_deck_progress", Context.MODE_PRIVATE)
    }

    /**
     * Streams the bundled corpus instead of first creating a second, very large JSONObject tree.
     * This keeps peak startup memory predictable on older and low-memory Android devices.
     */
    fun loadCorpus(): Corpus {
        openCorpusAsset().use { source ->
            JsonReader(InputStreamReader(source, Charsets.UTF_8)).use { reader ->
                return reader.readCorpus()
            }
        }
    }

    private fun openCorpusAsset(): InputStream {
        return try {
            GZIPInputStream(context.assets.open("corpus.json.gz"))
        } catch (_: FileNotFoundException) {
            // Some Android packaging toolchains remove the final .gz extension.
            val raw = BufferedInputStream(context.assets.open("corpus.json"))
            raw.mark(2)
            val first = raw.read()
            val second = raw.read()
            raw.reset()
            if (first == 0x1f && second == 0x8b) GZIPInputStream(raw) else raw
        }
    }

    fun state(id: Int) = UserState(
        attempts = prefs.getInt("a$id", 0),
        correct = prefs.getInt("c$id", 0),
        favorited = prefs.getBoolean("f$id", false),
        interval = prefs.getInt("i$id", 0),
        dueAt = prefs.getLong("d$id", 0)
    )

    fun setFavorite(id: Int, value: Boolean) = prefs.edit().putBoolean("f$id", value).apply()

    fun savedSession(config: SessionConfig): SessionSnapshot? = savedSession(SessionProgressLogic.identity(config))

    fun savedSession(identity: String): SessionSnapshot? {
        val encoded = prefs.getString(sessionKey(identity), null) ?: return null
        return try {
            Gson().fromJson(encoded, SessionSnapshot::class.java)
        } catch (_: RuntimeException) {
            null
        }
    }

    /** commit() is deliberate: a close/kill immediately after answering must not lose position. */
    fun saveSession(snapshot: SessionSnapshot) {
        prefs.edit().putString(sessionKey(snapshot.identity), Gson().toJson(snapshot)).commit()
    }

    fun clearSession(identity: String) {
        prefs.edit().remove(sessionKey(identity)).commit()
    }

    // Java's hash is not collision-proof, so include an encoded identity to keep scopes independent.
    private fun sessionKey(identity: String) = "session_" +
        android.util.Base64.encodeToString(identity.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    fun record(id: Int, correct: Boolean) {
        val old = state(id)
        val interval = if (correct) when {
            old.interval <= 0 -> 1
            old.interval == 1 -> 3
            else -> (old.interval * 2.1).toInt().coerceAtMost(120)
        } else 0
        prefs.edit()
            .putInt("a$id", old.attempts + 1)
            .putInt("c$id", old.correct + if (correct) 1 else 0)
            .putInt("i$id", interval)
            .putLong("d$id", System.currentTimeMillis() + interval * 86_400_000L)
            .apply()
    }

    fun attemptCount(id: Int) = prefs.getInt("a$id", 0)

    fun isDark() = prefs.getBoolean("dark", false)
    fun setDark(value: Boolean) = prefs.edit().putBoolean("dark", value).apply()
    fun reset() = prefs.edit().clear().apply()
}

private fun JsonReader.readCorpus(): Corpus {
    var specialty = "Internal Medicine"
    var declaredCount = 0
    val books = ArrayList<Book>(8)
    val questions = ArrayList<Question>(12_000)

    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "specialty" -> specialty = nextStringSafe(specialty)
            "count" -> declaredCount = nextIntSafe()
            "books" -> {
                beginArray()
                while (hasNext()) books += readBook()
                endArray()
            }
            "questions" -> {
                beginArray()
                while (hasNext()) questions += readQuestion()
                endArray()
            }
            else -> skipValue()
        }
    }
    endObject()

    require(books.isNotEmpty()) { "The offline library contains no books." }
    require(questions.isNotEmpty()) { "The offline library contains no questions." }
    val count = if (declaredCount > 0) declaredCount else questions.size
    require(count == questions.size) {
        "Offline library is incomplete: expected $count questions, found ${questions.size}."
    }
    return Corpus(specialty, count, books, questions)
}

private fun JsonReader.readBook(): Book {
    var id = 0
    var title = "Untitled source"
    var edition: String? = null
    var year: String? = null
    var count = 0
    val authors = ArrayList<String>()
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "id" -> id = nextIntSafe()
            "title" -> title = nextStringSafe(title)
            "edition" -> edition = nextNullableString()
            "year" -> year = nextNullableString()
            "count" -> count = nextIntSafe()
            "authors" -> readStringArrayInto(authors)
            else -> skipValue()
        }
    }
    endObject()
    return Book(id, title, edition, year, authors, count)
}

private fun JsonReader.readQuestion(): Question {
    var id = 0
    var bookId = 0
    var sectionId: Int? = null
    var section = "Uncategorized"
    var type = "sba"
    var difficulty = "unknown"
    var question = ""
    var explanation = ""
    var notes = ""
    val images = ArrayList<String>()
    val choices = ArrayList<Choice>(5)

    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "id" -> id = nextIntSafe()
            "bookId" -> bookId = nextIntSafe()
            "sectionId" -> sectionId = nextNullableInt()
            "section" -> section = nextStringSafe(section)
            "type" -> type = nextStringSafe(type)
            "difficulty" -> difficulty = nextStringSafe(difficulty)
            "question" -> question = nextStringSafe("")
            "explanation" -> explanation = nextStringSafe("")
            "notes" -> notes = nextStringSafe("")
            "images" -> readStringArrayInto(images)
            "choices" -> {
                if (peek() == JsonToken.NULL) nextNull() else {
                    beginArray()
                    while (hasNext()) choices += readChoice()
                    endArray()
                }
            }
            else -> skipValue()
        }
    }
    endObject()
    require(id > 0 && bookId > 0) { "Invalid question record in offline library." }
    return Question(
        id, bookId, sectionId, section, type, difficulty,
        question, explanation, notes, images, choices
    )
}

private fun JsonReader.readChoice(): Choice {
    var id = 0
    var order = 0
    var text = ""
    var correct = false
    var kind = "regular"
    var match: Int? = null
    var image: String? = null
    beginObject()
    while (hasNext()) {
        when (nextName()) {
            "id" -> id = nextIntSafe()
            "order" -> order = nextIntSafe()
            "text" -> text = nextStringSafe("")
            "correct" -> correct = nextBooleanSafe()
            "kind" -> kind = nextStringSafe(kind)
            "match" -> match = nextNullableInt()
            "image" -> image = nextNullableString()
            else -> skipValue()
        }
    }
    endObject()
    return Choice(id, order, text, correct, kind, match, image)
}

private fun JsonReader.readStringArrayInto(target: MutableList<String>) {
    if (peek() == JsonToken.NULL) {
        nextNull()
        return
    }
    beginArray()
    while (hasNext()) {
        nextNullableString()?.takeIf(String::isNotBlank)?.let(target::add)
    }
    endArray()
}

private fun JsonReader.nextStringSafe(default: String): String = nextNullableString() ?: default

private fun JsonReader.nextNullableString(): String? = when (peek()) {
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

private fun JsonReader.nextIntSafe(default: Int = 0): Int = nextNullableInt() ?: default

private fun JsonReader.nextNullableInt(): Int? = when (peek()) {
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.NUMBER, JsonToken.STRING -> nextString().toDoubleOrNull()?.toInt()
    else -> { skipValue(); null }
}

private fun JsonReader.nextBooleanSafe(default: Boolean = false): Boolean = when (peek()) {
    JsonToken.NULL -> { nextNull(); default }
    JsonToken.BOOLEAN -> nextBoolean()
    JsonToken.STRING, JsonToken.NUMBER -> nextString().let { it == "1" || it.equals("true", true) }
    else -> { skipValue(); default }
}
