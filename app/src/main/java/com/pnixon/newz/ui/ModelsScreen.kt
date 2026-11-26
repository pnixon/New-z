@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pnixon.newz.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnixon.newz.data.ModelCallEntity
import kotlinx.coroutines.launch
import newz.core.ModelSettings
import newz.core.OpenAiCompatClient
import newz.core.Prompts

@Composable
private fun Heading(title: String, note: String? = null) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (note != null) {
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun TextSetting(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    lines: Int = 1,
    error: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        isError = error,
        singleLine = lines == 1,
        minLines = if (lines == 1) 1 else 3,
        maxLines = lines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = if (lines == 1) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Remote model set-up. Every request goes straight to the provider with the user's own key. */
@Composable
fun ModelsPage(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val saved by vm.models.collectAsStateWithLifecycle()
    val hasKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val calls by vm.modelCalls.collectAsStateWithLifecycle()

    var draft by remember(saved) { mutableStateOf(saved) }
    var temperature by remember(saved) { mutableStateOf(saved.temperature.toString()) }
    var maxTokens by remember(saved) { mutableStateOf(saved.maxTokens.toString()) }
    var attempts by remember(saved) { mutableStateOf(saved.maxAttempts.toString()) }
    var htmlBudget by remember(saved) { mutableStateOf(saved.htmlBudgetChars.toString()) }
    var repairHours by remember(saved) { mutableStateOf(saved.repairIntervalHours.toString()) }
    var maxClaims by remember(saved) { mutableStateOf(saved.maxClaims.toString()) }

    var keyInput by rememberSaveable { mutableStateOf("") }
    var testResult by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var modelList by remember { mutableStateOf<List<String>?>(null) }
    var pickingFallback by remember { mutableStateOf(false) }
    var openCall by remember { mutableStateOf<Long?>(null) }

    val extraValid = remember(draft.extraBodyJson) {
        runCatching { OpenAiCompatClient.parseExtra(draft.extraBodyJson) }.isSuccess
    }
    val edited = draft.copy(
        temperature = temperature.toDoubleOrNull() ?: draft.temperature,
        maxTokens = (maxTokens.toIntOrNull() ?: draft.maxTokens).coerceIn(50, 16_000),
        maxAttempts = (attempts.toIntOrNull() ?: draft.maxAttempts).coerceIn(1, 6),
        htmlBudgetChars = (htmlBudget.toIntOrNull() ?: draft.htmlBudgetChars).coerceIn(2_000, 400_000),
        repairIntervalHours = (repairHours.toIntOrNull() ?: draft.repairIntervalHours).coerceIn(1, 24 * 30),
        maxClaims = (maxClaims.toIntOrNull() ?: draft.maxClaims).coerceIn(1, 8),
    )
    val dirty = edited != saved

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Remote models are used to write a scraping recipe when the built-in layout detection fails, to repair a scraped source that has stopped working, and to run a story check when you ask for one. " +
                "Every request goes directly from this phone to the provider below, on your key. Nothing else is in between.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (saved.enabled) "Remote models are on, using ${saved.model}." else "Remote models are off until you choose a model.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (dirty) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = extraValid, onClick = { vm.saveModels(edited); vm.say("Model settings saved") }) { Text("Save changes") }
                OutlinedButton(onClick = { draft = saved.copy() ; temperature = saved.temperature.toString(); maxTokens = saved.maxTokens.toString(); attempts = saved.maxAttempts.toString(); htmlBudget = saved.htmlBudgetChars.toString(); repairHours = saved.repairIntervalHours.toString(); maxClaims = saved.maxClaims.toString() }) { Text("Discard") }
            }
        }

        Heading("Provider")
        TextSetting("Address", draft.baseUrl, { draft = draft.copy(baseUrl = it) }, hint = "Any OpenAI-compatible endpoint", keyboard = KeyboardType.Uri)
        Text(
            if (hasKey) "An API key is saved on this phone, encrypted. It is never exported or backed up." else "No API key saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (hasKey) "Replace API key" else "API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = keyInput.isNotBlank(), onClick = { vm.setApiKey(keyInput); keyInput = "" }) { Text("Save key") }
            if (hasKey) OutlinedButton(onClick = { vm.setApiKey(null) }) { Text("Remove key") }
        }

        Heading("Models", "IDs exactly as the provider lists them.")
        TextSetting("Model", draft.model, { draft = draft.copy(model = it) }, hint = "Blank turns remote models off")
        TextSetting("Fallback model", draft.fallbackModel, { draft = draft.copy(fallbackModel = it) }, hint = "Used for the last attempt. Blank: keep using the first model")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "Pick model from list", true to "Pick fallback from list").forEach { (fallback, label) ->
                OutlinedButton(
                    enabled = !busy && !dirty,
                    onClick = {
                        busy = true
                        pickingFallback = fallback
                        scope.launch {
                            vm.listModels().fold(
                                onSuccess = { modelList = it },
                                onFailure = { testResult = "Could not load the model list: ${it.message}" },
                            )
                            busy = false
                        }
                    },
                ) { Text(label) }
            }
            OutlinedButton(
                enabled = !busy && !dirty && saved.enabled,
                onClick = {
                    busy = true
                    testResult = "Testing…"
                    scope.launch {
                        testResult = vm.testConnection()
                        busy = false
                    }
                },
            ) { Text("Test") }
        }
        if (dirty) {
            Text("Save your changes to use these buttons.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }

        Heading("Request")
        TextSetting("Temperature", temperature, { temperature = it }, error = temperature.toDoubleOrNull() == null)
        TextSetting("Maximum reply tokens", maxTokens, { maxTokens = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number)
        TextSetting("Attempts per recipe", attempts, { attempts = it.filter(Char::isDigit) }, hint = "1 to 6. Each failed answer is sent back with the reason", keyboard = KeyboardType.Number)
        TextSetting("Page size limit (characters)", htmlBudget, { htmlBudget = it.filter(Char::isDigit) }, hint = "How much simplified page HTML is sent", keyboard = KeyboardType.Number)
        TextSetting(
            "Extra request fields (JSON object)", draft.extraBodyJson, { draft = draft.copy(extraBodyJson = it) },
            hint = if (extraValid) "Merged into every request body. Your fields override the app's" else "Not a valid JSON object",
            lines = 6, error = !extraValid,
        )

        Heading("Prompts", "Sent exactly as written, with the placeholders filled in.")
        TextSetting("System prompt", draft.systemPrompt, { draft = draft.copy(systemPrompt = it) }, lines = 8)
        TextSetting("New site prompt", draft.addPrompt, { draft = draft.copy(addPrompt = it) }, hint = "Placeholders: {url} {html}", lines = 12)
        TextSetting("Repair prompt", draft.repairPrompt, { draft = draft.copy(repairPrompt = it) }, hint = "Placeholders: {url} {html} {old_recipe} {old_titles}", lines = 12)
        TextSetting("Story check: pick claims", draft.claimsPrompt, { draft = draft.copy(claimsPrompt = it) }, hint = "Placeholders: {max} {title} {text}", lines = 12)
        TextSetting("Story check: compare coverage", draft.comparePrompt, { draft = draft.copy(comparePrompt = it) }, hint = "Placeholder: {claims}", lines = 12)
        TextSetting("Claims per story check", maxClaims, { maxClaims = it.filter(Char::isDigit) }, hint = "1 to 8. Each claim costs one web search", keyboard = KeyboardType.Number)
        OutlinedButton(onClick = {
            draft = draft.copy(
                systemPrompt = Prompts.SYSTEM, addPrompt = Prompts.ADD, repairPrompt = Prompts.REPAIR,
                claimsPrompt = Prompts.CLAIMS, comparePrompt = Prompts.COMPARE,
            )
        }) { Text("Reset prompts to default") }

        Heading("Automatic repair")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Repair broken scraped sources", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "After three failed checks, ask the model for a new recipe. One that passes the checks is applied, and the old one is kept in the source's history to restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = draft.autoRepair, onCheckedChange = { draft = draft.copy(autoRepair = it) })
        }
        TextSetting("Hours between repair attempts per source", repairHours, { repairHours = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number)

        Heading("Model log", "Every request the app has made, newest first. Tap one to read exactly what was sent and what came back.")
        if (calls.isEmpty()) {
            Text("No requests yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            calls.take(50).forEach { call ->
                Column(Modifier.fillMaxWidth().clickable { openCall = call.id }.padding(vertical = 6.dp)) {
                    Text("${call.task} · ${call.model} · ${ago(call.at)}", style = MaterialTheme.typography.bodyMedium)
                    Text(call.target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        call.outcome,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (call.accepted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
            }
            OutlinedButton(onClick = { vm.clearModelCalls() }) { Text("Clear log") }
        }
        Spacer(Modifier.height(32.dp))
    }

    modelList?.let { list ->
        ModelPicker(
            models = list,
            onDismiss = { modelList = null },
            onPick = { id ->
                val next = if (pickingFallback) saved.copy(fallbackModel = id) else saved.copy(model = id)
                vm.saveModels(next)
                modelList = null
            },
        )
    }

    openCall?.let { id -> ModelCallDialog(vm = vm, id = id, onDismiss = { openCall = null }) }
}

@Composable
private fun ModelPicker(models: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var filter by remember { mutableStateOf("") }
    val shown = remember(models, filter) { models.filter { it.contains(filter.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${models.size} models") },
        text = {
            Column {
                OutlinedTextField(filter, { filter = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown) { id ->
                        Text(id, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val SHOWN_CHARS = 4_000

@Composable
private fun LongText(label: String, text: String) {
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    Text(
        if (text.length > SHOWN_CHARS) text.take(SHOWN_CHARS) + "\n… ${text.length - SHOWN_CHARS} more characters; copy to see all" else text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun ModelCallDialog(vm: MainViewModel, id: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var call by remember(id) { mutableStateOf<ModelCallEntity?>(null) }
    LaunchedEffect(id) { call = vm.modelCall(id) }
    val loaded = call

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(loaded?.let { "${it.task} · ${it.model}" } ?: "Loading…") },
        text = {
            if (loaded != null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Sent to ${loaded.endpoint} ${ago(loaded.at)} about ${loaded.target}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Took ${loaded.durationMs} ms" +
                            (loaded.promptTokens?.let { " · $it tokens in" } ?: "") +
                            (loaded.completionTokens?.let { " · $it tokens out" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        loaded.outcome,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (loaded.accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    LongText("Reply", loaded.response ?: "(no reply)")
                    LongText("System prompt", loaded.systemPrompt)
                    LongText("Prompt", loaded.userPrompt)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (loaded != null) {
                TextButton(onClick = {
                    val all = "SYSTEM PROMPT\n${loaded.systemPrompt}\n\nPROMPT\n${loaded.userPrompt}\n\nREPLY\n${loaded.response.orEmpty()}\n\nOUTCOME\n${loaded.outcome}"
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val ok = runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Model request", all)) }.isSuccess
                    vm.say(if (ok) "Copied" else "Too large to copy")
                }) { Text("Copy all") }
            }
        },
    )
}
