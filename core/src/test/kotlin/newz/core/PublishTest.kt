package newz.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

private const val NOW = 1_795_000_000_000L

class PublishTest {
    private val settings = PublishSettings(
        title = "Pete's picks",
        authorName = "Pete",
        feedUrl = "https://feeds.example.com/feed.json",
        homePageUrl = "https://feeds.example.com/",
        endpoint = "https://abc123.r2.cloudflarestorage.com",
        bucket = "newz",
        accessKeyId = "AKID",
    )

    private fun shared(n: Int, summary: String? = "A summary") = SharedItem(
        id = "id-$n", url = "https://www.npr.org/story-$n", title = "Story $n", summary = summary,
        sourceName = "NPR", imageUrl = "https://media.npr.org/$n.png", tags = listOf("Politics", "npr"), sharedAt = NOW - n * 60_000L,
    )

    @Test
    fun ed25519MatchesTheRfc8032TestVector() {
        val identity = AnsyblIdentity.fromPrivateKey("ed25519:" + b64(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")))
        assertEquals("ed25519:" + b64(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")), identity.publicKeyString)
        val expected = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
        assertEquals("ed25519:" + b64(hex(expected)), identity.sign(""))
        assertEquals(16, identity.fingerprint.length)
    }

    @Test
    fun generatedIdentitiesSignVerifyAndRoundTrip() {
        val a = AnsyblIdentity.generate()
        val b = AnsyblIdentity.generate()
        assertNotEquals(a.publicKeyString, b.publicKeyString)
        val signature = a.sign("hello")
        assertTrue(AnsyblIdentity.verify("hello", signature, a.publicKeyString))
        assertFalse(AnsyblIdentity.verify("hello!", signature, a.publicKeyString))
        assertFalse(AnsyblIdentity.verify("hello", signature, b.publicKeyString))
        assertFalse(AnsyblIdentity.verify("hello", "ed25519:garbage", a.publicKeyString))
        assertEquals(a.publicKeyString, AnsyblIdentity.fromPrivateKey(a.privateKeyString).publicKeyString)
    }

    @Test
    fun canonicalFormSortsKeysAndEscapesLikeJsonStringify() {
        val value = buildJsonObject {
            put("b", "line\nbreak \"quoted\" back\\slash tab\t bell\u0007 é")
            put("a", 1)
            putJsonArray("c") { add(buildJsonObject { put("z", true); put("y", null as String?) }) }
        }
        assertEquals(
            """{"a":1,"b":"line\nbreak \"quoted\" back\\slash tab\t bell\u0007 é","c":[{"y":null,"z":true}]}""",
            Ansybl.canonicalize(value),
        )
    }

    @Test
    fun feedHasEveryFieldTheReferenceReaderNeedsAndItsSignaturesVerify() {
        val identity = AnsyblIdentity.generate()
        val feed = Json.parseToJsonElement(Ansybl.buildFeed(settings, listOf(shared(2), shared(1)), identity, NOW)).jsonObject
        fun str(key: String) = feed.getValue(key).jsonPrimitive.content

        assertEquals("https://ansybl.org/version/1.0", str("version"))
        assertEquals("Pete's picks", str("title"))
        assertEquals(settings.feedUrl, str("feed_url"))
        val key = feed.getValue("author").jsonObject.getValue("public_key").jsonPrimitive.content
        assertEquals(identity.publicKeyString, key)
        assertTrue(key.startsWith("ed25519:"))

        // The feed signature can be checked because the signed timestamp is in the file.
        val feedSignable = Ansybl.feedSignable(settings, "Pete", key, str("timestamp"))
        assertTrue(AnsyblIdentity.verify(Ansybl.canonicalize(feedSignable), str("signature"), key))

        val items = feed.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("https://www.npr.org/story-1", "https://www.npr.org/story-2"), items.map { it.getValue("url").jsonPrimitive.content }, "newest first")
        val first = items[0]
        fun f(k: String) = first.getValue(k).jsonPrimitive.content
        assertEquals("https://feeds.example.com/feed.json#id-1", f("id"))
        assertEquals("Story 1 — A summary (via NPR) https://www.npr.org/story-1", f("content_text"))
        assertEquals("image/png", first.getValue("attachments").jsonArray[0].jsonObject.getValue("mime_type").jsonPrimitive.content)
        assertEquals(listOf("Politics", "npr"), first.getValue("tags").jsonArray.map { it.jsonPrimitive.content }, "the source is not repeated as a tag")
        // Signed exactly as the reference implementation's canonicalizeItem selects fields.
        val signable = Ansybl.itemSignable(f("id"), f("url"), f("date_published"), f("content_text"), "Pete", key)
        assertEquals(
            """{"author":{"name":"Pete","public_key":"$key"},"content_text":"${f("content_text")}","date_published":"${f("date_published")}","id":"${f("id")}","url":"${f("url")}"}""",
            Ansybl.canonicalize(signable),
        )
        assertTrue(AnsyblIdentity.verify(Ansybl.canonicalize(signable), f("signature"), key))

        // The mirror list the reference reader's timeline actually reads.
        val post = feed.getValue("posts").jsonArray[0].jsonObject
        assertEquals("short_text", post.getValue("type").jsonPrimitive.content)
        assertEquals(f("content_text"), post.getValue("content").jsonPrimitive.content)
        assertEquals("id-1", post.getValue("guid").jsonPrimitive.content)
        assertEquals("https://media.npr.org/1.png", post.getValue("media_urls").jsonArray[0].jsonPrimitive.content)
        assertEquals("pete", post.getValue("author").jsonObject.getValue("handle").jsonPrimitive.content)
    }

    @Test
    fun feedKeepsOnlyTheNewestItemsAndStaysUnderTheSizeReadersAccept() {
        val identity = AnsyblIdentity.generate()
        val capped = Json.parseToJsonElement(Ansybl.buildFeed(settings.copy(maxItems = 3), (1..10).map { shared(it) }, identity, NOW)).jsonObject
        assertEquals(3, capped.getValue("items").jsonArray.size)

        val huge = (1..400).map { shared(it, summary = "x".repeat(5_000)) }
        val text = Ansybl.buildFeed(settings.copy(maxItems = 1000), huge, identity, NOW)
        assertTrue(text.toByteArray().size <= 1_800_000, "was ${text.toByteArray().size}")
        assertTrue(Json.parseToJsonElement(text).jsonObject.getValue("items").jsonArray.size in 1..399)

        val empty = Json.parseToJsonElement(Ansybl.buildFeed(settings, emptyList(), identity, NOW)).jsonObject
        assertEquals(0, empty.getValue("items").jsonArray.size)
    }

    @Test
    fun anItemWithoutSummaryOrSecureImageOmitsThem() {
        val identity = AnsyblIdentity.generate()
        val item = shared(1, summary = null).copy(imageUrl = "http://insecure.example.com/a.jpg")
        val first = Json.parseToJsonElement(Ansybl.buildFeed(settings, listOf(item), identity, NOW)).jsonObject.getValue("items").jsonArray[0].jsonObject
        assertNull(first["summary"])
        assertNull(first["attachments"])
        assertEquals("Story 1 (via NPR) https://www.npr.org/story-1", first.getValue("content_text").jsonPrimitive.content)
    }

    // The two worked examples from the AWS Signature Version 4 documentation for S3.
    private val awsKey = "AKIAIOSFODNN7EXAMPLE"
    private val awsSecret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

    @Test
    fun sigV4MatchesTheAwsGetObjectExample() {
        val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(emptyHash, SigV4.sha256Hex(ByteArray(0)))
        val auth = SigV4.authorization(
            "GET", "https://examplebucket.s3.amazonaws.com/test.txt",
            mapOf("Host" to "examplebucket.s3.amazonaws.com", "Range" to "bytes=0-9", "x-amz-content-sha256" to emptyHash, "x-amz-date" to "20130524T000000Z"),
            emptyHash, "us-east-1", "s3", awsKey, awsSecret,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date," +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            auth,
        )
    }

    @Test
    fun sigV4MatchesTheAwsPutObjectExample() {
        val body = "Welcome to Amazon S3.".toByteArray()
        val hash = SigV4.sha256Hex(body)
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash)
        val auth = SigV4.authorization(
            "PUT", "https://examplebucket.s3.amazonaws.com/" + SigV4.encodePath("test\$file.text"),
            mapOf(
                "Date" to "Fri, 24 May 2013 00:00:00 GMT", "Host" to "examplebucket.s3.amazonaws.com",
                "x-amz-content-sha256" to hash, "x-amz-date" to "20130524T000000Z", "x-amz-storage-class" to "REDUCED_REDUNDANCY",
            ),
            hash, "us-east-1", "s3", awsKey, awsSecret,
        )
        assertTrue(auth.endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"), auth)
    }

    @Test
    fun uploadTargetsTheBucketAndSignsWhatItSends() {
        var seenUrl = ""
        var seenHeaders = emptyMap<String, String>()
        var seenType = ""
        val http = HttpPutter { url, headers, _, contentType ->
            seenUrl = url; seenHeaders = headers; seenType = contentType
            HttpResponse(200, "", finalUrl = url)
        }
        val body = "{}".toByteArray()
        val response = S3Uploader.put(http, settings.copy(objectKey = "/feeds/my feed.json"), "secret", body, now = NOW)
        assertEquals(200, response.code)
        assertEquals("https://abc123.r2.cloudflarestorage.com/newz/feeds/my%20feed.json", seenUrl)
        assertEquals("application/json", seenType)
        assertEquals(SigV4.sha256Hex(body), seenHeaders["x-amz-content-sha256"])
        assertEquals(SigV4.timestamp(NOW), seenHeaders["x-amz-date"])
        assertNull(seenHeaders["Host"], "the HTTP client supplies Host")
        val auth = seenHeaders.getValue("Authorization")
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKID/${SigV4.timestamp(NOW).take(8)}/auto/s3/aws4_request,SignedHeaders=host;x-amz-content-sha256;x-amz-date,Signature="))
        assertFalse("secret" in auth)
    }

    @Test
    fun publishSettingsTravelInTheConfigFileWithoutSecrets() {
        val config = AppConfig(publish = settings)
        val text = ConfigJson.encode(config)
        assertEquals(config, ConfigJson.decode(text))
        assertFalse("secretKey" in text || "privateKey" in text)
        assertFalse(PublishSettings().ready)
        assertTrue(settings.ready)
    }
}
