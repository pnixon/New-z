package com.pnixon.newz.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import newz.core.Ansybl
import newz.core.AnsyblIdentity
import newz.core.HttpPutter
import newz.core.PublishSettings
import newz.core.S3Uploader
import newz.core.SharedItem

/** Signs the shared articles as an Ansybl feed and uploads the file to the user's bucket. */
class Publisher(
    context: Context,
    private val http: HttpPutter,
    private val settings: JsonStore<PublishSettings>,
    private val shared: JsonStore<List<SharedItem>>,
    private val secrets: SecretStore,
) {
    private val prefs = context.getSharedPreferences("publish", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _status = MutableStateFlow(prefs.getString("status", null) ?: "Not published yet")
    val status: StateFlow<String> = _status

    private val _publicKey = MutableStateFlow(runCatching { loadIdentity()?.publicKeyString }.getOrNull())
    /** Null until an identity exists. */
    val publicKey: StateFlow<String?> = _publicKey

    private fun loadIdentity(): AnsyblIdentity? =
        secrets.get(SecretStore.ANSYBL_PRIVATE_KEY)?.let { runCatching { AnsyblIdentity.fromPrivateKey(it) }.getOrNull() }

    /** The signing identity, created on first use. */
    fun identity(): AnsyblIdentity = loadIdentity() ?: AnsyblIdentity.generate().also { store(it) }

    private fun store(identity: AnsyblIdentity) {
        secrets.set(SecretStore.ANSYBL_PRIVATE_KEY, identity.privateKeyString)
        _publicKey.value = identity.publicKeyString
    }

    fun newIdentity() = store(AnsyblIdentity.generate())

    /** Replaces the identity with an existing private key. Throws if the text is not one. */
    fun importIdentity(privateKey: String) = store(AnsyblIdentity.fromPrivateKey(privateKey))

    private fun setStatus(text: String) {
        _status.value = text
        prefs.edit().putString("status", text).apply()
    }

    /** Builds, signs and uploads the feed. Returns true when the bucket accepted it. */
    suspend fun publish(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = settings.flow.value
            val secret = secrets.get(SecretStore.PUBLISH_SECRET_KEY)
            if (!current.ready || secret == null) {
                setStatus("Not published: the storage settings or secret key are missing")
                return@withLock false
            }
            val items = shared.flow.value
            try {
                val feed = Ansybl.buildFeed(current, items, identity())
                val response = S3Uploader.put(http, current, secret, feed.toByteArray(Charsets.UTF_8))
                if (response.code in 200..299) {
                    setStatus("Published ${minOf(items.size, current.maxItems)} articles at ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
                    true
                } else {
                    setStatus("Upload refused: HTTP ${response.code} ${response.body.take(300)}")
                    false
                }
            } catch (e: Exception) {
                setStatus("Upload failed: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }
    }
}
