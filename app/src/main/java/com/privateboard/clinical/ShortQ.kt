package com.privateboard.clinical

import android.text.Html

private fun plainQ(s: String) =
    Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString()
        .replace(Regex("\\s+"), " ").trim()

private val AGE_SEX = Regex("""(\d{1,3})\s?-\s?year\s?-\s?old\s+(woman|man|female|male|girl|boy)""", RegexOption.IGNORE_CASE)

// ---- comorbidity short map ----
private val COMORBID = listOf(
    Regex("""hypertension""", RegexOption.IGNORE_CASE) to "HTN",
    Regex("""hyperlipidemia""", RegexOption.IGNORE_CASE) to "HL",
    Regex("""diabetes( mellitus)?""", RegexOption.IGNORE_CASE) to "DM",
    Regex("""coronary artery disease|ischemic heart disease""", RegexOption.IGNORE_CASE) to "CAD",
    Regex("""heart failure""", RegexOption.IGNORE_CASE) to "HF",
    Regex("""chronic kidney disease""", RegexOption.IGNORE_CASE) to "CKD",
    Regex("""\bcopd\b""", RegexOption.IGNORE_CASE) to "COPD",
    Regex("""\basthma\b""", RegexOption.IGNORE_CASE) to "Asthma",
    Regex("""atrial fibrillation""", RegexOption.IGNORE_CASE) to "AF",
    Regex("""\bobesity\b|\bobese\b""", RegexOption.IGNORE_CASE) to "Obese",
    Regex("""smok\w*""", RegexOption.IGNORE_CASE) to "Smoker",
    Regex("""\bhiv\b|human immunodeficiency""", RegexOption.IGNORE_CASE) to "HIV",
    Regex("""syphilis""", RegexOption.IGNORE_CASE) to "Syphilis",
    Regex("""tuberculosis|\btb\b""", RegexOption.IGNORE_CASE) to "TB",
)

// ---- presentation: keep chief complaint + duration ----
private val DURATION = Regex("""(\d+)\s?-\s?(day|week|month|year)s?\s+(?:history\s+)?of\s+([^.,;]+)""", RegexOption.IGNORE_CASE)

// ---- vitals: ONLY abnormal survive ----
private fun abnormalVitals(s: String): List<String> {
    val out = ArrayList<String>()
    Regex("""fever of ([\d.]+)""", RegexOption.IGNORE_CASE).find(s)?.let {
        out += "T ${it.groupValues[1]}°F"
    } ?: if (Regex("""\bfebrile\b""", RegexOption.IGNORE_CASE).containsMatchIn(s)) out += "Febrile"
    Regex("""heart rate (\d+)""", RegexOption.IGNORE_CASE).find(s)?.let {
        val hr = it.groupValues[1].toIntOrNull() ?: 0
        if (hr >= 100) out += "HR$hr" else if (hr in 1..59) out += "HR$hr"
    }
    Regex("""blood pressure (\d+)/(\d+)""", RegexOption.IGNORE_CASE).find(s)?.let {
        val sys = it.groupValues[1].toIntOrNull() ?: 999
        if (sys < 100 || sys >= 180) out += "BP ${it.groupValues[1]}/${it.groupValues[2]}"
    }
    Regex("""respiratory rate (\d+)""", RegexOption.IGNORE_CASE).find(s)?.let {
        val rr = it.groupValues[1].toIntOrNull() ?: 0
        if (rr >= 22 || rr in 1..11) out += "RR$rr"
    }
    Regex("""oxygen saturation (\d+)[^.,;]*?(?:and (\d+)[^.,;]*?with ambulation)?""", RegexOption.IGNORE_CASE).find(s)?.let {
        val o2 = it.groupValues[1].toIntOrNull() ?: 100
        if (o2 < 95) {
            val amb = it.groupValues[2]
            out += if (amb.isNotEmpty()) "O2 $o2%→$amb% ambul" else "O2 $o2% RA"
        }
    }
    return out
}

// ---- exam / labs / imaging: ONLY positive discriminators ----
private val KEEP_PATTERNS = listOf(
    // lung
    Regex("""(crackles|rales)[^.,;]*""", RegexOption.IGNORE_CASE),
    Regex("""(wheezes|wheezing)[^.,;]*""", RegexOption.IGNORE_CASE),
    Regex("""(decreased breath sounds|bronchial breathing|egophony|dullness to percussion)[^.,;]*""", RegexOption.IGNORE_CASE),
    // cardiac
    Regex("""((new |systolic |diastolic )?(regurgitant )?murmur[^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""(S3|S4|gallop|rub|JVD|jugular[^.,;]*)""", RegexOption.IGNORE_CASE),
    // abdomen / nodes / organs
    Regex("""((tender|guarding|rebound|rigid)[^.,;]{0,40})""", RegexOption.IGNORE_CASE),
    Regex("""((hepatomegaly|splenomegaly|lymphadenopathy)[^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""((edema|clubbing|cyanosis|pallor|icterus|jaundice)[^.,;]*)""", RegexOption.IGNORE_CASE),
    // neuro
    Regex("""((weakness|deficit|palsy|aphasia|neglect|confusion|cachectic|chronically ill)[^.,;]*)""", RegexOption.IGNORE_CASE),
    // skin / mouth
    Regex("""((rash|purpura|petechiae|ulcer|nodule|white plaques)[^.,;]*)""", RegexOption.IGNORE_CASE),
    // discriminating labs
    Regex("""(white blood cell count[^.,;]*elevated[^.,;]*|WBC[^.,;]*\d[\d,]*[^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""((leukocytosis|neutrophil\w* predominance|lymphocytosis)[^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""((anemia|thrombocyt\w+|hematuria|proteinuria|hyperkalemia|hyponatremia)[^.,;]*)""", RegexOption.IGNORE_CASE),
    Regex("""((HIV|CD4|viral load)[^.,;]{0,50})""", RegexOption.IGNORE_CASE),
    // imaging — the money line
    Regex("""((chest x-?ray|CXR|CT|MRI|ultrasound|echocardiogram|ECG)[^.,;]{0,80})""", RegexOption.IGNORE_CASE),
    Regex("""((infiltrate|consolidation|effusion|pneumothorax|interstitial[^.,;]*|vegetation|ejection fraction[^.,;]*)[^.,;]*)""", RegexOption.IGNORE_CASE),
)

