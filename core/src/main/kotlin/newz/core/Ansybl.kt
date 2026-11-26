package newz.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** An article the user chose to publish in their feed. Self-contained, so it outlives the stored article. */
@Serializable
data class SharedItem(
    val id: String,
    val url: String,
    val title: String,
    val summary: String? = null,
    val sourceName: String,
    val imageUrl: String? = null,
    val tags: List<String> = emptyList(),
    val sharedAt: Long,
)

/** Where and as whom the feed is published. The storage secret and the signing key are kept elsewhere. */
@Serializable
data class PublishSettings(
    val title: String = "My news picks",
    val description: String = "Articles I chose to share",
    val authorName: String = "",
    /** The public address subscribers use for the feed file. */
    val feedUrl: String = "",
    val homePageUrl: String = "",
    /** S3-compatible endpoint, for example https://ACCOUNT_ID.r2.cloudflarestorage.com */
    val endpoint: String = "",
    val bucket: String = "",
    /** The file's name inside the bucket. */
    val objectKey: String = "feed.json",
    val region: String = "auto",
    val accessKeyId: String = "",
    val maxItems: Int = 200,
    /** Upload the feed whenever the shared list changes. */
    val autoPublish: Boolean = true,
) {
    val ready: Boolean
        get() = feedUrl.isNotBlank() && endpoint.isNotBlank() && bucket.isNotBlank() && objectKey.isNotBlank() && accessKeyId.isNotBlank()
}

