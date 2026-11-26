@file:OptIn(ExperimentalMaterial3Api::class)

package com.pnixon.newz.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NewzTheme { NewzRoot() }
        }
    }
}

@Composable
fun NewzTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    FRONT("Front", "New-z", Icons.Filled.Home),
    FILTERED("Filtered", "Filtered out", Icons.Filled.Clear),
    SOURCES("Sources", "Sources", Icons.Filled.List),
    ALGORITHM("Algorithm", "Algorithm", Icons.Filled.Settings),
    MODELS("Models", "Models", Icons.Filled.Build),
}

/** Marks "add a new source" in the editor's saved state. */
private const val NEW_SOURCE = -1L

@Composable
fun NewzRoot(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.FRONT) }
    var editingSource by rememberSaveable { mutableStateOf<Long?>(null) }
    var whyArticle by rememberSaveable { mutableStateOf<Long?>(null) }
    // The front page's topic and tag filter: selected keys, one per line.
    var frontFilter by rememberSaveable { mutableStateOf("") }

    val front by vm.front.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            vm.tick()
        }
    }

    val editing = editingSource
    if (editing != null) {
        BackHandler { editingSource = null }
        SourceEditScreen(
            vm = vm,
            sourceId = editing.takeIf { it != NEW_SOURCE },
            snackbar = snackbar,
            onClose = { editingSource = null },
        )
        return
    }

    BackHandler(enabled = tab != Tab.FRONT) { tab = Tab.FRONT }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.title) },
                actions = {
                    if (tab == Tab.FRONT || tab == Tab.SOURCES) {
                        IconButton(onClick = { vm.refresh() }, enabled = !refreshing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh all sources")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.SOURCES) {
                ExtendedFloatingActionButton(
                    onClick = { editingSource = NEW_SOURCE },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add source") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.FRONT -> FrontPage(
                    state = front,
                    refreshing = refreshing,
                    onRefresh = { vm.refresh() },
                    filter = frontFilter,
                    onFilter = { frontFilter = it },
                    onOpen = { scored ->
                        vm.setRead(scored.item.id, true)
                    },
                    onWhy = { whyArticle = it.item.id },
                )
                Tab.FILTERED -> FilteredPage(state = front, onWhy = { whyArticle = it.item.id })
                Tab.SOURCES -> SourcesPage(sources = sources, onEdit = { editingSource = it })
                Tab.ALGORITHM -> AlgorithmPage(vm = vm, config = config)
                Tab.MODELS -> ModelsPage(vm = vm)
            }
        }
    }

    val whyId = whyArticle
    if (whyId != null) {
        val scored = front.result.visible.firstOrNull { it.item.id == whyId }
            ?: front.result.hidden.firstOrNull { it.item.id == whyId }
        if (scored == null) {
            LaunchedEffect(whyId) { whyArticle = null }
        } else {
            WhySheet(
                scored = scored,
                article = front.articles[whyId],
                onDismiss = { whyArticle = null },
                onTrust = { vm.setTrust(scored.item.sourceId, it) },
                onRead = { vm.setRead(scored.item.id, it) },
            )
        }
    }
}
