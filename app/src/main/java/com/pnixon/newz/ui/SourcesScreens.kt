@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.pnixon.newz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnixon.newz.data.BROKEN_AFTER
import com.pnixon.newz.data.SourceEntity
import com.pnixon.newz.data.isBroken
import com.pnixon.newz.data.recipe
import com.pnixon.newz.data.toConfig
import com.pnixon.newz.data.trustLevel
import kotlinx.coroutines.launch
import newz.core.FetchKind
import newz.core.RawItem
import newz.core.Recipe
import newz.core.SourceConfig
import newz.core.TrustLevel

@Composable
fun SourcesPage(sources: List<SourceEntity>, onEdit: (Long) -> Unit) {
    if (sources.isEmpty()) {
        EmptyMessage("No sources yet. Add one with the button below.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(sources, key = { it.id }) { source ->
            val trust = source.trustLevel()
            Column(Modifier.fillMaxWidth().clickable { onEdit(source.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(
                        trust.name.lowercase().replaceFirstChar { it.uppercase() },
                        if (source.recipe().kind == FetchKind.FEED) "feed" else "scraped page",
                        "every ${source.refreshMinutes} min",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val status = when {
                    trust == TrustLevel.BLOCKED -> "Blocked: not fetched, and its articles are hidden"
                    !source.enabled -> "Paused"
                    source.lastFetchAt == null -> "Not fetched yet"
                    source.isBroken() -> "Broken: ${source.failCount} checks in a row failed. ${source.lastStatus.orEmpty()}"
                    else -> "${source.lastStatus.orEmpty()} · ${ago(source.lastFetchAt ?: 0L)}"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (source.isBroken() || !source.lastOk) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Preview(items: List<RawItem>) {
    Text("${items.size} articles found", style = MaterialTheme.typography.titleSmall)
    items.take(8).forEach { item ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    item.publishedAt?.let { ago(it) } ?: "no date",
                    if (item.imageUrl != null) "has image" else "no image",
                    item.url,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
    if (items.size > 8) {
        Text("and ${items.size - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Add a source (sourceId null) or edit one. Every field of the recipe is shown and editable. */
@Composable
fun SourceEditScreen(vm: MainViewModel, sourceId: Long?, snackbar: SnackbarHostState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(sourceId == null) }
    var entity by remember { mutableStateOf<SourceEntity?>(null) }

    var siteUrl by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<RawItem>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    var name by rememberSaveable { mutableStateOf("") }
    var homeUrl by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(FetchKind.FEED) }
    var url by rememberSaveable { mutableStateOf("") }
    var item by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var image by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }
    var exclude by rememberSaveable { mutableStateOf("") }
    var trust by rememberSaveable { mutableStateOf(TrustLevel.NEUTRAL) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var refreshMinutes by rememberSaveable { mutableStateOf("60") }
    var userAgent by rememberSaveable { mutableStateOf("") }

    fun fill(config: SourceConfig) {
        name = config.name
        homeUrl = config.homeUrl
        kind = config.recipe.kind
        url = config.recipe.url
        item = config.recipe.item
        title = config.recipe.title
        link = config.recipe.link
        summary = config.recipe.summary
        image = config.recipe.image
        date = config.recipe.date
        exclude = config.recipe.exclude
        trust = config.trust
        enabled = config.enabled
        refreshMinutes = config.refreshMinutes.toString()
        userAgent = config.userAgent.orEmpty()
    }

    LaunchedEffect(sourceId) {
        if (sourceId != null) {
            val found = vm.source(sourceId)
            entity = found
            if (found == null) {
                onClose()
            } else if (!loaded) {
                fill(found.toConfig())
                loaded = true
            }
        }
    }

    fun current(): SourceConfig = SourceConfig(
        name = name.trim(),
        homeUrl = homeUrl.trim().ifEmpty { url.trim() },
        recipe = Recipe(
            kind = kind,
            url = url.trim(),
            item = item.trim(),
            title = title.trim(),
            link = link.trim(),
            summary = summary.trim(),
            image = image.trim(),
            date = date.trim(),
            exclude = exclude.trim(),
        ),
        trust = trust,
        enabled = enabled,
        refreshMinutes = (refreshMinutes.toIntOrNull() ?: 60).coerceIn(15, 24 * 60),
        userAgent = userAgent.trim().ifEmpty { null },
        headers = entity?.toConfig()?.headers ?: emptyMap(),
    )

    fun problem(): String? = when {
        name.isBlank() -> "Give the source a name"
        url.isBlank() -> "Enter the address to fetch"
        kind == FetchKind.SELECTORS && item.isBlank() -> "A scraped page needs an item selector"
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (sourceId == null) "Add source" else "Edit source") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sourceId != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete source")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (sourceId == null) {
                Text(
                    "Enter a site's address. The app looks for a feed first, then tries to find the article list from the page layout.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Field("Site address", siteUrl, { siteUrl = it }, keyboard = KeyboardType.Uri)
                Button(
                    enabled = !busy && siteUrl.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        preview = null
                        scope.launch {
                            vm.discover(siteUrl).fold(
                                onSuccess = { found ->
                                    fill(found.source)
                                    method = found.method
                                    preview = found.preview
                                },
                                onFailure = {
                                    method = null
                                    error = it.message ?: "Detection failed"
                                    if (url.isBlank()) url = siteUrl.trim()
                                },
                            )
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Detecting…" else "Detect") }
                method?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
            } else {
                entity?.let { e ->
                    val broken = e.isBroken()
                    Text(
                        when {
                            e.lastFetchAt == null -> "Not fetched yet"
                            broken -> "Broken: $BROKEN_AFTER or more checks in a row failed. Last result: ${e.lastStatus.orEmpty()}"
                            else -> "Last check ${ago(e.lastFetchAt ?: 0L)}: ${e.lastStatus.orEmpty()}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (broken || !e.lastOk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Field("Name", name, { name = it })
            Field("Home page", homeUrl, { homeUrl = it }, keyboard = KeyboardType.Uri)

            Spacer(Modifier.height(8.dp))
            Text("How it is fetched", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = kind == FetchKind.FEED, onClick = { kind = FetchKind.FEED }, label = { Text("Feed (RSS or Atom)") })
                FilterChip(selected = kind == FetchKind.SELECTORS, onClick = { kind = FetchKind.SELECTORS }, label = { Text("Scraped page") })
            }
            Field(
                if (kind == FetchKind.FEED) "Feed address" else "Listing page address",
                url, { url = it }, keyboard = KeyboardType.Uri,
            )

            if (kind == FetchKind.SELECTORS) {
                Text(
                    "CSS selectors. Item marks one block per article; the rest are looked up inside each block.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field("Item", item, { item = it }, hint = "Required. Example: article.story-card")
                Field("Title", title, { title = it }, hint = "Blank: the block's first heading, else its longest link")
                Field("Link", link, { link = it }, hint = "Blank: the title's link, else the block's first link")
                Field("Summary", summary, { summary = it }, hint = "Blank: no summary")
                Field("Image", image, { image = it }, hint = "Blank: the block's first image")
                Field("Date", date, { date = it }, hint = "Blank: the time the app first saw the article")
                Field("Exclude", exclude, { exclude = it }, hint = "Removed from the page first. Example: .sponsored")
            }

            Spacer(Modifier.height(8.dp))
            Text("Your trust in this source", style = MaterialTheme.typography.titleSmall)
            TrustChips(selected = trust, onSelect = { trust = it })

            Field("Check every (minutes)", refreshMinutes, { refreshMinutes = it.filter(Char::isDigit) }, hint = "15 to 1440", keyboard = KeyboardType.Number)
            Field("User agent", userAgent, { userAgent = it }, hint = "Blank: the app's default")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Spacer(Modifier.padding(start = 12.dp))
                Text(if (enabled) "Fetched on schedule" else "Paused")
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val issue = problem()
                        if (issue != null) {
                            error = issue
                        } else {
                            busy = true
                            error = null
                            scope.launch {
                                val result = vm.test(current())
                                preview = result.items
                                error = result.error
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Working…" else "Test") }
                Button(
                    enabled = !busy,
                    onClick = {
                        val issue = problem()
                        if (issue != null) error = issue else vm.saveSource(sourceId, current(), onClose)
                    },
                ) { Text("Save") }
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            preview?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(12.dp))
                Preview(it)
            }

            if (sourceId != null) {
                val log by remember(sourceId) { vm.logFor(sourceId) }.collectAsStateWithLifecycle(emptyList())
                if (log.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Recent checks", style = MaterialTheme.typography.titleSmall)
                    log.forEach { entry ->
                        Text(
                            "${ago(entry.at)}: ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && sourceId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $name?") },
            text = { Text("The source and its stored articles are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteSource(sourceId, onClose)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
