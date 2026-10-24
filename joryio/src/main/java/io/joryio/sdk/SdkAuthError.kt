package io.joryio.sdk

/**
 * Reason the backend rejected the SDK-authentication JWT. Mirrors the `reason`
 * field of the HTTP 401 `sdk_authentication_error` response body.
 */
enum class SdkAuthErrorReason {
    /** No `X-Joryio-Auth` header was present on the request. */
    MISSING,

    /** The JWT was malformed or its signature failed verification. */
    INVALID,

    /** The JWT's `exp` claim is in the past. */
    EXPIRED,

    /** The JWT's `sub` claim does not match the identified user. */
    SUB_MISMATCH,

    /** A `reason` value not recognized by this SDK version. */
    UNKNOWN;

    companion object {
        /** Map the backend's wire `reason` string to an enum, tolerating unknowns. */
        fun fromWire(reason: String?): SdkAuthErrorReason = when (reason) {
            "missing" -> MISSING
            "invalid" -> INVALID
            "expired" -> EXPIRED
            "sub_mismatch" -> SUB_MISMATCH
            else -> UNKNOWN
        }
    }
}

/**
 * Delivered to the handler registered via
 * [Joryio.setSdkAuthenticationErrorHandler] when an ingest request is rejected
 * with HTTP 401 `sdk_authentication_error`.
 *
 * The host app should mint a fresh JWT and call
 * [Joryio.setSdkAuthenticationToken]. If the handler does so **synchronously**,
 * the SDK retries the rejected request immediately with the new token; otherwise
 * the events stay queued and the next flush carries whatever token is set by then.
 */
data class SdkAuthError(
    val reason: SdkAuthErrorReason,
    /** The raw wire `reason` string, preserved for logging / forward-compat. */
    val rawReason: String? = null,
    /**
     * The ingest endpoint whose request was rejected, as context for the host.
     *
     * Added for parity: iOS has always carried it, so a React Native handler
     * written once received different fields depending on the platform it
     * happened to run on. Nullable because a response without a request path is
     * possible in principle, never because the platform lacks the concept.
     */
    val endpoint: String? = null,
    /**
     * The SDK has stopped trying for this request.
     *
     * True in BOTH terminal cases - no fresh token arrived, or one did and was
     * rejected as well. False on the first notification, which is the SDK
     * ASKING for a token rather than reporting that it gave up. A host that
     * signs the user out on repeated auth failure needs this to tell those
     * apart; on Android it previously could not, because the field only existed
     * on iOS and the terminal "no token arrived" case emitted nothing at all.
     */
    val refreshExhausted: Boolean = false
)
