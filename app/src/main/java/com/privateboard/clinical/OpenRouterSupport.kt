package com.privateboard.clinical

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object AiAnswerLogic {
    fun missingAnswers(questions: List<Question>) =
        questions.filter { question ->
            question.bookId < 0 && question.choices.isNotEmpty() && question.choices.none { it.correct }
        }

    fun batchWarning(count: Int) =
        "$count custom questions will be sent serially to OpenRouter. Provider charges may apply. AI may be medically wrong."

    fun parse(json: String, validLetters: Set<String>): AiAnswer? = try {
        val root = JsonParser.parseString(json).asJsonObject
        val content = root.getAsJsonArray("choices")[0]
            .asJsonObject
            .getAsJsonObject("message")
            .get("content")
            .asString
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = JsonParser.parseString(content).asJsonObject
        val answer = obj.get("answer")
        val letters = when {
            answer.isJsonArray -> answer.asJsonArray.map { it.asString }
            else -> answer.asString.split(Regex("[,\\s]+"))
        }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val explanation = obj.get("explanation")?.asString?.trim().orEmpty()
        val confidence = obj.get("confidence")?.asDouble ?: 0.0
        if (
            letters.isEmpty() ||
            !validLetters.containsAll(letters) ||
            explanation.isEmpty() ||
            confidence !in 0.0..1.0
        ) null else AiAnswer(letters, explanation, confidence)
    } catch (_: Exception) {
        null
    }
}

class OpenRouterSettings(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "openrouter_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var token: String
        get() = prefs.getString("token", "").orEmpty()
        set(value) {
            if (value.isBlank()) {
                prefs.edit().remove("token").apply()
            } else {
                prefs.edit().putString("token", value.trim()).apply()
            }
        }

    var model: String
        get() = prefs.getString("model", "openai/gpt-4o-mini").orEmpty()
        set(value) {
            prefs.edit()
                .putString("model", value.trim().ifBlank { "openai/gpt-4o-mini" })
                .apply()
        }

    fun hasToken() = token.isNotBlank()
}

class OpenRouterClient {
    companion object {
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    }

    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
    private val gson = Gson()

    fun answer(question: Question, token: String, model: String): AiAnswer {
        val options = question.choices
            .sortedBy { it.order }
            .mapIndexed { index, choice -> "${('A'.code + index).toChar()}. ${choice.text}" }
            .joinToString("\n")
        val prompt = "You are helping review a medical study question. Return strict JSON only: " +
            "{\"answer\":[\"B\"],\"explanation\":\"concise reasoning\",\"confidence\":0.0}. " +
            "Valid answer letters only. This is unverified study assistance.\n" +
            "Question: ${question.question}\n$options"
        val body = gson.toJson(
            mapOf(
                "model" to model,
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                "temperature" to 0.1,
            ),
        ).toRequestBody("application/json".toMediaType())

        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer $token")
                .header("HTTP-Referer", "https://localhost")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    if (attempt++ >= 2) error("OpenRouter rate limit persisted. Try again later.")
                    val wait = (response.header("Retry-After")?.toLongOrNull() ?: 2L).coerceAtMost(30)
                    Thread.sleep(wait * 1000)
                    return@use
                }
                if (!response.isSuccessful) {
                    error("OpenRouter error ${response.code}: ${response.body?.string()?.take(240)}")
                }
                val valid = question.choices.indices
                    .map { ('A'.code + it).toChar().toString() }
                    .toSet()
                return AiAnswerLogic.parse(response.body?.string().orEmpty(), valid)
                    ?: error("OpenRouter returned an invalid answer.")
            }
        }
    }
}
