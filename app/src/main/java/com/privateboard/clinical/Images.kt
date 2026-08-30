package com.privateboard.clinical

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Strip the host/path from a question image URL and map it to a bundled assets/images/<name> file. */
fun imageAssetPath(url: String): String = "images/" + url.substringAfterLast('/').substringBefore('?')

@Composable
fun QuestionImage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val path = imageAssetPath(url)
    val state by produceState<Bitmap?>(initialValue = null, url) {
        value = runCatching {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
    val bitmap = state ?: return
    Card(
        modifier = modifier.padding(top = 12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
        )
    }
}

@Composable
fun QuestionImages(urls: List<String>, modifier: Modifier = Modifier) {
    urls.forEach { url ->
        key(url) { QuestionImage(url, modifier) }
    }
}

/** Extract image URLs embedded as `<img src="...">` inside an HTML explanation. */
fun inlineImageUrls(html: String): List<String> {
    if (!html.contains("<img", ignoreCase = true)) return emptyList()
    val out = ArrayList<String>(2)
    val re = Regex("""<img[^>]+src=["']((?:https?://|/)[^"']+)["']""", RegexOption.IGNORE_CASE)
    for (m in re.findAll(html)) {
        val raw = m.groupValues[1]
        val url = when {
            raw.startsWith("http", true) -> raw
            raw.startsWith("/") -> "https://boardachi.com$raw"
            else -> continue
        }
        out += url
    }
    return out
}