private val NOISE = listOf(
    Regex("""basic laboratory testing reveals normal[^.]*\.""", RegexOption.IGNORE_CASE),
    Regex("""(hematocrit|platelets?|electrolytes?|creatinine|liver function)[^.,;]*normal[^.,;]*""", RegexOption.IGNORE_CASE),
    Regex("""no pleural effusion""", RegexOption.IGNORE_CASE),
    Regex("""on physical examination, (he|she) (is|has) (afebrile|comfortable)[^.,;]*""", RegexOption.IGNORE_CASE),
)

private val QUESTION_TAIL = listOf(
    Regex("""most likely diagnosis|diagnosis[^?]{0,40}\?""", RegexOption.IGNORE_CASE) to "→ Most likely Dx?",
    Regex("""most likely pathogen|causative organism|etiologic agent[^?]{0,40}\?""", RegexOption.IGNORE_CASE) to "→ Pathogen?",
    Regex("""appropriate (treatment|management|therapy)|treatment of choice[^?]{0,40}\?""", RegexOption.IGNORE_CASE) to "→ Best Rx?",
    Regex("""most appropriate next step|next step in management[^?]{0,40}\?""", RegexOption.IGNORE_CASE) to "→ Next step?",
    Regex("""should this patient be admitted[^?]*\?""", RegexOption.IGNORE_CASE) to "→ Admit?",
    Regex("""should not raise[^?]*\?""", RegexOption.IGNORE_CASE) to "→ NOT suggestive?",
    Regex("""which of the following[^?]*\?""", RegexOption.IGNORE_CASE) to "→ ?",
)

/**
 * Professional condenser: long vignette → short stem WITHOUT losing the core idea.
 * Rules: keep age/sex + comorbidities + chief complaint + ONLY abnormal vitals +
 * positive exam/labs/imaging discriminators + question intent. Drop normal values,
 * normal labs, and filler phrasing.
 */
fun condenseStem(raw: String): String {
    val s = plainQ(raw)
    if (s.length <= 140) return s
    val out = ArrayList<String>()

    AGE_SEX.find(s)?.let {
        val age = it.groupValues[1]
        val w = it.groupValues[2].lowercase()
        out += "$age" + if (w.startsWith("w") || w == "female" || w == "girl") "F" else "M"
    }

    val comor = COMORBID.filter { (rx, _) -> rx.containsMatchIn(s) }.map { it.second }.distinct().take(5)
    if (comor.isNotEmpty()) out += comor.joinToString("+")

    DURATION.find(s)?.let {
        val unit = when (it.groupValues[2].lowercase()[0]) {
            'd' -> "d"; 'w' -> "w"; 'm' -> "mo"; else -> "y"
        }
        var complaint = it.groupValues[3].trim()
            .replace(Regex("""^(a|an|the)\s+""", RegexOption.IGNORE_CASE), "")
        if (complaint.length > 70) complaint = complaint.take(70).trimEnd() + "…"
        out += "${it.groupValues[1]}$unit $complaint"
    }

    out += abnormalVitals(s)

    val seen = HashSet<String>()
    for (rx in KEEP_PATTERNS) {
        rx.findAll(s).forEach { m ->
            var hit = m.value.trim().trimEnd('.', ',', ';')
                .replace(Regex("""^(On lung examination, |Chest radiography demonstrates |Basic laboratory[^,]*, )""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+"""), " ")
            if (hit.length > 65) hit = hit.take(65).trimEnd() + "…"
            val key = hit.take(14).lowercase()
            if (hit.length >= 4 && seen.add(key) && out.size < 9) out += hit
        }
    }

    var tail = "→ ?"
    for ((rx, label) in QUESTION_TAIL) {
        if (rx.containsMatchIn(s)) { tail = label; break }
    }
    out += tail
    return out.joinToString(" • ")
}

fun shortLine(raw: String): String {
    val c = condenseStem(raw)
    return if (c.length <= 110) c else c.take(110).trimEnd() + "…"
}
