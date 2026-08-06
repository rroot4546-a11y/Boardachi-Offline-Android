package com.privateboard.clinical

import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class StreamingCorpusParserTest {
    @Test
    fun androidJsonReaderCanStreamRepresentativeCorpusShape() {
        val json = """
            {
              "specialty":"Internal Medicine",
              "count":1,
              "books":[{"id":20,"title":"Review","edition":null,"year":2025,"authors":["Author"],"count":1}],
              "questions":[{
                "id":7,"bookId":20,"sectionId":null,"section":"Cardiology","type":"sba",
                "difficulty":null,"question":"Question?","explanation":"Because.","notes":"",
                "images":null,
                "choices":[{"id":1,"order":1,"text":"Answer","correct":true,"kind":"regular","match":null,"image":null}]
              }]
            }
        """.trimIndent()

        JsonReader(StringReader(json)).use { reader ->
            reader.beginObject()
            var count = 0
            var sawQuestions = false
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "count" -> count = reader.nextInt()
                    "questions" -> {
                        reader.beginArray()
                        assertTrue(reader.hasNext())
                        reader.skipValue()
                        reader.endArray()
                        sawQuestions = true
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            assertEquals(1, count)
            assertTrue(sawQuestions)
        }
    }
}
