package io.joryio.sdk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import io.joryio.sdk.SdkAuthError
import io.joryio.sdk.SdkAuthErrorReason
import io.joryio.sdk.core.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Network client for API communication
 */
internal class NetworkClient(
    private val context: Context,
    private val sdkKey: String,
    private val apiHost: String,
    private val logger: Logger,
    private val maxRetries: Int = 3,
    private val retryBackoffMs: Long = 1000,
    private val enableDebug: Boolean = false,
    enableSdkAuthentication: Boolean = false,
    sdkAuthenticationToken: String? = null,
    /**
     * Per-request timeout (connect, read and write), milliseconds. Comes from
     * `JoryioConfig.requestTimeout`, which was documented, stored and never
     * read while this client sat on a hard-coded 30 s (audit 2026-09-26, A17).
     * iOS applies its `requestTimeout` to the URLSession the same way.
     */
    requestTimeoutMs: Long = 10_000
) {
    private val api: JoryioApi
    private val okHttpClient: OkHttpClient

    /**
     * `Joryio-Android/<sdk> (<package>; Android <release>)` — an app-shaped
     * User-Agent, so we are never mistaken for a scripted HTTP client. Built
     * defensively: a Context that cannot report its package name must not stop
     * the SDK from making requests.
     */
    // The version comes from Logger.SDK_VERSION - the ONE constant the
    // backend's sdk-version-parity spec pins. Both this and the version header
    // below said "1.0.0" as literals while the SDK was 1.3.0, so the server's
    // per-version breakdown attributed every Android install to a release that
    // never shipped.
    private val userAgent: String = userAgentFor(
        packageName = runCatching { context.packageName }.getOrNull(),
        osRelease = android.os.Build.VERSION.RELEASE,
    )

    // Optional JWT-based SDK Authentication. Holds the customer-minted JWT and
    // the app's auth-error handler; read on the OkHttp thread, written from the
    // app thread. Inert unless enabled + a token is set.
    private val sdkAuthenticator = SdkAuthenticator(enableSdkAuthentication, sdkAuthenticationToken)
    private val authErrorGson = Gson()

    internal companion object {
        private const val MAX_BACKOFF_MS = 30_000L

        /** Upper bound on a server-directed wait, so a bad header cannot park the SDK for days. */
        const val MAX_RETRY_AFTER_MS = 3_600_000L

        /**
         * `Retry-After` in either form RFC 9110 allows: delta-seconds
         * (`120`) or an HTTP-date (`Wed, 21 Oct 2026 07:28:00 GMT`). Null when
         * absent or unparseable. Not clamped to the in-request backoff cap -
         * the caller decides whether to sleep or to hand the wait to its queue.
         */
        fun parseRetryAfterMs(header: String?, now: Long = System.currentTimeMillis()): Long? {
            val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            value.toLongOrNull()?.let { return (it * 1000L).coerceIn(0L, MAX_RETRY_AFTER_MS) }
            val date = try {
                java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
                    .parse(value)
            } catch (e: Exception) {
                null
            } ?: return null
            return (date.time - now).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }
        private const val SDK_AUTH_HEADER = "X-Joryio-Auth"

        /** See [requestUserId]. */
        internal fun subjectUserIdOf(json: String): String? = try {
            val root = com.google.gson.JsonParser.parseString(json) as? com.google.gson.JsonObject
            fun str(o: com.google.gson.JsonObject?, key: String): String? =
                o?.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
            str(root, "userId")
                ?: str(root, "externalId")
                ?: str(root?.get("identify") as? com.google.gson.JsonObject, "userId")
                ?: listOf("events", "impressions").firstNotNullOfOrNull { key ->
                    (root?.get(key) as? com.google.gson.JsonArray)
                        ?.firstNotNullOfOrNull { str(it as? com.google.gson.JsonObject, "userId") }
                }
        } catch (e: Exception) {
            null
        }
        private const val SDK_AUTH_ERROR = "sdk_authentication_error"
        // Cap on how many bytes of an error body we peek to detect the auth error.
        private const val AUTH_ERROR_PEEK_BYTES = 8_192L

        /** `Joryio-Android/<version> (<package>; Android <release>)`. */
        fun userAgentFor(packageName: String?, osRelease: String?): String =
            if (packageName.isNullOrBlank()) {
                "Joryio-Android/${Logger.SDK_VERSION}"
            } else {
                "Joryio-Android/${Logger.SDK_VERSION} ($packageName; Android ${osRelease ?: "?"})"
            }

        /**
         * Turn whatever the host app passed as `apiHost` into a base URL Retrofit
         * accepts.
         *
         * This used to be `"https://" + apiHost`, which assumed a BARE host.
         * Every documented example passes a full URL
         * ("https://api.example.com/api"), which produced
         * `https://https//api.example.com/api` and made Retrofit throw inside
         * initialize().
         *
         *  - bare host        -> https:// prepended
         *  - explicit http:// -> PRESERVED, so a local dev backend
         *                        (http://10.0.2.2:3000/api on the emulator) is
         *                        not silently upgraded to https and broken
         *  - no path at all   -> "/api" appended (the backend's global prefix),
         *                        the same rule web and iOS apply
         *
         * Always ends in "/" because Retrofit requires it.
         */
        fun normalizeBaseUrl(host: String): String {
            val trimmed = host.trim()
            val withScheme = when {
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
            val noSlash = withScheme.trimEnd('/')
            val path = runCatching { java.net.URI(noSlash).path ?: "" }.getOrDefault("")
            val withPrefix = if (path.isEmpty() || path == "/") "$noSlash/api" else noSlash
            return "$withPrefix/"
        }

        /**
         * The HOST ROOT of a base URL: scheme, host and port, path dropped.
         *
         * The push receipt and click routes (`/track/push/delivered/:id`,
         * `/track/push/click/:id`) are served at the root, NOT under the `/api`
         * prefix every other SDK route uses - they are shared with the web
         * service worker and with email/SMS link redirects, which carry no SDK
         * key. Building them under the base URL produced
         * `https://host/api/track/push/...`, a 404 on production: every
         * Android push read as never delivered and never opened. Shared SDK
         * contract item 10: every SDK builds these from scheme + host.
         */
        fun rootUrlOf(baseUrl: String): String {
            val url = baseUrl.toHttpUrl()
            return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        }

        /** `<root>track/push/<kind>/<trackingId>`, the id encoded as one path segment. */
        fun pushTrackingUrl(baseUrl: String, kind: String, trackingId: String): String =
            rootUrlOf(baseUrl).toHttpUrl().newBuilder()
                .addPathSegment("track")
                .addPathSegment("push")
                .addPathSegment(kind)
                .addPathSegment(trackingId)
                .build()
                .toString()
    }

    /** Retrofit-ready base URL derived from `apiHost` — see normalizeBaseUrl. */

    private val normalizedBaseUrl: String get() = normalizeBaseUrl(apiHost)

    /** The base URL actually in use, for diagnostics. */
    val currentApiEndpoint: String get() = normalizedBaseUrl

    /**
     * The most recent transport failure, or null if the last call succeeded.
     *
     * Mirrors iOS's `lastTransportError`. It exists because "initialized" and
     * "the server accepts us" are DIFFERENT claims, and conflating them is what
     * makes a 401 loop look healthy: the SDK reports itself up while every
     * request is rejected. Surfaced through Joryio.getDiagnostics().
     *
     * Written from executeWithRetry, the single chokepoint every API call goes
     * through, so no call site has to remember to record anything.
     */
    @Volatile
    var lastTransportError: JoryioTransportError? = null
        private set


    init {
        // NOT GsonBuilder().setDateFormat("...'Z'"): that formats in the
        // device's DEFAULT time zone and then labels the result Z, so every
        // timestamp on the wire was off by the device's UTC offset (three
        // hours in Israel, nine in Japan). See WireJson.
        val gson = WireJson.gson

        // A non-positive timeout would make OkHttp wait forever; fall back to
        // the config default rather than hand an integrator an unbounded call.
        val timeoutMs = if (requestTimeoutMs > 0) requestTimeoutMs else 10_000L
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(createAuthInterceptor())
            // Added AFTER the SDK-key interceptor so this (inner) interceptor's
            // received request already carries the base headers; its one-shot
            // retry rebuilds from that request and therefore keeps them.
            .addInterceptor(createSdkAuthInterceptor())
            .apply {
                if (enableDebug) {
                    addInterceptor(createLoggingInterceptor())
                }
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        api = retrofit.create(JoryioApi::class.java)
        logger.debug("NetworkClient initialized with base URL: $normalizedBaseUrl")
    }

    /**
     * Create auth interceptor for SDK key
     */
    private fun createAuthInterceptor(): Interceptor {
        return Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $sdkKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Joryio-SDK", "android")
                .addHeader("X-Joryio-SDK-Version", Logger.SDK_VERSION)
                // Identify ourselves. Without this OkHttp sends its default
                // `okhttp/4.x`, which server-side bot heuristics classify as an
                // automated HTTP client — so /v1/in-app/sync answered
                // `{success:true, campaigns:[]}` and every event was tagged
                // $is_bot (and filtered out of analytics). iOS never hit this
                // because URLSession sends an app-shaped UA. Set here as well as
                // fixed server-side, so the SDK is also correct against an older
                // backend.
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }
    }

    // MARK: - SDK Authentication

    /** Set/replace the customer-minted JWT (thread-safe; call from any thread). */
    fun setSdkAuthenticationToken(token: String) {
        sdkAuthenticator.setToken(token)
    }

    /** Forget the JWT - see [SdkAuthenticator.clearToken]. */
    fun clearSdkAuthenticationToken() {
        sdkAuthenticator.clearToken()
    }

    /** Logout: keep the JWT for [outgoingUserId]'s leftovers only - see [SdkAuthenticator.retireToken]. */
    fun retireSdkAuthenticationToken(outgoingUserId: String?, signedInUserId: String? = null) {
        sdkAuthenticator.retireToken(outgoingUserId, signedInUserId)
    }

    /** wipeData(): forget the next user's standby token - see [SdkAuthenticator.dropStandby]. */
    fun dropStandbySdkAuthenticationTokens(keep: Set<String> = emptySet(), keepToken: String? = null) {
        sdkAuthenticator.dropStandby(keep, keepToken)
    }

    /** The session's SDK-auth token, or null. */
    fun currentSdkAuthenticationToken(): String? = sdkAuthenticator.currentToken()

    /** A held identify(): see [SdkAuthenticator.unflagStandby]. */
    fun unflagStandbySdkAuthenticationToken(userId: String) {
        sdkAuthenticator.unflagStandby(userId)
    }

    /** identify() of another user without reset(): see [SdkAuthenticator.retireTokenOnSwitch]. */
    fun retireSdkAuthenticationTokenOnSwitch(outgoingUserId: String, incomingUserId: String) {
        sdkAuthenticator.retireTokenOnSwitch(outgoingUserId, incomingUserId)
    }

    /** identify(): adopt a token kept for [userId] - see [SdkAuthenticator.adoptRetired]. */
    fun adoptRetiredSdkAuthenticationToken(userId: String) {
        sdkAuthenticator.adoptRetired(userId)
    }

    /** Persist retired tokens in [store] and restore an earlier process's (owner decision D-a). */
    internal fun attachRetiredTokenStore(store: SdkAuthenticator.Store) {
        sdkAuthenticator.attachStore(store)
    }

    /** Forget retired tokens of users with nothing left queued - see [SdkAuthenticator.prune]. */
    internal fun pruneRetiredTokens(activeUserIds: Set<String>) {
        sdkAuthenticator.prune(activeUserIds)
    }

    internal fun retiredTokenUserIds(): Set<String> = sdkAuthenticator.retiredUserIds()

    /** Register the handler invoked on a 401 `sdk_authentication_error`. */
    fun setSdkAuthenticationErrorHandler(handler: (SdkAuthError) -> Unit) {
        sdkAuthenticator.setErrorHandler(handler)
    }

    /**
     * The signed-in user right now. An SDK-Authentication refusal of a
     * request made for anyone else is NOT reported to the host's handler
     * (contract item 39; scan 4, Android finding M5): it is a leftover of a
     * user who logged out, which the queue and the outbox drop, and a host
     * that signs out on repeated auth failures must not sign out the CURRENT
     * user for it. Wired by the facade.
     */
    @Volatile
    var currentUserId: () -> String? = { null }

    /**
     * Whether a request's user is the person on this device now - the
     * identified user, or one an identify() held while tracking was refused
     * (owner decision D-b; contract v6 63). Only a refusal for anyone else
     * is kept from the handler. Wired by the facade.
     */
    @Volatile
    var isCurrentUser: (String) -> Boolean = { it == currentUserId() }

    /**
     * The userId a request is made for - the same fields the server binds the
     * token to: the body's `userId` or `externalId`, a batch's `identify`, or
     * the first event/impression naming one (one user per batch). Null for an
     * anonymous request or a body that is not a JSON object.
     */
    internal fun requestUserId(request: okhttp3.Request): String? = try {
        val body = request.body
        if (body == null) {
            // A bodyless request (an in-app GET) is about whoever is signed in.
            currentUserId()
        } else {
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            subjectUserIdOf(buffer.readUtf8())
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Interceptor implementing SDK Authentication.
     *
     * 1. When enabled + a token is set, attach `X-Joryio-Auth: <jwt>` to the request.
     * 2. If the response is HTTP 401 with body `{ error: "sdk_authentication_error",
     *    reason: ... }`, notify the app handler (its chance to mint a fresh JWT) and,
     *    if the token was updated **synchronously**, retry the SAME request exactly
     *    ONCE with the new token. Never loop.
     *
     * Batch integrity: on auth failure we do NOT drop or duplicate the batch. This
     * interceptor only ever returns the original 401 (or the single retry's result)
     * to [executeWithRetry], which treats 401 as non-retryable and surfaces an
     * error — so [QueueManager] leaves the events queued and the next flush carries
     * whatever token is set by then. An async refresh (e.g. the RN bridge, where the
     * handler round-trips to JS) therefore heals on the following flush rather than
     * inline; a synchronous native handler heals inline via the one-shot retry.
     */
    private fun createSdkAuthInterceptor(): Interceptor {
        return Interceptor { chain ->
            val original = chain.request()

            // Pure pass-through when the feature isn't in use.
            if (!sdkAuthenticator.isFeatureOn) {
                return@Interceptor chain.proceed(original)
            }

            // Routed by the request's user (contract v7 72): a logged-out
            // user's leftovers carry THEIR token (contract item 39), and a
            // token whose `sub` names someone else is never attached. The body
            // is only read while there is a token to route.
            val token = if (sdkAuthenticator.hasRetiredTokens || sdkAuthenticator.currentToken() != null) {
                sdkAuthenticator.tokenFor(requestUserId(original), currentUserId())
            } else {
                null
            }

            // Feature on but NO token yet (scan 3, Android finding 3): the
            // request still goes out - anonymous writes are accepted, SDK
            // auth verifies identified users only (contract item 13) - and a 401 that comes back is
            // reported to the handler with reason MISSING, so the host can
            // supply the token. This used to be a pass-through: the handler
            // was never called and an identified user's writes were refused
            // in silence until something else happened to set a token.
            val firstRequest = if (token != null) {
                original.newBuilder().header(SDK_AUTH_HEADER, token).build()
            } else {
                original
            }
            val firstResponse = chain.proceed(firstRequest)

            // Only react to the backend's dedicated auth rejection. Any other
            // status (including a plain 401 without this body) passes straight
            // through to the existing retry/backoff logic.
            val authError = parseSdkAuthError(firstResponse) ?: return@Interceptor firstResponse

            // A request for a user who has since logged out: not the current
            // user's problem, and no token for them is coming. Returned as is
            // - its queue drops it - without asking the host (scan 4, M5).
            val subject = requestUserId(original)
            if (subject != null && !isCurrentUser(subject)) {
                logger.warn(
                    "SDK authentication refused a request of a user who is no longer signed in " +
                        "(${authError.rawReason ?: authError.reason}); not reported to the error handler",
                )
                return@Interceptor firstResponse
            }

            logger.warn("SDK authentication rejected: ${authError.rawReason ?: authError.reason}")

            // Ask the host app for a fresh token. A native handler may call
            // setSdkAuthenticationToken() synchronously here.
            sdkAuthenticator.notifyError(authError)

            // The refreshed token, routed like the first: never one whose
            // `sub` names another user than this request's.
            val refreshed = sdkAuthenticator.tokenFor(subject, currentUserId())
            if (refreshed == null || refreshed == token) {
                // No (new) token available synchronously → stop. Events stay queued.
                //
                // Say so. This branch used to return in silence, so a host that
                // set no token could not distinguish "the SDK is asking" from
                // "the SDK has given up", and never learned the request was
                // abandoned. iOS has always emitted this second, terminal
                // notification; Android emitted nothing.
                sdkAuthenticator.notifyError(authError.copy(refreshExhausted = true))
                return@Interceptor firstResponse
            }

            // Retry exactly once with the refreshed token. Close the first
            // response body to release its connection.
            firstResponse.close()
            val retryRequest = original.newBuilder()
                .header(SDK_AUTH_HEADER, refreshed)
                .build()
            val retryResponse = chain.proceed(retryRequest)

            // If it STILL fails auth, surface the terminal error and stop — no loop.
            parseSdkAuthError(retryResponse)?.let {
                logger.error("SDK authentication still failing after refresh: ${it.rawReason ?: it.reason}")
                // Terminal: a fresh token was supplied and rejected too.
                sdkAuthenticator.notifyError(it.copy(refreshExhausted = true))
            }

            retryResponse
        }
    }

    /**
     * Return an [SdkAuthError] iff [response] is a 401 whose body is the backend's
     * `sdk_authentication_error`. Uses `peekBody` so the real body stays readable
     * downstream. Returns null (leaving the response untouched) on any other case.
     */
    private fun parseSdkAuthError(response: okhttp3.Response): SdkAuthError? {
        if (response.code != 401) return null

        val bodyStr = try {
            response.peekBody(AUTH_ERROR_PEEK_BYTES).string()
        } catch (e: Exception) {
            return null
        }
        if (bodyStr.isBlank()) return null

        return try {
            val body = authErrorGson.fromJson(bodyStr, SdkAuthErrorBody::class.java)
            if (body?.error != SDK_AUTH_ERROR) {
                null
            } else {
                SdkAuthError(
                    reason = SdkAuthErrorReason.fromWire(body.reason),
                    rawReason = body.reason,
                    endpoint = response.request.url.encodedPath
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The `reason` of a 401 `sdk_authentication_error` body, or null for any other body. */
    private fun sdkAuthReasonOf(body: String?): SdkAuthErrorReason? {
        if (body.isNullOrBlank()) return null
        return try {
            val parsed = authErrorGson.fromJson(body, SdkAuthErrorBody::class.java)
            if (parsed?.error == SDK_AUTH_ERROR) SdkAuthErrorReason.fromWire(parsed.reason) else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create logging interceptor for debugging
     */
    private fun createLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            logger.debug("HTTP: $message")
        }.apply {
            // HEADERS (not BODY): body logging would write request bodies —
            // which carry PII (email/phone/name in identify/attributes) — to
            // Logcat. And redact the two bearer credentials so a debug build
            // can never leak a replayable token to logs.
            level = HttpLoggingInterceptor.Level.HEADERS
            redactHeader("Authorization")
            redactHeader("X-Joryio-Auth")
        }
    }

    /**
     * Check if network is available
     */
    fun isNetworkAvailable(): Boolean {
        // `as?`, not `as`: getSystemService returns null on a restricted or
        // not-yet-ready Context (and on some OEM builds), and the unchecked cast
        // then threw a NullPointerException out of this method. It is called
        // before EVERY request, so that would surface as a crash on an arbitrary
        // caller's thread. Assume "connected" when we cannot tell — the request
        // itself will fail and be retried, which is a far better outcome than
        // taking the host app down to answer a question we only use as a hint.
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Execute API call with retry logic
     */
    private suspend fun <T> executeWithRetry(
        operation: String,
        call: suspend () -> Response<T>
    ): NetworkResult<T> {
        if (!isNetworkAvailable()) {
            logger.warn("$operation failed: No network connection")
            lastTransportError = JoryioTransportError(
                message = "No network connection",
                isUnauthorized = false,
                isRetryable = true,
            )
            return NetworkResult.Error("No network connection", isRetryable = true)
        }

        var lastError: Exception? = null
        var attempt = 0

        while (attempt <= maxRetries) {
            // Server-directed delay (429 Retry-After) overrides our own backoff.
            var retryAfterMs: Long? = null
            try {
                logger.debug("$operation - Attempt ${attempt + 1}/${maxRetries + 1}")

                val response = call()

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        logger.debug("$operation succeeded")
                        // A success CLEARS the last error: diagnostics should say
                        // "healthy now", not "failed once, three hours ago".
                        lastTransportError = null
                        return NetworkResult.Success(body)
                    } else {
                        logger.error("$operation failed: Empty response body")
                        return NetworkResult.Error("Empty response body", isRetryable = false)
                    }
                } else {
                    val code = response.code()
                    val errorMessage = response.errorBody()?.string() ?: "Unknown error"
                    logger.warn("$operation failed: HTTP $code - $errorMessage")

                    // Only transient/rate-limit statuses are retryable. Never retry
                    // 401/403/4xx (bad key / bad request) — those won't self-heal and
                    // would just burn battery and hammer the backend.
                    val isRetryable = code in listOf(408, 429, 500, 502, 503, 504)

                    // An SDK-authentication rejection says WHY (contract item
                    // 30): a write for a user who is no longer the current one
                    // that comes back `sub_mismatch` / `missing` is dropped by
                    // its queue instead of blocking the next person's writes.
                    val authReason = if (code == 401) sdkAuthReasonOf(errorMessage) else null

                    // Any status may carry Retry-After (429 and 503 usually
                    // do). It used to be read for 429 only and clamped to
                    // 30 s, so a server asking for five minutes was retried
                    // after thirty seconds - three times in a row.
                    val serverWait = parseRetryAfterMs(response.headers()["Retry-After"])
                    retryAfterMs = serverWait

                    // A wait longer than one in-request backoff is not slept
                    // here: the caller (the event queue, the write outbox)
                    // owns long waits and keeps the work meanwhile.
                    val waitTooLong = serverWait != null && serverWait > MAX_BACKOFF_MS

                    if (!isRetryable || attempt >= maxRetries || waitTooLong) {
                        lastTransportError = JoryioTransportError(
                            message = "HTTP $code: $errorMessage",
                            isUnauthorized = code == 401 || code == 403,
                            isRetryable = isRetryable,
                        )
                        // The status travels with the error: the event queue
                        // decides between "retry later", "split the batch" and
                        // "stop for this session" on it (contract item 3).
                        return NetworkResult.Error(
                            "HTTP $code: $errorMessage",
                            isRetryable = isRetryable,
                            code = code,
                            retryAfterMs = serverWait,
                            authReason = authReason,
                        )
                    }
                }
            } catch (e: Exception) {
                lastError = e
                logger.warn("$operation failed: ${e.message}")

                if (attempt >= maxRetries) {
                    lastTransportError = JoryioTransportError(
                        message = e.message ?: "Network request failed",
                        isUnauthorized = false,
                        // Exceptions here are transport-level (DNS, timeout,
                        // connection reset): the next attempt may succeed.
                        isRetryable = true,
                    )
                    return NetworkResult.Error(
                        e.message ?: "Network request failed",
                        isRetryable = true
                    )
                }
            }

            // Backoff before the next attempt: honor Retry-After when the server
            // sent one, otherwise exponential backoff off config.retryBackoffMs with
            // ±25% jitter and a cap to avoid thundering-herd retries.
            if (attempt < maxRetries) {
                val delayMs = retryAfterMs ?: computeBackoffMs(attempt)
                logger.debug("$operation - Retrying in ${delayMs}ms")
                delay(delayMs)
            }

            attempt++
        }

        return NetworkResult.Error(
            lastError?.message ?: "Max retries exceeded",
            isRetryable = true
        )
    }

    /**
     * Exponential backoff off [retryBackoffMs] with ±25% jitter, capped.
     */
    private fun computeBackoffMs(attempt: Int): Long {
        val base = (retryBackoffMs * (1L shl attempt)).coerceAtMost(MAX_BACKOFF_MS)
        val jitter = (base * 0.25).toLong()
        val low = (base - jitter).coerceAtLeast(0L)
        val high = base + jitter
        return if (high > low) Random.nextLong(low, high + 1) else base
    }

    // MARK: - Event Tracking

    suspend fun trackBatch(batch: io.joryio.sdk.models.EventBatch): NetworkResult<BatchResponse> {
        return executeWithRetry("trackBatch") {
            api.trackBatch(batch)
        }
    }

    suspend fun trackEvent(event: io.joryio.sdk.models.Event): NetworkResult<EventResponse> {
        return executeWithRetry("trackEvent") {
            api.trackEvent(event)
        }
    }

    // MARK: - Identity

    suspend fun identify(request: IdentifyRequest): NetworkResult<IdentifyResponse> {
        return executeWithRetry("identify") {
            api.identify(request)
        }
    }

    suspend fun addAlias(request: AddAliasRequest): NetworkResult<AliasResponse> {
        return executeWithRetry("addAlias") {
            api.addAlias(request)
        }
    }

    suspend fun setAttributes(request: SetAttributesRequest): NetworkResult<SetAttributesResponse> {
        return executeWithRetry("setAttributes") {
            api.setAttributes(request)
        }
    }

    /**
     * POST a body that was serialised earlier, byte for byte - the write
     * outbox replays identify / addAlias / attribute ops / subscriptions this
     * way, so a retry sends exactly what the call produced.
     */
    suspend fun postJson(path: String, json: String): NetworkResult<GenericResponse> {
        val body = json.toByteArray(Charsets.UTF_8)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return executeWithRetry("POST $path") {
            api.postJson(path, body)
        }
    }

    // MARK: - Subscriptions (marketing, not tracking consent)

    suspend fun updateChannelSubscription(
        request: ChannelSubscriptionRequest
    ): NetworkResult<SubscriptionResponse> {
        return executeWithRetry("updateChannelSubscription") {
            api.updateChannelSubscription(request)
        }
    }

    suspend fun updateSubscriptionGroup(
        request: SubscriptionGroupRequest
    ): NetworkResult<SubscriptionResponse> {
        return executeWithRetry("updateSubscriptionGroup") {
            api.updateSubscriptionGroup(request)
        }
    }

    // MARK: - In-App Messaging

    suspend fun syncInAppMessages(request: io.joryio.sdk.models.SessionSyncRequest): NetworkResult<io.joryio.sdk.models.SessionSyncResponse> {
        return executeWithRetry("syncInAppMessages") {
            api.syncInAppMessages(request)
        }
    }

    /** Display-time re-check for a `reevaluateBeforeDisplay` campaign. */
    suspend fun resolveInAppCampaign(
        request: io.joryio.sdk.models.ResolveRequest,
    ): NetworkResult<io.joryio.sdk.models.ResolveResponse> {
        return executeWithRetry("resolveInAppCampaign") {
            api.resolveInAppCampaign(request)
        }
    }

    suspend fun trackImpression(request: io.joryio.sdk.models.TrackImpressionRequest): NetworkResult<ImpressionResponse> {
        return executeWithRetry("trackImpression") {
            api.trackImpression(request)
        }
    }

    suspend fun submitInAppForm(request: io.joryio.sdk.models.InAppFormSubmitRequest): NetworkResult<io.joryio.sdk.models.InAppFormSubmitResponse> {
        return executeWithRetry("submitInAppForm") {
            api.submitInAppForm(request)
        }
    }

    // MARK: - Push Notifications

    suspend fun registerPushToken(request: PushTokenRequest): NetworkResult<PushTokenResponse> {
        return executeWithRetry("registerPushToken") {
            api.registerPushToken(request)
        }
    }

    suspend fun unregisterPushToken(request: PushTokenRequest): NetworkResult<PushTokenResponse> {
        return executeWithRetry("unregisterPushToken") {
            api.unregisterPushToken(request)
        }
    }

    /**
     * Report that a push arrived on this device.
     *
     * Retried like any other call: a receipt lost to a flaky moment of network
     * is a delivery that silently reads as undelivered, and the backend
     * deduplicates by tracking id, so a retry that actually succeeded twice
     * costs nothing.
     */
    suspend fun reportPushDelivered(
        trackingId: String,
    ): NetworkResult<PushTokenResponse> {
        // Absolute URL at the HOST ROOT - see rootUrlOf.
        val url = pushTrackingUrl(normalizedBaseUrl, "delivered", trackingId)
        return executeWithRetry("reportPushDelivered") {
            api.reportPushDelivered(url)
        }
    }

    suspend fun trackPushClick(trackingId: String): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        // okHttpClient.execute() is a BLOCKING call — it must never run on the main
        // thread (callers dispatch on Dispatchers.Main), hence the withContext(IO).
        try {
            // At the HOST ROOT, not under /api - see rootUrlOf. This was
            // `${normalizedBaseUrl}track/push/click/...`, which is
            // /api/track/push/click on production: a 404 for every tap.
            val url = pushTrackingUrl(normalizedBaseUrl, "click", trackingId)
            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                logger.debug("Push click tracked successfully")
                NetworkResult.Success(Unit)
            } else {
                logger.error("Failed to track push click: ${response.code}")
                NetworkResult.Error("HTTP ${response.code}", isRetryable = response.code in 500..599)
            }
        } catch (e: Exception) {
            logger.error("Failed to track push click: ${e.message}", e)
            NetworkResult.Error(e.message ?: "Unknown error", isRetryable = true)
        }
    }
}

/**
 * Network result wrapper
 */
internal sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error(
        val message: String,
        val isRetryable: Boolean,
        /** HTTP status, or null when the request never got an answer (offline, timeout). */
        val code: Int? = null,
        /** Server-directed wait from `Retry-After`, when it sent one. */
        val retryAfterMs: Long? = null,
        /** For a 401 `sdk_authentication_error`: the reason the server gave. */
        val authReason: SdkAuthErrorReason? = null,
    ) : NetworkResult<Nothing>()
}

/**
 * Shape of the HTTP 401 SDK-authentication rejection body. Extra fields are
 * ignored by Gson; both properties are nullable to tolerate partial/legacy bodies.
 */
internal data class SdkAuthErrorBody(
    @com.google.gson.annotations.SerializedName("error") val error: String? = null,
    @com.google.gson.annotations.SerializedName("reason") val reason: String? = null
)

/**
 * A transport failure the host app can display. Deliberately narrow: a message,
 * whether it was an auth rejection, and when.
 *
 * `message` carries no credentials by construction — the SDK key travels as the
 * `Authorization` / `X-App-Key` HEADERS, never in a URL, so neither an HTTP
 * error body nor an exception message can contain it.
 */
data class JoryioTransportError(
    val message: String,
    val isUnauthorized: Boolean,
    val at: Long = System.currentTimeMillis(),
    /** Transient (offline, DNS, 5xx, 429): the next call may succeed. */
    val isRetryable: Boolean = false,
)