/** An Ansybl identity: an Ed25519 key pair in the protocol's "ed25519:" + base64 form. */
class AnsyblIdentity private constructor(private val privateKey: Ed25519PrivateKeyParameters) {
    val privateKeyString: String get() = PREFIX + Base64.getEncoder().encodeToString(privateKey.encoded)
    val publicKeyString: String get() = PREFIX + Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)

    /** First 8 bytes of the SHA-256 of the public key, as hex: how Ansybl names an identity. */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(privateKey.generatePublicKey().encoded)
            .take(8).joinToString("") { "%02x".format(it) }

    fun sign(content: String): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        val bytes = content.toByteArray(Charsets.UTF_8)
        signer.update(bytes, 0, bytes.size)
        return PREFIX + Base64.getEncoder().encodeToString(signer.generateSignature())
    }

    companion object {
        const val PREFIX = "ed25519:"

        fun generate(): AnsyblIdentity = AnsyblIdentity(Ed25519PrivateKeyParameters(SecureRandom()))

        /** Reads a private key in "ed25519:BASE64" form (the prefix is optional). */
        fun fromPrivateKey(text: String): AnsyblIdentity {
            val bytes = Base64.getDecoder().decode(text.trim().removePrefix(PREFIX))
            require(bytes.size == 32) { "An Ed25519 private key is 32 bytes" }
            return AnsyblIdentity(Ed25519PrivateKeyParameters(bytes, 0))
        }

        fun verify(content: String, signature: String, publicKey: String): Boolean = try {
            val key = Ed25519PublicKeyParameters(Base64.getDecoder().decode(publicKey.removePrefix(PREFIX)), 0)
            val signer = Ed25519Signer()
            signer.init(false, key)
            val bytes = content.toByteArray(Charsets.UTF_8)
            signer.update(bytes, 0, bytes.size)
            signer.verifySignature(Base64.getDecoder().decode(signature.removePrefix(PREFIX)))
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Builds an Ansybl 1.0 feed document, signed the way the reference
 * implementation signs: Ed25519 over the canonical JSON of the signable
 * fields, with keys sorted and no whitespace.
 */
object Ansybl {
    const val VERSION = "https://ansybl.org/version/1.0"
    /** Readers cut feeds off at 2 MB; stay well under. */
    private const val MAX_BYTES = 1_800_000

    /** Sorted keys, no whitespace, strings escaped exactly as JavaScript's JSON.stringify does. */
    fun canonicalize(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (value.isString) quote(value.content) else value.content
        is JsonArray -> value.joinToString(",", "[", "]") { canonicalize(it) }
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { quote(it) + ":" + canonicalize(value.getValue(it)) }
    }

    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** The text shown by readers that display only an item's content. */
    fun contentText(item: SharedItem): String = buildString {
        append(item.title)
        item.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" — ").append(it) }
        append(" (via ").append(item.sourceName).append(") ").append(item.url)
    }

    private fun mimeOf(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".avif") -> "image/avif"
            else -> "image/jpeg"
        }
    }

    /** The fields an item signature covers, as the reference implementation selects them. */
    fun itemSignable(id: String, url: String, datePublished: String, contentText: String, authorName: String, publicKey: String): JsonObject =
        buildJsonObject {
            put("id", id)
            put("url", url)
            put("date_published", datePublished)
            put("content_text", contentText)
            putJsonObject("author") {
                put("name", authorName)
                put("public_key", publicKey)
            }
        }

    fun feedSignable(settings: PublishSettings, authorName: String, publicKey: String, timestamp: String): JsonObject = buildJsonObject {
        putJsonObject("author") {
            put("name", authorName)
            put("public_key", publicKey)
        }
        put("feed_url", settings.feedUrl)
        put("home_page_url", settings.homePageUrl.ifBlank { settings.feedUrl })
        put("timestamp", timestamp)
        put("title", settings.title)
        put("version", VERSION)
    }

    /**
     * The feed file's JSON. Items are listed newest first, at most
     * [PublishSettings.maxItems], and fewer if needed to stay under the size
     * readers accept.
     *
     * Two things go beyond the 1.0 spec, for the reference reader's sake:
     * a `posts` list mirroring `items` in the shape its timeline reads, and a
     * top-level `timestamp`, without which the feed signature could not be
     * checked by anyone.
     */
    fun buildFeed(settings: PublishSettings, shared: List<SharedItem>, identity: AnsyblIdentity, now: Long = System.currentTimeMillis()): String {
        val authorName = settings.authorName.ifBlank { settings.title }
        val publicKey = identity.publicKeyString
        val timestamp = Instant.ofEpochMilli(now).toString()
        val handle = authorName.lowercase().filter { it.isLetterOrDigit() }.take(30).ifEmpty { "newz" }
        var items = shared.sortedByDescending { it.sharedAt }.take(settings.maxItems.coerceIn(1, 1000))

        while (true) {
            val feed = buildJsonObject {
                put("version", VERSION)
                put("title", settings.title)
                put("home_page_url", settings.homePageUrl.ifBlank { settings.feedUrl })
                put("feed_url", settings.feedUrl)
                if (settings.description.isNotBlank()) put("description", settings.description)
                putJsonObject("author") {
                    put("name", authorName)
                    put("public_key", publicKey)
                }
                put("timestamp", timestamp)
                putJsonArray("items") { items.forEach { add(itemJson(it, settings, authorName, publicKey, identity)) } }
                putJsonArray("posts") { items.forEach { add(postJson(it, settings, authorName, handle, publicKey, identity)) } }
                put("signature", identity.sign(canonicalize(feedSignable(settings, authorName, publicKey, timestamp))))
            }
            val text = feed.toString()
            if (text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES || items.size <= 1) return text
            items = items.take(items.size * 3 / 4)
        }
    }

    private fun itemId(settings: PublishSettings, item: SharedItem) = settings.feedUrl + "#" + item.id

    private fun signatureFor(item: SharedItem, settings: PublishSettings, authorName: String, publicKey: String, identity: AnsyblIdentity): String {
        val date = Instant.ofEpochMilli(item.sharedAt).toString()
        return identity.sign(canonicalize(itemSignable(itemId(settings, item), item.url, date, contentText(item), authorName, publicKey)))
    }

    private fun tagsOf(item: SharedItem): JsonArray = buildJsonArray {
        (item.tags + item.sourceName).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(10).forEach { add(JsonPrimitive(it)) }
    }

    private fun itemJson(item: SharedItem, settings: PublishSettings, authorName: String, publicKey: String, identity: AnsyblIdentity): JsonObject =
        buildJsonObject {
            put("id", itemId(settings, item))
            put("url", item.url)
            put("title", item.title)
            item.summary?.takeIf { it.isNotBlank() }?.let { put("summary", it) }
            put("content_text", contentText(item))
            put("date_published", Instant.ofEpochMilli(item.sharedAt).toString())
            put("tags", tagsOf(item))
            item.imageUrl?.takeIf { it.startsWith("https://") }?.let { image ->
                putJsonArray("attachments") {
                    add(buildJsonObject {
                        put("url", image)
                        put("mime_type", mimeOf(image))
                    })
                }
                putJsonArray("media_urls") { add(JsonPrimitive(image)) }
            }
            putJsonObject("author") {
                put("name", authorName)
                put("public_key", publicKey)
            }
            put("signature", signatureFor(item, settings, authorName, publicKey, identity))
        }

    private fun postJson(item: SharedItem, settings: PublishSettings, authorName: String, handle: String, publicKey: String, identity: AnsyblIdentity): JsonObject =
        buildJsonObject {
            put("guid", item.id)
            put("type", "short_text")
            put("content", contentText(item))
            put("created_at", Instant.ofEpochMilli(item.sharedAt).toString())
            put("tags", tagsOf(item))
            putJsonArray("media_urls") { item.imageUrl?.takeIf { it.startsWith("https://") }?.let { add(JsonPrimitive(it)) } }
            putJsonObject("author") {
                put("name", authorName)
                put("handle", handle)
                put("public_key", publicKey)
            }
            put("signature", signatureFor(item, settings, authorName, publicKey, identity))
        }
}
