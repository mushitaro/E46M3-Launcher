package app.tsunagi.e46m3.launcher.ota

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The OTA subsystem's HTTP client: `HttpURLConnection`, and nothing else.
 *
 * No OkHttp, no Retrofit. This app has no networking dependency today and the
 * reasons it has no Compose either apply here — the cold-start budget is 800 ms
 * on an in-order Cortex-A7 and the home screen pays it on every boot. The whole
 * need is two small GETs and one ranged one.
 *
 * ## Redirects are followed by hand
 *
 * `instanceFollowRedirects` is turned off and the hops are walked here, for
 * three reasons:
 *
 *  - the **final** URL can be logged, so "which release did this actually come
 *    from" is answerable after the fact;
 *  - an `https → http` downgrade can be refused outright, which the built-in
 *    follower would silently perform within the same host;
 *  - `HttpURLConnection` refuses to follow across a protocol change anyway, and
 *    it does so by quietly returning the 30x to the caller — a redirect that
 *    reads as a successful response with an empty body.
 *
 * GitHub's `releases/latest/download/…` takes two same-protocol hops out to
 * `objects.githubusercontent.com`.
 *
 * ## Accept-Encoding: identity, always
 *
 * Left alone, `HttpURLConnection` advertises gzip and transparently decodes the
 * response. For a payload whose signature covers exact bytes that makes "the
 * bytes I verified" ambiguous, and for a ranged request it makes
 * `Content-Length` describe something other than what lands on disk.
 */
internal object OtaHttp {

    sealed class Fetch {
        class Ok(val bytes: ByteArray, val finalUrl: String) : Fetch()

        /**
         * @param clockFault the failure is a certificate validity problem, i.e.
         *        this device's clock, not the network. Worth its own field
         *        because the two need completely different messages.
         */
        data class Failed(
            val reason: String,
            val clockFault: Boolean = false,
            val cause: Throwable? = null,
        ) : Fetch()
    }

    /**
     * An open response body. The caller streams it and closes it.
     *
     * @param honouredRange the server answered 206. A **200 to a ranged request
     *        means the range was ignored**, and the body is the whole file from
     *        byte zero — so appending it to an existing prefix would silently
     *        produce a corrupt APK that is only caught later by its hash. The
     *        caller has to look at this flag; that is the entire reason it is
     *        not a boolean buried in a log line.
     */
    class Body(
        val stream: InputStream,
        val honouredRange: Boolean,
        val contentLength: Long,
        val finalUrl: String,
        private val connection: HttpURLConnection,
    ) : Closeable {
        override fun close() {
            runCatching { stream.close() }
            runCatching { connection.disconnect() }
        }
    }

    sealed class Open {
        class Ok(val body: Body) : Open()
        data class Failed(
            val reason: String,
            val clockFault: Boolean = false,
            val cause: Throwable? = null,
        ) : Open()
    }

    /**
     * Fetches a small document into memory.
     *
     * @param maxBytes hard ceiling. A misconfigured URL that happens to serve
     *        something enormous must not be read into a 192 MB heap before it
     *        is rejected for failing its signature.
     */
    fun get(url: String, maxBytes: Int): Fetch {
        return when (val open = open(url, rangeFrom = 0L)) {
            is Open.Failed -> Fetch.Failed(open.reason, open.clockFault, open.cause)
            is Open.Ok -> open.body.use { body ->
                if (body.contentLength > maxBytes) {
                    return Fetch.Failed("TOO_LARGE_${body.contentLength}")
                }
                val buffer = ByteArrayOutputStream(
                    if (body.contentLength in 1..maxBytes.toLong()) body.contentLength.toInt()
                    else 8 * 1024
                )
                val chunk = ByteArray(8 * 1024)
                try {
                    while (true) {
                        val read = body.stream.read(chunk)
                        if (read < 0) break
                        if (buffer.size() + read > maxBytes) return Fetch.Failed("TOO_LARGE")
                        buffer.write(chunk, 0, read)
                    }
                } catch (e: IOException) {
                    val clock = OtaClock.looksLikeClockFault(e)
                    return Fetch.Failed(if (clock) "CLOCK" else "NETWORK", clock, e)
                }
                Log.i(Ota.TAG, "GET ${body.finalUrl} -> ${buffer.size()} bytes")
                Fetch.Ok(buffer.toByteArray(), body.finalUrl)
            }
        }
    }

    /**
     * Opens a response body, optionally from a byte offset.
     *
     * @param rangeFrom 0 for a whole-file request; anything higher sends
     *        `Range: bytes=<n>-`. Whether the server honoured it is reported on
     *        [Body.honouredRange] rather than assumed.
     */
    fun open(url: String, rangeFrom: Long): Open {
        var current = try {
            URL(url)
        } catch (e: Exception) {
            return Open.Failed("BAD_URL", cause = e)
        }

        var hops = 0
        while (true) {
            var connection: HttpURLConnection? = null
            var keep = false
            try {
                connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = Ota.CONNECT_TIMEOUT_MS
                    readTimeout = Ota.READ_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Accept", "*/*")
                    if (rangeFrom > 0L) setRequestProperty("Range", "bytes=$rangeFrom-")
                }

                val code = connection.responseCode

                if (code in REDIRECTS) {
                    val location = connection.getHeaderField("Location")
                        ?: return Open.Failed("REDIRECT_NO_LOCATION")
                    if (++hops > Ota.MAX_REDIRECTS) return Open.Failed("TOO_MANY_REDIRECTS")
                    val next = try {
                        URL(current, location)
                    } catch (e: Exception) {
                        return Open.Failed("BAD_REDIRECT", cause = e)
                    }
                    if (current.protocol.equals("https", true) &&
                        !next.protocol.equals("https", true)
                    ) {
                        // Not merely unsupported — refused. A downgrade here is
                        // the shape a transport attack would take, and the only
                        // thing standing behind it is the manifest signature.
                        return Open.Failed("HTTPS_DOWNGRADE_REFUSED")
                    }
                    current = next
                    continue
                }

                val partial = code == HttpURLConnection.HTTP_PARTIAL
                if (code != HttpURLConnection.HTTP_OK && !partial) {
                    return Open.Failed("HTTP_$code")
                }

                @Suppress("DEPRECATION")  // getContentLengthLong is API 24; minSdk is 21
                val length = connection.contentLength.toLong()

                keep = true
                return Open.Ok(
                    Body(
                        stream = connection.inputStream,
                        honouredRange = partial,
                        contentLength = length,
                        finalUrl = current.toString(),
                        connection = connection,
                    )
                )
            } catch (e: IOException) {
                val clock = OtaClock.looksLikeClockFault(e)
                Log.w(Ota.TAG, "GET $current failed (clockFault=$clock)", e)
                return Open.Failed(if (clock) "CLOCK" else "NETWORK", clockFault = clock, cause = e)
            } catch (e: Exception) {
                Log.w(Ota.TAG, "GET $current failed", e)
                return Open.Failed("ERROR_${e.javaClass.simpleName}", cause = e)
            } finally {
                // Only when the body is not being handed to the caller: the
                // stream dies with the connection.
                if (!keep) connection?.disconnect()
            }
        }
    }

    private val REDIRECTS = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,   // 301
        HttpURLConnection.HTTP_MOVED_TEMP,   // 302
        HttpURLConnection.HTTP_SEE_OTHER,    // 303
        307,
        308,
    )
}
