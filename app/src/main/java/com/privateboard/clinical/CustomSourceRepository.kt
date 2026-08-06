package com.privateboard.clinical

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class CustomSourceRepository(private val context: Context) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("clinical_custom_sources", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "custom_sources").apply { mkdirs() }
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    fun list(): List<CustomSource> { val json = prefs.getString("sources", "[]") ?: "[]"; return try { gson.fromJson(json, object : TypeToken<List<CustomSource>>() {}.type) } catch (_: RuntimeException) { emptyList() } }
    fun questions(): List<Question> = list().flatMap { loadQuestions(it.id) }
    fun books(): List<Book> = list().map { Book(bookId(it.id), it.name, "Custom Source", null, listOf("Imported on this device"), it.questionCount) }
    /** The source directory owns both private PDF copies, parsed questions, and generated answers. */
    fun delete(source: CustomSource) { File(root, source.id).deleteRecursively(); saveList(list().filterNot { it.id == source.id }) }
    fun saveAiAnswer(question: Question, answer: AiAnswer): Question { val source = list().firstOrNull { bookId(it.id) == question.bookId } ?: error("Custom source not found."); val all = loadQuestions(source.id); val updated = question.copy(choices = question.choices.mapIndexed { i, c -> c.copy(correct = (('A'.code + i).toChar().toString() in answer.letters)) }, explanation = answer.explanation, aiGenerated = true, aiConfidence = answer.confidence); File(root, "${source.id}/questions.json").writeText(gson.toJson(all.map { if (it.id == question.id) updated else it })); return updated }

    fun importPdf(questionUri: Uri, answerUri: Uri?, requestedName: String, onProgress: (CustomImportProgress) -> Unit): CustomSource {
        val questionName = queryName(questionUri) ?: "questions.pdf"
        val answerName = answerUri?.let(::queryName) ?: answerUri?.let { "solutions.pdf" }
        val id = UUID.randomUUID().toString(); val folder = File(root, id).apply { mkdirs() }
        val questionPdf = File(folder, "questions.pdf"); val answerPdf = answerUri?.let { File(folder, "solutions.pdf") }
        try {
            copyPrivate(questionUri, questionPdf, questionName, "Questions", onProgress)
            answerUri?.let { copyPrivate(it, answerPdf!!, answerName!!, "Solutions", onProgress) }

            val questionNative = extractNative(questionPdf, "Questions", onProgress)
            var records = CustomQuestionParser.parseRecords(questionNative, bookId(id), firstQuestionId(id)); var usedOcr = false
            if (CustomSourceLogic.shouldUseOcr(questionNative, records.size)) {
                usedOcr = true; records = CustomQuestionParser.parseRecords(extractOcr(questionPdf, "Questions", onProgress), bookId(id), firstQuestionId(id))
            }
            if (records.isEmpty()) error("No supported questions were found. Use numbered questions and A-D choices.")

            var report = AnswerMergeResult(records, 0, 0)
            answerPdf?.let { pdf ->
                val native = extractNative(pdf, "Solutions", onProgress)
                var answers = CustomAnswerParser.parse(native)
                if (CustomSourceLogic.shouldUseOcr(native, answers.size)) { usedOcr = true; answers = CustomAnswerParser.parse(extractOcr(pdf, "Solutions", onProgress)) }
                report = CustomAnswerParser.merge(records, answers)
            }
            val finalQuestions = report.questions.map { it.question }
            onProgress(CustomImportProgress(ImportStage.SAVING, message = "Saving ${finalQuestions.size} questions"))
            File(folder, "questions.json").writeText(gson.toJson(finalQuestions))
            val source = CustomSource(id, CustomSourceLogic.safeDisplayName(requestedName, questionName), questionName, System.currentTimeMillis(), finalQuestions.size, usedOcr, answerName, report.verifiedCount, report.missingCount, report.skippedOrUnmatchedEntries)
            saveList(list() + source)
            onProgress(CustomImportProgress(ImportStage.COMPLETE, message = "Imported ${finalQuestions.size}: ${report.verifiedCount} verified, ${report.missingCount} missing"))
            return source
        } catch (t: Throwable) { folder.deleteRecursively(); throw t }
    }

    private fun copyPrivate(uri: Uri, target: File, name: String, label: String, onProgress: (CustomImportProgress) -> Unit) {
        onProgress(CustomImportProgress(ImportStage.READING, message = "Copying $label PDF • $name"))
        context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { input.copyTo(it) } } ?: error("The selected $label PDF could not be opened.")
    }
    private fun extractNative(pdf: File, label: String, onProgress: (CustomImportProgress) -> Unit): String = PDDocument.load(pdf).use { doc ->
        val total = doc.numberOfPages; val out = StringBuilder()
        for (page in 1..total) { onProgress(CustomImportProgress(ImportStage.READING, page, total, "$label • reading native text • page $page of $total")); out.append(PDFTextStripper().apply { startPage = page; endPage = page }.getText(doc)).append('\n') }
        out.toString()
    }
    private fun extractOcr(pdf: File, label: String, onProgress: (CustomImportProgress) -> Unit): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); val out = StringBuilder()
        try { ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
            val total = renderer.pageCount
            for (i in 0 until total) { onProgress(CustomImportProgress(ImportStage.OCR, i + 1, total, "$label • offline OCR • page ${i + 1} of $total")); renderer.openPage(i).use { page ->
                val scale = (1600f / page.width).coerceIn(1f, 2.5f); val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                try { bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); out.append(Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text).append('\n') } finally { bitmap.recycle() }
            } }
        } }; return out.toString() } finally { recognizer.close() }
    }
    private fun loadQuestions(id: String): List<Question> { val file = File(root, "$id/questions.json"); if (!file.exists()) return emptyList(); return try { gson.fromJson(file.readText(), object : TypeToken<List<Question>>() {}.type) } catch (_: RuntimeException) { emptyList() } }
    private fun saveList(value: List<CustomSource>) { prefs.edit().putString("sources", gson.toJson(value)).commit() }
    private fun queryName(uri: Uri): String? { if (uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) }; return uri.lastPathSegment?.substringAfterLast('/') }
    private fun bookId(id: String) = -1 - (id.hashCode() and 0x3fffffff)
    private fun firstQuestionId(id: String) = 1_000_000_000 + (id.hashCode() and 0xffff) * 10_000
}
