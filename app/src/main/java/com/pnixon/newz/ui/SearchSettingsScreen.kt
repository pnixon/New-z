@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pnixon.newz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import newz.core.SearchSettings

@Composable
private fun Line(label: String, value: String, onChange: (String) -> Unit, hint: String? = null, keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = lines == 1,
        minLines = if (lines == 1) 1 else 2,
        maxLines = lines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = if (lines == 1) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** The web search connector that topic searches use, on the user's own key. */
@Composable
fun SearchSettingsPage(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val saved by vm.search.collectAsStateWithLifecycle()
    val hasKey by vm.hasSearchKey.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()

    var draft by remember(saved) { mutableStateOf(saved) }
    var results by remember(saved) { mutableStateOf(saved.resultsPerSearch.toString()) }
    var daily by remember(saved) { mutableStateOf(saved.dailyLimit.toString()) }
    var keyInput by rememberSaveable { mutableStateOf("") }
    var testQuery by rememberSaveable { mutableStateOf("latest news") }
    var testOutput by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val edited = draft.copy(
        resultsPerSearch = (results.toIntOrNull() ?: draft.resultsPerSearch).coerceIn(1, 50),
        dailyLimit = (daily.toIntOrNull() ?: draft.dailyLimit).coerceAtLeast(0),
    )
    val dirty = edited != saved
    val searching = config.topics.filter { it.search }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "A topic with web search switched on is searched on its own schedule, and what the search finds is added to the front page. " +
                "Requests go directly from this phone to the provider below, on your key. Each search is one paid request.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            when {
                searching.isEmpty() -> "No topic has web search switched on. Turn it on for a topic under Algorithm, Topics."
                !hasKey -> "${searching.size} topics want searching, but no search key is saved, so none run."
                else -> "Searching for: " + searching.joinToString(", ") { "${it.name} (every ${it.searchEveryHours} h)" }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Sites a search turns up that you have not added appear under Sources as unrated, where you can trust, block or follow them. Results from blocked sites are dropped. Every search is recorded in the log under Models.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Provider", style = MaterialTheme.typography.titleMedium)
        Text(
            "Presets fill in the fields below. They are starting points: providers change their formats, so check the provider's documentation and use Test.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchSettings.PRESETS.forEach { (name, preset) ->
                val same = draft.endpoint == preset.endpoint && draft.method == preset.method && draft.request == preset.request
                FilterChip(
                    selected = same,
                    onClick = { draft = preset.copy(resultsPerSearch = edited.resultsPerSearch, dailyLimit = edited.dailyLimit) },
                    label = { Text(name) },
                )
            }
        }
        Line("Address", draft.endpoint, { draft = draft.copy(endpoint = it) }, keyboard = KeyboardType.Uri)
        Line("Method", draft.method, { draft = draft.copy(method = it.uppercase()) }, hint = "GET or POST")
        Line("Key header", draft.authHeader, { draft = draft.copy(authHeader = it) }, hint = "The header your key is sent in")
        Line("Text before the key", draft.authPrefix, { draft = draft.copy(authPrefix = it) }, hint = "For example \"Bearer \" with its space. May be blank")
        Line(
            if (draft.method.equals("GET", ignoreCase = true)) "Query string" else "JSON body",
            draft.request, { draft = draft.copy(request = it) },
            hint = "{query} and {limit} are filled in", lines = 5,
        )
        Line("Results per search", results, { results = it.filter(Char::isDigit) }, keyboard = KeyboardType.Number)
        Line("Searches per day at most", daily, { daily = it.filter(Char::isDigit) }, hint = "0 means no limit", keyboard = KeyboardType.Number)
        if (dirty) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveSearch(edited); vm.say("Search settings saved") }) { Text("Save changes") }
                OutlinedButton(onClick = { draft = saved.copy(); results = saved.resultsPerSearch.toString(); daily = saved.dailyLimit.toString() }) { Text("Discard") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Key", style = MaterialTheme.typography.titleMedium)
        Text(
            if (hasKey) "A search key is saved on this phone, encrypted. It is never exported or backed up." else "No search key saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (hasKey) "Replace search key" else "Search key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = keyInput.isNotBlank(), onClick = { vm.setSearchKey(keyInput); keyInput = "" }) { Text("Save key") }
            if (hasKey) OutlinedButton(onClick = { vm.setSearchKey(null) }) { Text("Remove key") }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Try it", style = MaterialTheme.typography.titleMedium)
        Line("Test search", testQuery, { testQuery = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && !dirty && testQuery.isNotBlank(),
                onClick = {
                    busy = true
                    testOutput = "Searching…"
                    scope.launch {
                        val outcome = vm.testSearch(testQuery.trim())
                        testOutput = buildString {
                            append(outcome.error?.let { "Failed: $it" } ?: "${outcome.results.size} results understood.")
                            outcome.results.take(5).forEach { append("\n• ${it.title}\n  ${it.url}") }
                            if (outcome.raw.isNotBlank()) append("\n\nRaw reply (start):\n${outcome.raw.take(1200)}")
                        }
                        busy = false
                    }
                },
            ) { Text("Test (one paid search)") }
            OutlinedButton(enabled = !dirty && hasKey && searching.isNotEmpty(), onClick = { vm.runSearchesNow() }) {
                Text("Run all ${searching.size} topic searches now")
            }
        }
        if (dirty) {
            Text("Save your changes to use these buttons.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        testOutput?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(32.dp))
    }
}
