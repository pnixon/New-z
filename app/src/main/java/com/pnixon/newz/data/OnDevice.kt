package com.pnixon.newz.data

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import newz.core.LlmClient
import newz.core.LlmException
import newz.core.LlmReply

/**
 * The phone's built-in language model, where it has one. Nothing leaves the
 * phone and there is no key or cost, but it accepts only a few thousand
 * tokens and is weaker than a remote model.
 */
class OnDeviceModel {
    private val _status = MutableStateFlow("Not checked yet")
    val status: StateFlow<String> = _status

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available

    private val _downloadable = MutableStateFlow(false)
    val downloadable: StateFlow<Boolean> = _downloadable

    private val model by lazy { Generation.getClient() }

    /** Asks the phone whether the model is present, downloadable or unsupported. */
    suspend fun refresh() {
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> set("Ready on this phone", available = true, downloadable = false)
                FeatureStatus.DOWNLOADABLE -> set("Supported, but the model is not downloaded yet", available = false, downloadable = true)
                FeatureStatus.DOWNLOADING -> set("The model is downloading", available = false, downloadable = false)
                else -> set("This phone does not offer a built-in model to apps", available = false, downloadable = false)
            }
        } catch (e: Throwable) {
            set("Not available: ${e.message ?: e.javaClass.simpleName}", available = false, downloadable = false)
        }
    }

    private fun set(text: String, available: Boolean, downloadable: Boolean) {
        _status.value = text
        _available.value = available
        _downloadable.value = downloadable
    }

    /** Downloads the model. It is large, so this is only started by the user. */
    suspend fun download() {
        try {
            model.download().collect { step ->
                when (step) {
                    is DownloadStatus.DownloadProgress -> _status.value = "Downloading: ${step.totalBytesDownloaded / 1_000_000} MB so far"
                    is DownloadStatus.DownloadFailed -> _status.value = "Download failed: ${step.e.message}"
                    is DownloadStatus.DownloadCompleted -> _status.value = "Download finished"
                    else -> _status.value = "Download started"
                }
            }
        } catch (e: Throwable) {
            _status.value = "Download failed: ${e.message ?: e.javaClass.simpleName}"
        }
        refresh()
    }

    /** A blocking client for the shared model code. The system and user prompts are sent as one text. */
    val llm = LlmClient { request ->
        if (!_available.value) throw LlmException("The on-device model is not ready")
        try {
            val response = runBlocking {
                withTimeout(TIMEOUT_MS) { model.generateContent(request.system + "\n\n" + request.user) }
            }
            LlmReply(response.candidates.firstOrNull()?.text.orEmpty())
        } catch (e: Throwable) {
            throw LlmException("The on-device model failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /** How the model is named in the request log. */
        const val NAME = "on-device model"
        private const val TIMEOUT_MS = 180_000L
    }
}
