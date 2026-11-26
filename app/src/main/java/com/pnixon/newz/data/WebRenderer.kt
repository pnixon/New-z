package com.pnixon.newz.data

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import newz.core.BrowserSettings
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Loads a page in a hidden WebView, lets its JavaScript run, and returns the
 * resulting HTML. Used for listing pages that are empty until scripts fill
 * them. Images are not loaded and blocked hosts are skipped, to keep it quick.
 */
class WebRenderer(
    private val context: Context,
    private val filters: FilterListStore,
    private val settings: JsonStore<BrowserSettings>,
) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(url: String, userAgent: String? = null): String = withContext(Dispatchers.Main) {
        withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)
                var done = false

                fun finish(html: String?, error: String?) {
                    if (done) return
                    done = true
                    webView.stopLoading()
                    webView.destroy()
                    if (!continuation.isActive) return
                    if (html != null) continuation.resume(html) else continuation.resumeWithException(IOException(error ?: "Rendering failed"))
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.blockNetworkImage = true
                webView.settings.mediaPlaybackRequiresUserGesture = true
                if (!userAgent.isNullOrBlank()) webView.settings.userAgentString = userAgent
                // Give the page a phone-sized viewport so lazy layouts fill in.
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
                )
                webView.layout(0, 0, 1080, 2400)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.isForMainFrame || !settings.flow.value.blockRequests) return null
                        filters.blocker.value.check(request.url.toString(), url) ?: return null
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        // Wait a little for scripts to fetch and draw the list.
                        view.postDelayed({
                            if (done) return@postDelayed
                            view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { json ->
                                val html = runCatching { Json.decodeFromString(String.serializer(), json) }.getOrNull()
                                if (html.isNullOrBlank()) finish(null, "The page rendered empty") else finish(html, null)
                            }
                        }, SETTLE_MS)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) finish(null, "The page failed to load: ${error.description}")
                    }
                }
                continuation.invokeOnCancellation {
                    webView.post {
                        if (!done) {
                            done = true
                            webView.stopLoading()
                            webView.destroy()
                        }
                    }
                }
                webView.loadUrl(url)
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 45_000L
        private const val SETTLE_MS = 2_500L
    }
}
