package io.joryio.sdk.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The smallest image loader that does the job, deliberately.
 *
 * An SDK adding Glide or Coil imposes that dependency - and its version - on
 * every app that integrates. For one optional image in an in-app message that
 * is a poor trade, so this is ~80 lines of HttpURLConnection instead.
 *
 * Behaviour that matters:
 *  - **https only.** An in-app image URL is authored content; http would let a
 *    campaign trigger cleartext traffic (and be blocked by default on modern
 *    Android anyway).
 *  - **Bounded decode.** `inSampleSize` caps the decoded bitmap so a large
 *    image cannot OOM a low-end device.
 *  - **Fails invisibly.** A failed load HIDES the ImageView rather than leaving
 *    a blank 140dp gap in the middle of the message.
 *  - **Tag-checked.** The view is tagged with its URL before the request, so a
 *    recycled view cannot be filled by a response for a message the user has
 *    already dismissed.
 */
internal object ImageLoader {
    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "joryio-image").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** ~4MB of decoded bitmaps; a repeated message should not re-download. */
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private const val MAX_DIMENSION = 1024
    private const val MAX_BYTES = 5 * 1024 * 1024

    fun load(url: String, into: ImageView) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            into.visibility = View.GONE
            return
        }

        cache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }

        // Identify the pending request so a late response for a dismissed
        // message cannot paint over a different one.
        into.tag = url

        io.execute {
            val bitmap = runCatching { fetch(url) }.getOrNull()
            main.post {
                if (into.tag != url) return@post
                if (bitmap == null) {
                    // No broken-image placeholder: a message missing its picture
                    // still reads, a 140dp grey rectangle does not.
                    into.visibility = View.GONE
                } else {
                    cache.put(url, bitmap)
                    into.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) return null
            if (conn.contentLength > MAX_BYTES) return null

            // Read with the cap applied WHILE reading (scan 5, Android NICE 9):
            // a chunked response has no Content-Length, and readBytes() pulled
            // the whole body into memory before the size was checked.
            val bytes = BoundedRead.read(conn.inputStream, MAX_BYTES) ?: return null

            // Two passes: measure, then decode subsampled. Decoding a 4000px
            // image at full size to show it at 140dp is how an SDK gets blamed
            // for an OOM in someone else's app.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
                sample *= 2
            }

            return BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } finally {
            conn.disconnect()
        }
    }
}

/** Reads a stream with a size cap applied WHILE reading. No Android types: unit-testable. */
internal object BoundedRead {
    /** The stream's bytes, or null as soon as more than [max] arrive. */
    fun read(stream: java.io.InputStream, max: Int): ByteArray? = stream.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) return null
        }
        out.toByteArray()
    }
}
