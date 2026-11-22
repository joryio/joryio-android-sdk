package io.joryio.sdk.core

/**
 * Schemes never opened nor handed to the app, whatever a link says (the
 * backend's placement / card link rule). The same list on every Joryio SDK,
 * pinned by the backend spec sdk-placements-parity.spec.ts.
 */
internal val REFUSED_LINK_SCHEMES = listOf("javascript", "data", "file", "vbscript", "blob", "about", "content", "intent")

private val APP_DEEP_LINK = Regex("^([a-z][a-z0-9+.-]*)://.", RegexOption.IGNORE_CASE)

/**
 * An app deep link (`myapp://cart`) that may go to the app's own handler: a
 * `scheme://` link whose scheme is not http(s) and not a refused one. The SDK
 * never opens it itself; http(s) goes to the SDK's own safe open path, and
 * anything else is refused. The same rule on every Joryio SDK.
 */
fun isAppDeepLink(url: String?): Boolean {
    if (url == null) return false
    val scheme = APP_DEEP_LINK.find(url.trim())?.groupValues?.get(1)?.lowercase() ?: return false
    return scheme != "http" && scheme != "https" && scheme !in REFUSED_LINK_SCHEMES
}
