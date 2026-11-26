package newz.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class HttpResponse(
    val code: Int,
    val body: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val finalUrl: String,
)

/** Blocking GET. Throws IOException on network failure. */
fun interface HttpFetcher {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) NewZ/0.1"

/** Responses larger than this are cut off; listing pages and feeds are far smaller. */
private const val MAX_BODY_BYTES = 8L * 1024 * 1024

class OkHttpFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
) : HttpFetcher {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            val body = response.body
            val text = if (body == null) {
                ""
            } else {
                val source = body.source()
                source.request(MAX_BODY_BYTES)
                val size = minOf(source.buffer.size, MAX_BODY_BYTES)
                val charset = body.contentType()?.charset() ?: Charsets.UTF_8
                source.buffer.readString(size, charset)
            }
            return HttpResponse(
                code = response.code,
                body = text,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                finalUrl = response.request.url.toString(),
            )
        }
    }
}
