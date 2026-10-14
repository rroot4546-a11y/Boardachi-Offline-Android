package com.privateboard.clinical

import android.text.Html

private fun plainQ(s: String) =
    Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString()
        .replace(Regex("\\s+"), " ").trim()

private val AGE_SEX = Regex("""(\d{1,3})\s?-\s?year\s?-\s?old\s+(woman|man|female|male|girl|boy)""", RegexOption.IGNORE_CASE)
private val DURATION = Regex("""(\d+)\s?-\s?(day|week|month|year)s?\s+of\s+([^.,;]+)""", RegexOption.IGNORE_CASE)
private val VITALS_KEEP = listOf(
    Regex("""febrile|fever of ([\d.]+)""", RegexOption.IGNORE_CASE),
    Regex("""(tachy|brady)card\w*|heart rate (\d+)""", RegexOption.IGNORE_CASE),
    Regex("""hypotens\w*|blood pressure (\d+/\d+)""", RegexOption.IGNORE_CASE),
    Regex("""(tachypn\w*|respiratory rate (\d+))""", RegexOption.IGNORE_CASE),
    Regex("""oxygen saturation (\d+)""", RegexOption.IGNORE_CASE),
    Regex("""hypox\w*""", RegexOption.IGNORE_CASE),
)
private val EXAM_KEEP = listOf(
    Regex("""(crackles|wheezes|rhonchi|stridor)([^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""(murmur|gallop|rub)([^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""(edema|clubbing|cyanosis|pallor|icterus|jaundice)([^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""(tenderness|guarding|rebound|rigidity)([^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""(lymphadenopathy|hepatomegaly|splenomegaly)([^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""(deficit|weakness|palsy|aphasia|neglect)([^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""(rash|purpura|petechiae|ulcer|nodule)([^.,;]*)""", RegexOption.IGNORE_CASE),
)
private val COMORBID = mapOf(
    "hypertension" to "HTN", "hyperlipidemia" to "HL", "diabetes" to "DM",
    "coronary artery disease" to "CAD", "heart failure" to "HF",
    "chronic kidney disease" to "CKD", "copd" to "COPD", "asthma" to "Asthma",
    "atrial fibrillation" to "AF", "obesity" to "Obese", "smoking" to "Smoker",
    "hiv" to "HIV", "tuberculosis" to "TB",
)
private val QUESTION_TAIL = listOf(
    Regex("""which of the following is the (most likely diagnosis|diagnosis)[^?]*\?""", RegexOption.IGNORE_CASE) to "→ Dx?",
    Regex("""which of the following is the appropriate (treatment|management|therapy)[^?]*\?""", RegexOption.IGNORE_CASE) to "→ Rx?",
    Regex("""what is the (most appropriate )?next step[^?]*\?""", RegexOption.IGNORE_CASE) to "→ Next?",
    Regex("""which of the following[^?]*\?""", RegexOption.IGNORE_CASE) to "→ ?",
)

/** Rule-based stem condenser: long vignette → ultra-short stem. Offline, zero deps. */
fun condenseStem(raw: String): String {
    val s = plainQ(raw)
    if (s.length <= 140) return s
    val out = ArrayList<String>()

    AGE_SEX.find(s)?.let {
        val age = it.groupValues[1]
        val sex = if (it.groupValues[2].lowercase().startsWith("w") ||
            it.groupValues[2].equals("female", true) ||
            it.groupValues[2].equals("girl", true)) "F" else "M"
        out += "$age$sex"
    }

    val lower = s.lowercase()
    val comor = COMORBID.filterKeys { lower.contains(it) }.values.distinct().take(4)
    if (comor.isNotEmpty()) out += comor.joinToString("+")

    DURATION.find(s)?.let {
        val unit = when (it.groupValues[2].lowercase()[0]) {
            'd' -> "d"; 'w' -> "w"; 'm' -> "mo"; else -> "y"
        }
        var complaint = it.groupValues[3].trim()
        if (complaint.length > 60) complaint = complaint.take(60).trimEnd() + "…"
        out += "${it.groupValues[1]}$unit $complaint"
    } ?: run {
        // fallback: first sentence trimmed
        val first = s.substringBefore(". ")
        out += if (first.length > 90) first.take(90).trimEnd() + "…" else first
    }

    for (rx in VITALS_KEEP) {
        rx.find(s)?.let {
            var hit = it.value.trim()
            if (hit.length > 45) hit = hit.take(45).trimEnd() + "…"
            if (out.none { e -> e.contains(hit.take(12), true) }) out += hit
        }
    }
    for (rx in EXAM_KEEP) {
        rx.find(s)?.let {
            var hit = it.value.trim()
            if (hit.length > 45) hit = hit.take(45).trimEnd() + "…"
            if (out.none { e -> e.contains(hit.take(12), true) }) out += hit
        }
    }

    var tail = "→ ?"
    for ((rx, label) in QUESTION_TAIL) {
        if (rx.containsMatchIn(s)) { tail = label; break }
    }
    out += tail
    return out.joinToString(" • ")
}

/** Short line for search results / lists: first 110 chars of condensed stem. */
fun shortLine(raw: String): String {
    val c = condenseStem(raw)
    return if (c.length <= 110) c else c.take(110).trimEnd() + "…"
}
