package io.joryio.sdk.push

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches the picture for a rich push notification.
 *
 * Bounded on purpose, because this runs inside someone else's app on a device we
 * do not control and a notification is not worth an OOM:
 *  - connect/read timeouts, so a slow host cannot hold the FCM worker thread;
 *  - a hard byte cap, checked against Content-Length AND the bytes actually read
 *    (Content-Length is attacker-controlled and may simply be absent);
 *  - two-pass subsampled decode, so a 4000px image is not decoded at full size
 *    to be shown in a notification shade.
 *
 * Every failure returns null and the caller shows the notification without a
 * picture. A missing image must never cost the message.
 */
internal object NotificationImageLoader {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** Roughly a 2MB picture; far more than a notification shade can use. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /** Widest a big-picture is rendered at; decoding beyond this is wasted memory. */
    private const val TARGET_WIDTH_PX = 1024

    fun fetch(url: String): Bitmap? = try {
        val parsed = URL(url)
        // https only: a notification image is remote content rendered inside the
        // host app, and cleartext would let a network attacker choose the picture.
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            android.util.Log.w("Joryio", "Ignoring non-https push image URL")
            null
        } else {
            download(parsed)?.let(::decodeSubsampled)
        }
    } catch (t: Throwable) {
        // Malformed URL, DNS failure, TLS failure, OOM - all the same answer.
        android.util.Log.w("Joryio", "Push image unavailable: ${t.message}")
        null
    }

    private fun download(url: URL): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as? HttpsURLConnection) ?: return null
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            if (conn.contentLength > MAX_BYTES) return null

            val bytes = conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    // Enforce the cap on what actually arrives: Content-Length is
                    // supplied by the server and can lie or be missing entirely.
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
            bytes
        } catch (t: Throwable) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    private fun decodeSubsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > TARGET_WIDTH_PX) sample *= 2

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
