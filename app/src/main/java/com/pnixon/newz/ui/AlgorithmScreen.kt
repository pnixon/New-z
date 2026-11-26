@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pnixon.newz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import newz.core.Ranker
import newz.core.RankingConfig
import newz.core.Rule
import newz.core.RuleAction
import newz.core.RuleField
import newz.core.TrustLevel
import kotlin.math.roundToInt

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

/** A labelled slider that applies its value when the finger lifts. */
@Composable
private fun NumberSetting(
    label: String,
    explanation: String,
    value: Double,
    min: Double,
    max: Double,
    whole: Boolean = false,
    onChange: (Double) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    fun rounded(v: Float): Double = if (whole) v.roundToInt().toDouble() else (v * 20).roundToInt() / 20.0
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (whole) rounded(local).toInt().toString() else Ranker.fmt(rounded(local)),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = local.coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { local = it },
            valueRange = min.toFloat()..max.toFloat(),
            onValueChangeFinished = { onChange(rounded(local)) },
        )
    }
}

@Composable
private fun SwitchSetting(label: String, explanation: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun describe(rule: Rule): String {
    val where = if (rule.field == RuleField.ANY) "title, source or address" else rule.field.name.lowercase()
    val effect = when (rule.action) {
        RuleAction.HIDE -> "hide the article"
        RuleAction.ADD_POINTS -> "add ${Ranker.fmt(rule.points)} to its score"
    }
    return "If the $where contains \"${rule.contains}\", $effect"
}

/** Every number and rule behind the front page order, all editable. */
@Composable
fun AlgorithmPage(vm: MainViewModel, config: RankingConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addingRule by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf("") }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray()) }
            }.isSuccess
            vm.say(if (ok) "Configuration saved" else "Could not write the file")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text == null) vm.say("Could not read the file") else vm.importConfig(text)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "The front page is sorted by score, highest first. This is the whole calculation; nothing else affects the order.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Text(
                "score =\n" +
                    "  ${Ranker.fmt(config.recencyWeight)} × 0.5^(age in hours ÷ ${Ranker.fmt(config.recencyHalfLifeHours)})\n" +
                    "+ ${Ranker.fmt(config.trustWeight)} × trust value of the source\n" +
                    "+ ${Ranker.fmt(config.coverageWeight)} × min(other sources, ${config.coverageSaturation}) ÷ ${config.coverageSaturation}\n" +
                    "+ ${Ranker.fmt(config.readWeight)} × (1 if already read)\n" +
                    "+ points from each of your rules that match",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Ties go to the newer article. Tap the ⓘ on any article to see these numbers for it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Presets")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.updateConfig { RankingConfig.DEFAULT.copy(rules = it.rules) } }) { Text("Default") }
            OutlinedButton(onClick = { vm.updateConfig { RankingConfig.CHRONOLOGICAL.copy(rules = it.rules) } }) { Text("Newest first only") }
        }
        Text(
            "Presets reset the numbers below and keep your rules.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("Weights")
        NumberSetting("Recency weight", "How much a fresh article gains over an old one.", config.recencyWeight, 0.0, 3.0) { v ->
            vm.updateConfig { it.copy(recencyWeight = v) }
        }
        NumberSetting("Recency half-life (hours)", "An article's recency value halves each time this many hours pass.", config.recencyHalfLifeHours, 1.0, 72.0, whole = true) { v ->
            vm.updateConfig { it.copy(recencyHalfLifeHours = v) }
        }
        NumberSetting("Source trust weight", "How much your trust in the source counts.", config.trustWeight, 0.0, 3.0) { v ->
            vm.updateConfig { it.copy(trustWeight = v) }
        }
        NumberSetting("Coverage weight", "How much a story gains when several of your sources report it.", config.coverageWeight, 0.0, 3.0) { v ->
            vm.updateConfig { it.copy(coverageWeight = v) }
        }
        NumberSetting("Coverage ceiling (sources)", "Other sources beyond this number add nothing more.", config.coverageSaturation.toDouble(), 1.0, 10.0, whole = true) { v ->
            vm.updateConfig { it.copy(coverageSaturation = v.toInt()) }
        }
        NumberSetting("Already-read weight", "Added to articles you have opened. Negative moves them down.", config.readWeight, -3.0, 0.0) { v ->
            vm.updateConfig { it.copy(readWeight = v) }
        }

        Section("Trust values")
        Text(
            "The number each trust level stands for in the formula. You set each source's level; the app never changes it. Blocked sources are hidden, not scored.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(TrustLevel.TRUSTED, TrustLevel.NEUTRAL, TrustLevel.UNRATED, TrustLevel.LOW).forEach { level ->
            NumberSetting(level.name.lowercase().replaceFirstChar { it.uppercase() }, "", config.trustValues[level] ?: 0.0, 0.0, 1.0) { v ->
                vm.updateConfig { it.copy(trustValues = it.trustValues + (level to v)) }
            }
        }

        Section("What is held back")
        Text(
            "Anything held back is listed on the Filtered tab with its reason.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NumberSetting("Hide articles older than (days)", "", config.maxAgeDays.toDouble(), 1.0, 30.0, whole = true) { v ->
            vm.updateConfig { it.copy(maxAgeDays = v.toInt()) }
        }
        SwitchSetting(
            "One article per story",
            "When several sources carry the same story, show the highest scoring and list the others under it.",
            config.collapseDuplicates,
        ) { on -> vm.updateConfig { it.copy(collapseDuplicates = on) } }
        NumberSetting(
            "Same-story threshold",
            "Share of headline words two articles must have in common to count as one story. Higher is stricter.",
            config.duplicateSimilarity, 0.3, 1.0,
        ) { v -> vm.updateConfig { it.copy(duplicateSimilarity = v) } }
        SwitchSetting(
            "Score cut-off",
            "Hide articles scoring below a minimum.",
            config.minScore != null,
        ) { on -> vm.updateConfig { it.copy(minScore = if (on) 0.25 else null) } }
        config.minScore?.let { min ->
            NumberSetting("Minimum score", "", min, -1.0, 3.0) { v -> vm.updateConfig { it.copy(minScore = v) } }
        }

        Section("Your rules")
        if (config.rules.isEmpty()) {
            Text("No rules yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        config.rules.forEachIndexed { index, rule ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                    Text(describe(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { on ->
                        vm.updateConfig { c -> c.copy(rules = c.rules.mapIndexed { i, r -> if (i == index) r.copy(enabled = on) else r }) }
                    },
                )
                IconButton(onClick = { vm.updateConfig { c -> c.copy(rules = c.rules.filterIndexed { i, _ -> i != index }) } }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete rule")
                }
            }
        }
        OutlinedButton(onClick = { addingRule = true }) { Text("Add rule") }

        Section("Configuration file")
        Text(
            "One readable JSON file holds these numbers, your rules, and every source with its recipe and trust level. Importing replaces the numbers and rules, and updates or adds the sources in the file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    pendingExport = vm.exportConfig()
                    exporter.launch("newz-config.json")
                }
            }) { Text("Export") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (addingRule) {
        RuleDialog(
            onDismiss = { addingRule = false },
            onAdd = { rule ->
                vm.updateConfig { it.copy(rules = it.rules + rule) }
                addingRule = false
            },
        )
    }
}

