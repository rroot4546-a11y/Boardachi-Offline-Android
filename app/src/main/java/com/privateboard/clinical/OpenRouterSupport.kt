package com.privateboard.clinical

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

enum class AiProvider(val title:String) { OPENAI("OpenAI / ChatGPT"), GEMINI("Google Gemini"), ANTHROPIC("Anthropic Claude"), OPENROUTER("OpenRouter"), CUSTOM("OpenAI-compatible endpoint") }

data class AiProviderConfig(val provider:AiProvider,val apiKey:String,val model:String,val baseUrl:String="")

object AiAnswerLogic {
    fun missingAnswers(questions:List<Question>) = questions.filter { q -> q.bookId < 0 && q.choices.isNotEmpty() && q.choices.none { it.correct } }
    fun batchWarning(count:Int, provider:String) = "$count questions will be sent to $provider. Provider charges may apply. AI may be medically wrong."
    fun parse(json:String, validLetters:Set<String>):AiAnswer? = try {
        val root=JsonParser.parseString(json).asJsonObject
        val content=when {
            root.has("choices") -> root.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString
            root.has("content") -> root.getAsJsonArray("content").firstOrNull()?.asJsonObject?.get("text")?.asString.orEmpty()
            else -> json
        }.replace("```json","").replace("```","").trim()
        val obj=JsonParser.parseString(content).asJsonObject; val raw=obj.get("answer") ?: return null
        val letters=if(raw.isJsonArray) raw.asJsonArray.map{it.asString} else raw.asString.split(Regex("[,\\s]+"))
        val normalized=letters.map{it.trim().uppercase()}.filter{it.isNotEmpty()}.toSet(); val explanation=obj.get("explanation")?.asString?.trim().orEmpty(); val confidence=obj.get("confidence")?.asDouble?:0.0
        if(normalized.isEmpty()||!validLetters.containsAll(normalized)||explanation.isEmpty()||confidence !in 0.0..1.0) null else AiAnswer(normalized,explanation,confidence)
    } catch(_:Throwable){null}
}

