package com.privateboard.clinical

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PackagedCorpusAssetTest {
    @Test
    fun corpusSourceExistsAndIsGzip() {
        val file = File("src/main/assets/corpus.json.gz")
        assertTrue("corpus.json.gz must exist", file.isFile)
        file.inputStream().use { input ->
            assertTrue(input.read() == 0x1f && input.read() == 0x8b)
        }
    }
}
