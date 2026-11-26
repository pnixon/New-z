@file:OptIn(ExperimentalMaterial3Api::class)

package com.pnixon.newz.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import newz.core.AdBlocker
import newz.core.BrowserSettings
import newz.core.Reader
import newz.core.Urls
import java.io.ByteArrayInputStream

/** One request the blocker stopped, with the rule responsible. */
data class BlockedRequest(val host: String, val rule: String, val list: String)

/** What the WebView's network thread reads. Written from the main thread only. */
private class BrowserLive {
    @Volatile var page: String? = null
    @Volatile var settings: BrowserSettings = BrowserSettings()
    @Volatile var blocker: AdBlocker? = null

    fun blockingOn(): Boolean {
        if (!settings.blockRequests) return false
        val site = page?.let { Urls.host(it) }?.let { Urls.site(it) } ?: return true
        return site !in settings.allowedSites
    }
}

private const val MAX_BLOCKED_SHOWN = 1000

/** The in-app browser: the system WebView with filter-list blocking and a reader view. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: MainViewModel, startUrl: String, snackbar: SnackbarHostState, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val browserSettings by vm.browser.collectAsStateWithLifecycle()
    val blocker by vm.blocker.collectAsStateWithLifecycle()

    var pageUrl by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var reader by remember { mutableStateOf(false) }
    var readerOrigin by remember { mutableStateOf(startUrl) }
    var showBlocked by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val blocked = remember { mutableStateListOf<BlockedRequest>() }
    var blockedTotal by remember { mutableIntStateOf(0) }

    val live = remember { BrowserLive() }
    SideEffect {
        live.settings = browserSettings
        live.blocker = blocker
    }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame || !live.blockingOn()) return null
                    val url = request.url.toString()
                    val decision = live.blocker?.check(url, live.page) ?: return null
                    view.post {
                        blockedTotal++
                        if (blocked.size < MAX_BLOCKED_SHOWN) {
                            blocked.add(BlockedRequest(request.url.host.orEmpty(), decision.rule, decision.list))
                        }
                    }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty()
                    if (scheme != "http" && scheme != "https") {
                        // Mail, phone and app links go to whatever handles them.
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        return true
                    }
                    if (reader && request.isForMainFrame) {
                        // Following a link leaves the reader view.
                        reader = false
                        view.settings.javaScriptEnabled = true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    live.page = url
                    pageUrl = url
                    blocked.clear()
                    blockedTotal = 0
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageTitle = view.title.orEmpty()
                    val current = live.blocker
                    if (current == null || reader || !live.settings.hideElements || !live.blockingOn()) return
                    val host = Urls.host(url)
                    scope.launch {
                        val css = withContext(Dispatchers.Default) { current.elementHidingCss(host) }
                        if (css.isNotEmpty()) {
                            view.evaluateJavascript(
                                "(function(){var s=document.getElementById('newz-hide');" +
                                    "if(!s){s=document.createElement('style');s.id='newz-hide';" +
                                    "(document.head||document.documentElement).appendChild(s);}" +
                                    "s.textContent=" + JsonPrimitive(css).toString() + ";})()",
                                null,
                            )
                        }
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    pageTitle = title.orEmpty()
                }
            }
            live.page = startUrl
            loadUrl(startUrl)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    fun leaveReader() {
        reader = false
        webView.settings.javaScriptEnabled = true
        webView.loadUrl(readerOrigin)
    }

    fun enterReader() {
        val url = pageUrl
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { result ->
            scope.launch {
                val article = withContext(Dispatchers.Default) {
                    runCatching { Json.decodeFromString(String.serializer(), result) }.getOrNull()?.let { Reader.extract(it, url) }
                }
                if (article == null) {
                    vm.say("No article text was found on this page")
                } else {
                    reader = true
                    readerOrigin = url
                    // The reader page is static, so scripts stay off while it is shown.
                    webView.settings.javaScriptEnabled = false
                    webView.loadDataWithBaseURL(url, Reader.page(article, url, dark), "text/html", "utf-8", url)
                }
            }
        }
    }

    BackHandler {
        when {
            reader -> leaveReader()
            webView.canGoBack() -> webView.goBack()
            else -> onClose()
        }
    }

    val site = Urls.host(pageUrl)?.let { Urls.site(it) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                pageTitle.ifBlank { site.orEmpty() },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (if (reader) "Reader view · " else "") + site.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    },
                    actions = {
                        TextButton(onClick = { showBlocked = true }) { Text("$blockedTotal blocked") }
                        TextButton(onClick = { if (reader) leaveReader() else enterReader() }) {
                            Text(if (reader) "Web" else "Reader")
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Reload") },
                                onClick = {
                                    menu = false
                                    if (reader) leaveReader() else webView.reload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open in your browser") },
                                onClick = {
                                    menu = false
                                    if (!openUrl(context, pageUrl)) vm.say("No browser could open this")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share link") },
                                onClick = {
                                    menu = false
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl),
                                                null,
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize().padding(padding))
    }

    if (showBlocked) {
        val allowedHere = site != null && site in browserSettings.allowedSites
        ModalBottomSheet(onDismissRequest = { showBlocked = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Text("$blockedTotal requests blocked on this page", style = MaterialTheme.typography.titleLarge)
                if (site != null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Block on $site", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Turn off if the site does not work with blocking. The page reloads.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = !allowedHere && browserSettings.blockRequests,
                            enabled = browserSettings.blockRequests,
                            onCheckedChange = { on ->
                                val sites = if (on) browserSettings.allowedSites - site else (browserSettings.allowedSites + site).distinct()
                                val next = browserSettings.copy(allowedSites = sites)
                                live.settings = next
                                vm.saveBrowser(next)
                                if (reader) leaveReader() else webView.reload()
                            },
                        )
                    }
                }
                if (!browserSettings.blockRequests) {
                    Text("Blocking is switched off in Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (blocked.isEmpty()) {
                    Text("Nothing was blocked.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                blocked.groupBy { it.host }.entries.sortedByDescending { it.value.size }.forEach { (host, requests) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text("$host · ${requests.size}", style = MaterialTheme.typography.bodyLarge)
                        requests.map { it.rule to it.list }.distinct().take(3).forEach { (rule, list) ->
                            Text(
                                "$rule  ($list)",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
