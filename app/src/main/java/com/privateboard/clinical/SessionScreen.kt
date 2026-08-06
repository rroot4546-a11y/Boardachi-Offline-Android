package com.privateboard.clinical

import android.text.Html
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun rich(s: String) = Html.fromHtml(s, Html.FROM_HTML_MODE_COMPACT).toString().trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: MainViewModel) {
    val questions = vm.sessionQuestions
    val index = vm.sessionIndex.coerceIn(0, questions.lastIndex.coerceAtLeast(0))
    var selected by remember(index) { mutableStateOf<Set<Int>>(emptySet()) }
    var revealed by remember(index) { mutableStateOf(false) }
    var examAnswers by remember(questions) { mutableStateOf<Map<Int, Set<Int>>>(emptyMap()) }
    var finished by remember(questions) { mutableStateOf(false) }
    var recorded by remember(questions) { mutableStateOf<Set<Int>>(emptySet()) }

    if (questions.isEmpty()) {
        EmptySession { vm.screen = AppScreen.HOME }
        return
    }
    if (finished) {
        ExamResult(questions, examAnswers) { vm.finishSession() }
        return
    }

    val q = questions[index]
    val isMulti = q.type == "mcq" || q.choices.count { it.correct } > 1
    val correctIds = q.choices.filter { it.correct }.map { it.id }.toSet()
    val answerCorrect = selected == correctIds
    val answer: () -> Unit = {
        if (vm.sessionMode == SessionMode.EXAM) {
            examAnswers = examAnswers + (q.id to selected)
            if (q.id !in recorded) {
                vm.record(q, answerCorrect)
                recorded = recorded + q.id
            }
            if (index == questions.lastIndex) {
                finished = true
                vm.markSessionAnswered()
            } else vm.moveSessionTo(index + 1)
        } else {
            revealed = true
            if (q.id !in recorded) {
                vm.record(q, answerCorrect)
                recorded = recorded + q.id
            }
            vm.markSessionAnswered()
        }
        Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (vm.sessionMode == SessionMode.EXAM) "Exam mode" else "Study mode", fontWeight = FontWeight.Bold)
                        Text("Question ${index + 1} of ${questions.size}", fontSize = 12.sp)
                    }
                },
                navigationIcon = { IconButton({ vm.persistSession(); vm.screen = AppScreen.HOME }) { Icon(Icons.Outlined.Close, "Close") } },
                actions = {
                    IconButton({ vm.favorite(q) }) {
                        Icon(if (vm.state(q).favorited) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Bookmark", tint = if (vm.state(q).favorited) Amber else LocalContentColor.current)
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(Modifier.padding(16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (vm.sessionMode == SessionMode.STUDY && revealed) {
                        OutlinedButton({ if (index > 0) vm.moveSessionTo(index - 1) }, enabled = index > 0) { Icon(Icons.Outlined.ArrowBack, null) }
                        Button({ if (index == questions.lastIndex) vm.finishSession() else vm.moveSessionTo(index + 1) }, Modifier.weight(1f)) {
                            Text(if (index == questions.lastIndex) "Finish round" else "Next question")
                            Icon(Icons.Outlined.ArrowForward, null)
                        }
                    } else {
                        Button(answer, Modifier.fillMaxWidth(), enabled = selected.isNotEmpty()) {
                            Text(if (vm.sessionMode == SessionMode.EXAM && index == questions.lastIndex) "Submit exam" else "Lock answer")
                        }
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                LinearProgressIndicator({ (index + 1f) / questions.size }, Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(q.difficulty); Pill(q.section.ifBlank { "General" }); if (isMulti) Pill("Select all")
                }
                Text(rich(q.question), Modifier.padding(top = 18.dp), fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
            }
            items(q.choices.sortedBy { it.order }, key = { it.id }) { c ->
                ChoiceCard(c, c.id in selected, revealed, {
                    if (!revealed) selected = if (isMulti) {
                        if (c.id in selected) selected - c.id else selected + c.id
                    } else setOf(c.id)
                }, q.type)
            }
            if (revealed) item { AnswerPanel(q, answerCorrect) }
        }
    }
}

@Composable private fun Pill(text: String) {
    Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text.uppercase(), Modifier.padding(7.dp, 4.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
    }
}

@Composable
private fun ChoiceCard(c: Choice, selected: Boolean, revealed: Boolean, onClick: () -> Unit, type: String) {
    val success = revealed && c.correct
    val wrong = revealed && selected && !c.correct
    val color = when { success -> Color(0xFF2E7D5B); wrong -> MaterialTheme.colorScheme.error; selected -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.outlineVariant }
    OutlinedCard(
        Modifier.fillMaxWidth().clickable(enabled = !revealed, onClick = onClick),
        colors = CardDefaults.outlinedCardColors(containerColor = if (success) Color(0xFF2E7D5B).copy(.1f) else if (wrong) MaterialTheme.colorScheme.errorContainer.copy(.35f) else MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (selected || success || wrong) 2.dp else 1.dp, color)
    ) {
        Row(Modifier.padding(16.dp)) {
            Text(('A'.code + (c.order - 1).coerceAtLeast(0)).toChar().toString(), fontWeight = FontWeight.Black, color = color)
            Spacer(Modifier.width(14.dp))
            Text(c.text.ifBlank { if (type == "match") "Matching item" else "Image option" }, Modifier.weight(1f), lineHeight = 23.sp)
            if (success) Icon(Icons.Filled.CheckCircle, null, tint = color) else if (wrong) Icon(Icons.Filled.Cancel, null, tint = color)
        }
    }
}

@Composable private fun AnswerPanel(q: Question, correct: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = if (correct) Color(0xFF2E7D5B).copy(.12f) else MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(18.dp)) {
            Text(if (correct) "Correct — strengthen the trace" else "Review the reasoning", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            val explanation = q.explanation.ifBlank { q.notes }
            Text(if (explanation.isBlank()) "No explanation was included with this source record." else rich(explanation), lineHeight = 23.sp)
            if (q.images.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("${q.images.size} source image reference${if (q.images.size > 1) "s" else ""} preserved in the offline record.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun EmptySession(close: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FilterAltOff, null, Modifier.size(52.dp)); Text("No questions match this filter", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Button(close, Modifier.padding(top = 16.dp)) { Text("Go back") }
        }
    }
}

@Composable private fun ExamResult(qs: List<Question>, answers: Map<Int, Set<Int>>, close: () -> Unit) {
    val score = qs.count { q -> answers[q.id] == q.choices.filter { it.correct }.map { it.id }.toSet() }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.AssignmentTurnedIn, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Exam complete", fontWeight = FontWeight.Black, fontSize = 28.sp)
        Text("$score / ${qs.size}", fontWeight = FontWeight.Black, fontSize = 50.sp, color = MaterialTheme.colorScheme.primary)
        Text("${if (qs.isEmpty()) 0 else score * 100 / qs.size}% correct", fontSize = 18.sp)
        Button(close, Modifier.padding(top = 24.dp)) { Text("View progress") }
    }
}
