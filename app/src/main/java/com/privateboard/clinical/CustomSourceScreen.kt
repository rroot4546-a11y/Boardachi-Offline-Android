package com.privateboard.clinical

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun CustomSourceScreen(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var questionUri by remember { mutableStateOf<Uri?>(null) }; var questionName by remember { mutableStateOf("") }
    var answerUri by remember { mutableStateOf<Uri?>(null) }; var answerName by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }; var remove by remember { mutableStateOf<CustomSource?>(null) }; var confirmAi by remember { mutableStateOf(false) }
    fun displayName(uri: Uri): String { if (uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) }; return uri.lastPathSegment?.substringAfterLast('/') ?: "Selected PDF" }
    val questionPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { selected -> questionUri = selected; questionName = displayName(selected); vm.clearImportResult() } }
    val answerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { selected -> answerUri = selected; answerName = displayName(selected); vm.clearImportResult() } }
    val customQuestions = vm.corpus.questions.filter { it.bookId < 0 }; val missing = AiAnswerLogic.missingAnswers(customQuestions)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { IconButton({ vm.screen = AppScreen.LIBRARY }) { Icon(Icons.Outlined.ArrowBack, "Back") }; Column { Text("Custom Source", fontSize = 25.sp, fontWeight = FontWeight.Black); Text("Private two-document import", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        item { Card(Modifier.padding(20.dp).fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.PictureAsPdf, null, tint = MaterialTheme.colorScheme.primary); Text("Add a custom source", fontSize = 21.sp, fontWeight = FontWeight.Black)
            Text("Choose the question document and, optionally, its separate key or solutions. Both are copied into private app storage. Native text is tried first; scanned pages use bundled offline OCR one page at a time.")
            Text("Questions PDF • required", fontWeight = FontWeight.Bold); OutlinedButton({ questionPicker.launch(arrayOf("application/pdf")) }) { Icon(Icons.Outlined.FileOpen, null); Text(if (questionUri == null) " Choose Questions PDF" else " Change Questions PDF") }
            if (questionUri != null) Text(questionName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(); Text("Answers / Solutions PDF • optional", fontWeight = FontWeight.Bold); Text("Supports answer keys and full rationale documents.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ answerPicker.launch(arrayOf("application/pdf")) }) { Text(if (answerUri == null) "Choose Answers / Solutions PDF" else "Change Answers / Solutions PDF") }; if (answerUri != null) TextButton({ answerUri = null; answerName = ""; vm.clearImportResult() }) { Text("Clear") } }
            if (answerUri != null) Text(answerName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (questionUri != null) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Source name (optional)") }, singleLine = true)
            when (val state = vm.customImportState) {
                is CustomImportState.Working -> { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()); Text(state.progress.message); if (state.progress.totalPages > 0) Text("Page ${state.progress.page} of ${state.progress.totalPages}", style = MaterialTheme.typography.labelMedium) }
                is CustomImportState.Success -> Column { Text("✓ ${state.source.questionCount} imported", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text("${state.source.verifiedAnswerCount} with verified answers • ${state.source.missingAnswerCount} still missing"); if (state.source.unmatchedAnswerCount > 0) Text("${state.source.unmatchedAnswerCount} solution entries skipped or unmatched") }
                is CustomImportState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error); else -> Unit
            }
            Button({ questionUri?.let { vm.importCustomSource(it, answerUri, name) } }, Modifier.fillMaxWidth(), enabled = questionUri != null && vm.customImportState !is CustomImportState.Working) { Text("Import Custom Source") }
        } } }
        item { Text("Your custom sources", Modifier.padding(20.dp, 10.dp), fontWeight = FontWeight.Black, fontSize = 20.sp) }
        if (vm.customSources.isEmpty()) item { Text("No custom sources yet.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (missing.isNotEmpty()) item { Card(Modifier.padding(20.dp).fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Unanswered imports", fontWeight = FontWeight.Black); Text("${missing.size} custom questions remain unanswered. Optional AI targets only these questions and may be wrong."); vm.aiBatchProgress?.let { p -> LinearProgressIndicator({ if (p.second == 0) 0f else p.first.toFloat() / p.second }, Modifier.fillMaxWidth()); Text("${p.first} of ${p.second}") }; if (vm.aiBatchProgress == null) OutlinedButton({ confirmAi = true }) { Text("Review AI batch") } else OutlinedButton({ vm.cancelAiBatch() }) { Text("Cancel batch") }; vm.aiMessage?.let { Text(it) } } } }
        items(vm.customSources, key = { it.id }) { source -> ListItem(headlineContent = { Text(source.name, fontWeight = FontWeight.Bold) }, supportingContent = { Column { Text("Questions: ${source.fileName}"); source.answersFileName?.let { Text("Solutions: $it") }; Text("${source.questionCount} questions • ${source.verifiedAnswerCount} answered • ${source.missingAnswerCount} missing${if (source.usedOcr) " • OCR" else ""}") } }, leadingContent = { Icon(Icons.Outlined.Description, null) }, trailingContent = { IconButton({ remove = source }) { Icon(Icons.Outlined.Delete, "Delete") } }) }
    }
    if (confirmAi) AlertDialog({ confirmAi = false }, title = { Text("Send unanswered questions?") }, text = { Text(AiAnswerLogic.batchWarning(missing.size)) }, confirmButton = { TextButton({ confirmAi = false; vm.requestAiBatch(missing) }) { Text("Send ${missing.size}") } }, dismissButton = { TextButton({ confirmAi = false }) { Text("Cancel") } })
    remove?.let { source -> AlertDialog({ remove = null }, title = { Text("Delete Custom Source?") }, text = { Text("This removes both privately copied PDFs, imported questions, and local AI answers. This cannot be undone.") }, confirmButton = { TextButton({ vm.deleteCustomSource(source); remove = null }) { Text("Delete") } }, dismissButton = { TextButton({ remove = null }) { Text("Cancel") } }) }
}

@Composable fun OpenRouterSettingsCard(vm: MainViewModel) { val context = androidx.compose.ui.platform.LocalContext.current; val settings = remember { OpenRouterSettings(context) }; var token by remember { mutableStateOf("") }; var model by remember { mutableStateOf(settings.model) }; var saved by remember { mutableStateOf(settings.hasToken()) }; Card(Modifier.padding(20.dp).fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Optional OpenRouter AI", fontSize = 20.sp, fontWeight = FontWeight.Black); Text("Medical accuracy warning: AI answers can be wrong. Verify medically. Only unanswered custom questions you explicitly submit are sent; imported PDFs and bundled content stay local.", color = MaterialTheme.colorScheme.error); OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text(if (saved) "API token •••••••• (enter to replace)" else "API token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true); OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ if (token.isNotBlank()) settings.token = token; settings.model = model; token = ""; saved = settings.hasToken() }) { Text("Save securely") }; OutlinedButton({ settings.token = ""; token = ""; saved = false }) { Text("Delete token") } }; Text("Encrypted with Android Keystore. Network calls happen only after explicit AI actions.", style = MaterialTheme.typography.bodySmall) } } }