@Composable
private fun RuleDialog(onDismiss: () -> Unit, onAdd: (Rule) -> Unit) {
    var name by remember { mutableStateOf("") }
    var contains by remember { mutableStateOf("") }
    var field by remember { mutableStateOf(RuleField.TITLE) }
    var action by remember { mutableStateOf(RuleAction.ADD_POINTS) }
    var points by remember { mutableStateOf("1.0") }
    val pointsValue = points.toDoubleOrNull()
    val valid = contains.isNotBlank() && (action == RuleAction.HIDE || pointsValue != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Look in", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleField.entries.forEach { f ->
                        FilterChip(selected = field == f, onClick = { field = f }, label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                OutlinedTextField(contains, { contains = it }, label = { Text("Contains (any case)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("Then", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = action == RuleAction.ADD_POINTS, onClick = { action = RuleAction.ADD_POINTS }, label = { Text("Change score") })
                    FilterChip(selected = action == RuleAction.HIDE, onClick = { action = RuleAction.HIDE }, label = { Text("Hide") })
                }
                if (action == RuleAction.ADD_POINTS) {
                    OutlinedTextField(
                        points, { points = it },
                        label = { Text("Points to add") },
                        supportingText = { Text("Negative moves matching articles down") },
                        isError = pointsValue == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onAdd(
                        Rule(
                            name = name.trim().ifEmpty { contains.trim() },
                            field = field,
                            contains = contains.trim(),
                            action = action,
                            points = pointsValue ?: 0.0,
                        )
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
