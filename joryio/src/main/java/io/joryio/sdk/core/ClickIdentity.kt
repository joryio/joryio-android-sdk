package io.joryio.sdk.core

/**
 * Click-link identity: the token a tracked message link carries.
 *
 * When someone clicks a tracked link in a Joryio message, the click redirect
 * appends an opaque, signed token as `jr_t`. An app opened by that link (a
 * deep link / app link) hands the URL to `Joryio.identifyFromLink`, which
 * sends the token with this SDK's anonymous id to `v1/identify-link`, and the
 * server attaches this visitor to the contact the message was sent to.
 *
 * The wire values are byte-identical on all five SDKs (pinned by the backend
 * spec sdk-click-identity-parity.spec.ts): the query parameter `jr_t`, the
 * path `v1/identify-link`, a body of exactly `token` and `anonymousId`.
 *
 * The token is a credential for one person's identity: it is never logged
 * above debug, never persisted, and held in memory only.
 */
internal object ClickIdentity {
    /** The query parameter the click redirect appends. */
    const val CLICK_IDENTITY_PARAM = "jr_t"

    /** Relative to the API base URL, like every other SDK route (no leading slash). */
    const val IDENTIFY_LINK_PATH = "v1/identify-link"

    /** A well-formed token; anything else is ignored locally, with no request. */
    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{16,512}$")

    /**
     * Most tokens held while consent is pending. A person clicks a handful of
     * links per launch at most; the bound only keeps a misbehaving host from
     * growing the list without limit.
     */
    const val MAX_HELD_TOKENS = 20

    /**
     * The `jr_t` value of [url]'s query, or null when there is none or it is
     * malformed. Parsed by hand rather than with android.net.Uri so it works
     * the same for any string a bridge passes (and in a plain JVM test).
     */
    fun tokenFrom(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val fragmentStart = url.indexOf('#', queryStart)
        val query = if (fragmentStart < 0) url.substring(queryStart + 1) else url.substring(queryStart + 1, fragmentStart)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val name = decode(pair.substring(0, eq)) ?: continue
            if (name != CLICK_IDENTITY_PARAM) continue
            val value = decode(pair.substring(eq + 1)) ?: return null
            return value.takeIf { TOKEN_PATTERN.matches(it) }
        }
        return null
    }

    private fun decode(s: String): String? = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        null
    }
}
