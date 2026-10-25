package io.joryio.sdk

/**
 * Configuration for Joryio SDK
 */
// @JvmOverloads: Java callers (the Unity bridge) call this constructor
// positionally. With it, a field added at the END keeps every shorter
// positional call compiling - the 19-argument call that predates
// notificationChannelName still works. New fields go at the end.
data class JoryioConfig @JvmOverloads constructor(
    // User Identification
    val userId: String? = null,
    val anonymousId: String? = null,

    // Batching & Performance
    val batchSize: Int = 50,
    val flushInterval: Long = 5000, // milliseconds
    val sendImmediately: Boolean = false,
    val maxQueueSize: Int = 1000,

    // Session Management
    val sessionTimeout: Long = 1800000, // 30 minutes in milliseconds
    val trackSessionStart: Boolean = true,

    // Network & Retry
    val maxRetries: Int = 3,
    val retryBackoffMs: Long = 1000,
    val requestTimeout: Long = 10000, // milliseconds

    // SDK Authentication (optional, JWT-based) — fully opt-in.
    // When enabled, the host app supplies a customer-minted JWT (here at init
    // and/or at runtime via Joryio.setSdkAuthenticationToken(...)), and the SDK
    // attaches it as the `X-Joryio-Auth` header on every ingest request. On an
    // HTTP 401 `sdk_authentication_error` the SDK asks the app for a fresh token
    // (see Joryio.setSdkAuthenticationErrorHandler) and retries once. Disabled by
    // default → behaviour is completely unchanged unless opted in.
    val enableSdkAuthentication: Boolean = false,
    val sdkAuthenticationToken: String? = null,


    /**
     * Allow in-app messages authored as HTML, shown in a JavaScript-enabled
     * WebView inside your app.
     *
     * OFF BY DEFAULT. The name carries both halves on purpose: `Html…
     * InAppMessages` so the option is findable by someone who wants the
     * feature, and `Js` so nobody enables it thinking HTML is just markup.
     * The reason: an HTML message is dashboard-authored markup shown in a
     * WebView that can execute script, so a maintainer has to opt in.
     *
     * What actually happens: the server keeps the markup as written (only
     * `<base>` and meta refresh are removed), so `<script>` and `on*`
     * handlers reach the device - and do NOT run, because the document
     * carries a fresh nonce Content-Security-Policy and only the SDK's
     * bootstrap has the nonce. Buttons and forms work through that bridge
     * (`data-joryio-action`, `data-jry-form`). The residual risk is a gap in
     * the CSP - and because the document is authored server-side, a
     * compromised marketing account would then mean code running in every
     * user's session, not merely data exposure in a dashboard. Regulated apps
     * frequently prohibit a web view outright.
     *
     * Leaving this off does NOT disable in-app messaging. Native messages still
     * display; they are data (title, body, image, buttons) rendered by this
     * SDK's own views, with no interpreter involved. Only HTML campaigns are
     * skipped.
     */
    val allowHtmlJsInAppMessages: Boolean = false,

    // Privacy & GDPR
    //
    // `respectDoNotTrack` is GONE. It was accepted, stored and never read
    // (audit 2026-09-26, A17), and it could not have been: Do Not Track is a
    // BROWSER signal (`navigator.doNotTrack`) with no Android equivalent - the
    // "limit ad tracking" flag is an advertising-id setting that needs Play
    // Services and says nothing about first-party analytics. Web keeps the
    // option because the browser has the concept; a mobile option that can
    // never do anything reads as a promise, which is worse than none.
    /**
     * Start opted out (PERSISTED, like calling optOut()). Only optIn() lifts
     * it - never a consent value.
     */
    val optOut: Boolean = false,
    /**
     * Tracking consent from your consent banner, or null for "not set" - the
     * DEFAULT (contract item 24, consent model A).
     *
     * - null: a stored denial from an earlier launch stays in force;
     *   otherwise the SDK behaves as granted (an app with no banner).
     * - GRANTED: explicit - lifts a stored denial.
     * - DENIED: collect nothing, discard what is held; PERSISTED.
     * - PENDING: hold everything on the device for THIS launch; never
     *   overrides a stored denial and is not stored itself.
     *
     * The default used to be GRANTED, which could not tell "the app has no
     * banner" from "the user just accepted" and so would have lifted every
     * stored denial on the next launch.
     */
    val trackingConsent: TrackingConsent? = null,

    /**
     * Ask for notification permission automatically, at the first opportunity
     * after initialize().
     *
     * **Off by default, and that default is the recommendation.** The system
     * prompt can be shown exactly once; a "no" is permanent short of a trip to
     * Settings. Firing it at launch - before the user has any reason to want
     * notifications from you - is how apps burn their single ask. A soft prompt
     * first (the app's own screen explaining why) is the common pattern among
     * push SDKs, and the way to use this flag.
     *
     * Turn it on only when notifications ARE the product (a messaging or alerts
     * app, where a user who declines has no reason to be there). Otherwise show
     * a primer - see the `request_push_permission` in-app button action - and
     * call requestPushPermission() when the user says yes.
     *
     * Requires an Activity, so it takes effect at the first foreground rather
     * than inside initialize() itself.
     */
    val requestPushPermissionAtLaunch: Boolean = false,

    // Debugging
    val enableDebug: Boolean = false,
    /**
     * Explicit log level. `null` means "not specified" — see [effectiveLogLevel],
     * which then derives it from [enableDebug].
     *
     * This used to default to [LogLevel.ERROR], which made `enableDebug = true`
     * INERT on Android: Logger gates every call on BOTH `enabled` and the level,
     * so turning debugging on still suppressed every debug/info/warn line and the
     * SDK stayed completely silent. iOS has always derived the level from the
     * flag (`enableDebug ? .debug : .error`); Android never got that line, so the
     * one switch an integrator reaches for did nothing on one of the two
     * platforms. Nullable rather than defaulting to DEBUG so that an explicit
     * `logLevel = LogLevel.ERROR` is still honoured.
     */
    val logLevel: LogLevel? = null,

    /**
     * The notification channel's name, as the USER sees it in system Settings
     * -> Notifications (Android 8+). Pass it localised, e.g.
     * `context.getString(R.string.notifications_channel)`. Default
     * "Notifications". Used to be a hard-coded "Joryio Notifications" - the
     * SDK's name, in English, inside every customer's app.
     */
    val notificationChannelName: String? = "Notifications",
) {
    /**
     * The level the Logger actually runs at: an explicit [logLevel] if given,
     * otherwise derived from [enableDebug]. Matches iOS.
     */
    val effectiveLogLevel: LogLevel
        get() = logLevel ?: if (enableDebug) LogLevel.DEBUG else LogLevel.ERROR
}

/**
 * Tracking consent (shared SDK contract items 4 and 24) - the app's consent
 * banner. INDEPENDENT of the person's own opt-out switch (optOut / optIn):
 * the SDK collects only when consent is GRANTED and the person has not opted
 * out. Any "no" wins.
 *
 * - [GRANTED]: normal (unless opted out).
 * - [DENIED]: collect nothing; anything held is discarded. PERSISTED across
 *   restarts, lifted only by an explicit `updateConsent(GRANTED)` or an
 *   explicit `trackingConsent = GRANTED` in the config. Does NOT opt out.
 * - [PENDING]: events and attributes are kept ON THE DEVICE (bounded by
 *   maxQueueSize) and nothing is sent - no in-app sync, no push registration -
 *   until `updateConsent(GRANTED)` releases them, or `updateConsent(DENIED)`
 *   discards them. This launch only; never overrides a stored DENIED.
 */
enum class TrackingConsent {
    GRANTED,
    DENIED,
    PENDING
}

enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
