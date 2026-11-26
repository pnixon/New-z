@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pnixon.newz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import newz.core.BrowserSettings
import newz.core.FilterList

@Composable
private fun Toggle(label: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Part(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

/** The in-app browser's settings: what is blocked, by which lists, and where it is switched off. */
@Composable
fun BrowserSettingsPage(vm: MainViewModel) {
    val settings by vm.browser.collectAsStateWithLifecycle()
    val blocker by vm.blocker.collectAsStateWithLifecycle()
    val updating by vm.updatingFilters.collectAsStateWithLifecycle()
    val revision by vm.filterRevision.collectAsStateWithLifecycle()

    var customRules by remember(settings.customRules) { mutableStateOf(settings.customRules) }
    var updateDays by remember(settings.updateDays) { mutableStateOf(settings.updateDays.toString()) }
    var addingList by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Toggle(
            "Open articles in the app",
            "Uses the phone's built-in web engine, with the blocking below. Off opens your usual browser, where this app cannot block anything.",
            settings.openInApp,
        ) { vm.saveBrowser(settings.copy(openInApp = it)) }
        Toggle(
            "Block ad and tracker requests",
            "Requests to hosts on the lists below are not made. The browser's \"blocked\" button lists each one with the rule that stopped it.",
            settings.blockRequests,
        ) { vm.saveBrowser(settings.copy(blockRequests = it)) }
        Toggle(
            "Hide leftover ad elements",
            "Applies the lists' element rules to remove empty ad boxes and banners from the page.",
            settings.hideElements,
        ) { vm.saveBrowser(settings.copy(hideElements = it)) }

        Part("What is loaded")
        Text(
            "${blocker.requestRuleCount} request rules and ${blocker.elementRuleCount} element rules are active. " +
                "${blocker.skippedRules} rules in the lists use syntax this app does not support (wildcards, per-site options, scripts) and are skipped, " +
                "so blocking is less complete than a full browser extension.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Part("Filter lists")
        // Reading revision makes the rows refresh after a download.
        val shownRevision = revision
        settings.filterLists.forEachIndexed { index, list ->
            val state = remember(list, shownRevision) { vm.filterState(list) }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(list.name, style = MaterialTheme.typography.bodyLarge)
                    Text(list.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    Text(
                        state.error?.let { "Last download failed: $it" }
                            ?: state.downloadedAt?.let { "Downloaded ${ago(it)}" }
                            ?: "Not downloaded yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = list.enabled,
                    onCheckedChange = { on ->
                        vm.saveBrowser(settings.copy(filterLists = settings.filterLists.mapIndexed { i, l -> if (i == index) l.copy(enabled = on) else l }))
                    },
                )
                IconButton(onClick = { vm.saveBrowser(settings.copy(filterLists = settings.filterLists.filterIndexed { i, _ -> i != index })) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove list")
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !updating, onClick = { vm.updateFilters(force = true) }) { Text(if (updating) "Downloading…" else "Download lists now") }
            OutlinedButton(onClick = { addingList = true }) { Text("Add list") }
            OutlinedButton(onClick = { vm.saveBrowser(settings.copy(filterLists = BrowserSettings.DEFAULT_LISTS)) }) { Text("Reset lists") }
        }
        OutlinedTextField(
            value = updateDays,
            onValueChange = { text ->
                updateDays = text.filter(Char::isDigit)
                updateDays.toIntOrNull()?.let { days -> if (days in 1..90 && days != settings.updateDays) vm.saveBrowser(settings.copy(updateDays = days)) }
            },
            label = { Text("Download again every (days)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Part("Your own rules")
        Text(
            "One per line, same syntax as the lists. Examples: ||ads.example.com^ blocks a host; ##.newsletter-popup hides an element everywhere; example.com##.banner hides it on one site.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = customRules,
            onValueChange = { customRules = it },
            minLines = 4,
            maxLines = 12,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        if (customRules != settings.customRules) {
            Button(onClick = { vm.saveBrowser(settings.copy(customRules = customRules)) }) { Text("Save rules") }
        }

        Part("Sites with blocking off")
        if (settings.allowedSites.isEmpty()) {
            Text(
                "None. Switch a site off from the browser's \"blocked\" button when it will not work with blocking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        settings.allowedSites.forEach { site ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(site, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.saveBrowser(settings.copy(allowedSites = settings.allowedSites - site)) }) { Text("Block again") }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (addingList) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingList = false },
            title = { Text("Add filter list") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        url, { url = it },
                        label = { Text("Address") },
                        supportingText = { Text("An Adblock-format list or a hosts file") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = url.trim().startsWith("https://"),
                    onClick = {
                        val added = FilterList(name.trim().ifEmpty { url.trim() }, url.trim())
                        vm.saveBrowser(settings.copy(filterLists = settings.filterLists.filter { it.url != added.url } + added))
                        addingList = false
                        vm.updateFilters(force = false)
                    },
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingList = false }) { Text("Cancel") } },
        )
    }
}