class OpenRouterSettings(context:Context) {
    private val prefs=EncryptedSharedPreferences.create(context,"ai_secure",MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private fun key(p:AiProvider,suffix:String)="${p.name.lowercase()}_$suffix"
    fun apiKey(p:AiProvider)=prefs.getString(key(p,"key"),"").orEmpty()
    fun setApiKey(p:AiProvider,value:String){prefs.edit().apply{if(value.isBlank())remove(key(p,"key")) else putString(key(p,"key"),value.trim())}.apply()}
    fun model(p:AiProvider)=prefs.getString(key(p,"model"),defaultModel(p)).orEmpty()
    fun setModel(p:AiProvider,value:String)=prefs.edit().putString(key(p,"model"),value.trim().ifBlank{defaultModel(p)}).apply()
    fun baseUrl(p:AiProvider)=prefs.getString(key(p,"url"),"").orEmpty()
    fun setBaseUrl(p:AiProvider,value:String)=prefs.edit().apply{if(value.isBlank())remove(key(p,"url")) else putString(key(p,"url"),value.trim().removeSuffix("/"))}.apply()
    var defaultProvider:AiProvider get()=runCatching{AiProvider.valueOf(prefs.getString("default_provider",AiProvider.OPENROUTER.name)!!)}.getOrDefault(AiProvider.OPENROUTER) set(v){prefs.edit().putString("default_provider",v.name).apply()}
    var cloudOcrEnabled:Boolean get()=prefs.getBoolean("cloud_ocr",false) set(v){prefs.edit().putBoolean("cloud_ocr",v).apply()}
    var googleEmail:String get()=prefs.getString("google_email","").orEmpty() set(v){prefs.edit().putString("google_email",v).apply()}
    fun config(p:AiProvider)=AiProviderConfig(p,apiKey(p),model(p),baseUrl(p))
    fun hasToken(p:AiProvider=defaultProvider)=apiKey(p).isNotBlank()
    private fun defaultModel(p:AiProvider)=when(p){AiProvider.OPENAI->"gpt-4o-mini";AiProvider.GEMINI->"gemini-2.0-flash";AiProvider.ANTHROPIC->"claude-3-5-haiku-latest";AiProvider.OPENROUTER->"openai/gpt-4o-mini";AiProvider.CUSTOM->"vision-model"}
    var token:String get()=apiKey(AiProvider.OPENROUTER) set(v)=setApiKey(AiProvider.OPENROUTER,v)
    var model:String get()=model(AiProvider.OPENROUTER) set(v)=setModel(AiProvider.OPENROUTER,v)
}

class OpenRouterClient(private val settings:OpenRouterSettings) {
    private val client=OkHttpClient.Builder().callTimeout(120,TimeUnit.SECONDS).build(); private val gson=Gson()
    fun answer(question:Question):AiAnswer { val c=settings.config(settings.defaultProvider); if(c.apiKey.isBlank()) error("Configure an API key for ${c.provider.title} in Settings first."); return answerWith(c,question) }
    fun answerWith(c:AiProviderConfig,q:Question):AiAnswer { val valid=q.choices.indices.map{('A'.code+it).toChar().toString()}.toSet(); val prompt="Return strict JSON only: {\\\"answer\\\":[\\\"B\\\"],\\\"explanation\\\":\\\"concise medical reasoning\\\",\\\"confidence\\\":0.0}. Valid letters only. Analyze this medical board question and choices. Question: ${q.question}\\n${q.choices.sortedBy{it.order}.mapIndexed{i,x->"${('A'.code+i).toChar()}. ${x.text}"}.joinToString("\\n")}"; val body=when(c.provider){AiProvider.GEMINI->geminiBody(c,prompt);AiProvider.ANTHROPIC->gson.toJson(mapOf("model" to c.model,"max_tokens" to 600,"messages" to listOf(mapOf("role" to "user","content" to prompt))));else->gson.toJson(mapOf("model" to c.model,"temperature" to .1,"messages" to listOf(mapOf("role" to "user","content" to prompt))))}; val response=post(c,body,"application/json"); return AiAnswerLogic.parse(response,valid)?:error("${c.provider.title} returned an invalid answer.") }
    fun ocr(bitmap:Bitmap):String { val c=settings.config(settings.defaultProvider); if(c.apiKey.isBlank()) error("Configure an AI API key before enabling AI OCR."); val image=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.JPEG,82,it)}.toByteArray(); val encoded=Base64.encodeToString(image,Base64.NO_WRAP); val prompt="Read this PDF page accurately. Preserve question numbering, answer choices, and any visible answer/explanation. Return plain text only; do not invent missing text."; val body=when(c.provider){AiProvider.GEMINI->geminiVisionBody(c,prompt,encoded);AiProvider.ANTHROPIC->gson.toJson(mapOf("model" to c.model,"max_tokens" to 4000,"messages" to listOf(mapOf("role" to "user","content" to listOf(mapOf("type" to "text","text" to prompt),mapOf("type" to "image","source" to mapOf("type" to "base64","media_type" to "image/jpeg","data" to encoded)))))));else->gson.toJson(mapOf("model" to c.model,"messages" to listOf(mapOf("role" to "user","content" to listOf(mapOf("type" to "text","text" to prompt),mapOf("type" to "image_url","image_url" to mapOf("url" to "data:image/jpeg;base64,$encoded"))))))) }; val raw=post(c,body,"application/json"); return extractText(raw,c.provider) }
    private fun geminiBody(c:AiProviderConfig,p:String)=gson.toJson(mapOf("contents" to listOf(mapOf("parts" to listOf(mapOf("text" to p))))))
    private fun geminiVisionBody(c:AiProviderConfig,p:String,b:String)=gson.toJson(mapOf("contents" to listOf(mapOf("parts" to listOf(mapOf("text" to p),mapOf("inline_data" to mapOf("mime_type" to "image/jpeg","data" to b)))))))
    private fun extractText(raw:String,p:AiProvider)=try{when(p){AiProvider.GEMINI->JsonParser.parseString(raw).asJsonObject.getAsJsonArray("candidates")[0].asJsonObject.getAsJsonObject("content").getAsJsonArray("parts")[0].asJsonObject.get("text").asString;AiProvider.ANTHROPIC->JsonParser.parseString(raw).asJsonObject.getAsJsonArray("content")[0].asJsonObject.get("text").asString;else->JsonParser.parseString(raw).asJsonObject.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message").get("content").asString}}catch(_:Throwable){raw}
    private fun post(c:AiProviderConfig,body:String,media:String):String { val url=when(c.provider){AiProvider.GEMINI->"https://generativelanguage.googleapis.com/v1beta/models/${c.model}:generateContent?key=${c.apiKey}";AiProvider.ANTHROPIC->"https://api.anthropic.com/v1/messages";AiProvider.OPENAI->"https://api.openai.com/v1/chat/completions";AiProvider.OPENROUTER->"https://openrouter.ai/api/v1/chat/completions";AiProvider.CUSTOM->"${c.baseUrl.ifBlank{"https://api.openai.com/v1"}}/chat/completions"}; val b=body.toRequestBody(media.toMediaType()); val r=Request.Builder().url(url).post(b).header("Content-Type","application/json").apply{when(c.provider){AiProvider.GEMINI->Unit;AiProvider.ANTHROPIC->header("x-api-key",c.apiKey).header("anthropic-version","2023-06-01");else->header("Authorization","Bearer ${c.apiKey}")}}.build(); client.newCall(r).execute().use{if(!it.isSuccessful)error("${c.provider.title} error ${it.code}: ${it.body?.string()?.take(240)}");return it.body?.string().orEmpty()} }
}
