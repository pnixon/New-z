package newz.core

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** AWS Signature Version 4, as used by S3 and S3-compatible stores such as Cloudflare R2. */
object SigV4 {
    private val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray(Charsets.UTF_8))

    fun timestamp(now: Long): String = amzDate.format(Instant.ofEpochMilli(now))

    /**
     * The Authorization header value for a request. [headers] are the headers
     * that will be sent, all of which are signed; they must include Host and
     * x-amz-date. [url] must already be percent-encoded.
     */
    fun authorization(
        method: String,
        url: String,
        headers: Map<String, String>,
        payloadHash: String,
        region: String,
        service: String,
        accessKeyId: String,
        secretKey: String,
    ): String {
        val uri = URI(url)
        val lower = headers.map { (k, v) -> k.lowercase() to v.trim().replace(Regex("\\s+"), " ") }.sortedBy { it.first }
        val stamp = lower.first { it.first == "x-amz-date" }.second
        val day = stamp.substring(0, 8)
        val signedHeaders = lower.joinToString(";") { it.first }
        val canonicalQuery = (uri.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.sorted().joinToString("&") {
            if ('=' in it) it else "$it="
        }
        val canonicalRequest = listOf(
            method.uppercase(),
            uri.rawPath.ifEmpty { "/" },
            canonicalQuery,
            lower.joinToString("") { "${it.first}:${it.second}\n" },
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val scope = "$day/$region/$service/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$stamp\n$scope\n" + sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        var key = hmac(("AWS4$secretKey").toByteArray(Charsets.UTF_8), day)
        for (part in listOf(region, service, "aws4_request")) key = hmac(key, part)
        val signature = hmac(key, toSign).joinToString("") { "%02x".format(it) }
        return "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope,SignedHeaders=$signedHeaders,Signature=$signature"
    }

    /** Percent-encodes an object key for use as a URL path, keeping the slashes. */
    fun encodePath(key: String): String = key.split("/").joinToString("/") { segment ->
        buildString {
            for (b in segment.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') append(c)
                else append("%%%02X".format(b.toInt() and 0xFF))
            }
        }
    }
}

/** Uploads one file to an S3-compatible bucket. */
object S3Uploader {
    fun put(
        http: HttpPutter,
        settings: PublishSettings,
        secretKey: String,
        body: ByteArray,
        contentType: String = "application/json",
        now: Long = System.currentTimeMillis(),
    ): HttpResponse {
        val base = settings.endpoint.trim().trimEnd('/')
        val url = "$base/${SigV4.encodePath(settings.bucket.trim())}/${SigV4.encodePath(settings.objectKey.trim().trimStart('/'))}"
        val uri = URI(url)
        val host = uri.host + if (uri.port == -1) "" else ":${uri.port}"
        val payloadHash = SigV4.sha256Hex(body)
        val headers = linkedMapOf(
            "Host" to host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to SigV4.timestamp(now),
        )
        val authorization = SigV4.authorization(
            "PUT", url, headers, payloadHash, settings.region.trim().ifEmpty { "auto" }, "s3",
            settings.accessKeyId.trim(), secretKey.trim(),
        )
        // The HTTP client sets Host itself.
        return http.put(url, headers.filterKeys { it != "Host" } + ("Authorization" to authorization), body, contentType)
    }
}
