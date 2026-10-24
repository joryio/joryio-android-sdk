# Joryio Android SDK

The Joryio SDK for Android apps: identify users, track events, receive push notifications and display in-app messages, all through your Joryio workspace.

## Features

- **Event tracking** with batching (50 events or 5 seconds by default), an offline Room (SQLite) queue that keeps events until the server takes them, and retry with exponential backoff
- **Identity** - anonymous ID out of the box, `identify`, labelled aliases (`addAlias`), `reset` on logout; identifiers encrypted at rest with a Keystore-backed key
- **User attributes** - set, increment and unset
- **Push notifications** over Firebase Cloud Messaging (FCM), with delivery receipts, click tracking and rich images
- **In-app messages** - native (Android views) and HTML, rendered by the optional `joryio-android-ui` artifact or by your own UI
- **E-commerce helpers** that send the standard event names Joryio reports on
- **Subscription preferences** per channel and subscription group
- **Privacy controls** - opt-out, tracking consent and on-device data wipe
- **SDK authentication** (optional) - a customer-minted JWT on every ingest request

## Requirements

- Android 6.0 (API level 23) or higher
- Java 17 toolchain (Android Gradle Plugin 8). The SDK's classes are Java 11 bytecode, so a JDK 11 build (Unity 2022.3) compiles against them too
- Kotlin 1.9 or newer
- A Joryio workspace and an **Android SDK key** (`jry_sdk_android_...`) from **Settings → Apps** in the dashboard. A key minted for another platform (`jry_sdk_ios_...`, `jry_sdk_web_...`, a store) is refused with an error and the SDK sends nothing
- For push: Firebase Cloud Messaging in your app (`com.google.firebase:firebase-messaging`)

## Installation

The SDK is published to Maven Central as two artifacts:

| Artifact | Contains |
|----------|----------|
| `io.joryio:joryio-android-ui` | Everything, including in-app message display. Pulls `joryio-android` at the same version |
| `io.joryio:joryio-android` | Tracking, identity, push and in-app sync only - no UI, no WebView |

Declare **one** of them, never both. Make sure `mavenCentral()` is in your repositories.

Kotlin DSL (`build.gradle.kts`):

```kotlin
dependencies {
    // Everything, including in-app message display. Start here.
    implementation("io.joryio:joryio-android-ui:1.3.0")

    // Or tracking/identity/push only - no in-app rendering, no WebView:
    // implementation("io.joryio:joryio-android:1.3.0")
}
```

Groovy (`build.gradle`):

```groovy
dependencies {
    implementation 'io.joryio:joryio-android-ui:1.3.0'
}
```

The SDK's own dependencies (Kotlin coroutines, AndroidX, Retrofit/OkHttp, Gson, Room, `androidx.security:security-crypto`) come in transitively. `firebase-messaging` is **not** included: add it yourself if you use push.

## Quick start

### 1. Initialize

In your `Application` class:

```kotlin
import io.joryio.sdk.Joryio
import io.joryio.sdk.JoryioConfig

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Does the setup off the main thread; calls made before it is ready
        // are buffered and replayed in order.
        Joryio.initializeAsync(
            context = this,
            sdkKey = "jry_sdk_android_YOUR_SDK_KEY",
            apiHost = "api-eu1.joryio.com",   // your workspace's API host
            config = JoryioConfig(enableDebug = BuildConfig.DEBUG),
        )
    }
}
```

`Joryio.initialize(...)` does the same synchronously and returns the instance, at the cost of Keystore setup on the calling thread. `apiHost` accepts a bare host, a host with scheme, or the full base URL ending in `/api`. Use the API host shown for your workspace in the dashboard.

If you initialize after your first screen is already showing (for example from an `Activity` rather than your `Application`), pass that `Activity` as `context`: the SDK then has a screen for in-app messages and the permission prompt straight away. Only the application context is kept. With `initializeAsync()` from `Application.onCreate()` you do not need to: an `Activity` that opened while the SDK was starting is picked up, including the notification tap that opened it.

Calling `initialize` a second time is ignored (with an error in the log if the settings differ).

### 2. Track events

```kotlin
Joryio.track("Button Tapped")

Joryio.track("Product Viewed", mapOf(
    "product_id" to "abc123",
    "price" to 99.99,
    "category" to "Electronics"
))

Joryio.trackScreen("ProductDetail", mapOf("product_id" to "abc123"))
```

### 3. Identify users

```kotlin
Joryio.identify("user-123")
Joryio.getInstance().setAttributes(mapOf(
    "email" to "user@example.com",
    "name" to "Jane Doe",
    "plan" to "premium"
))

Joryio.setAttribute("language", "en")
Joryio.incrementAttribute("lifetime_value", 99.99)
Joryio.unsetAttribute("temporary_flag")

// Attach another identifier of yours (lowercase label). Works before
// identify() too: it lands on the anonymous profile and follows it at login.
Joryio.addAlias("loyalty_id", "L-12345")

// On logout: forgets the user and starts a NEW anonymous ID, so the next
// person on this device is not recorded as the previous one
Joryio.reset()
```

`identify` is how an anonymous visitor becomes a known user - there is no separate `alias(userId)` call.

The calls on `Joryio` itself (`track`, `trackScreen`, `identify`, `addAlias`, `reset`, `setAttribute`, `setAttributes`, `incrementAttribute`, `unsetAttribute`, `flush`, `optIn`, `optOut`, subscriptions, `unregisterPush` and SDK authentication) are safe before initialization finishes - they queue (up to 1000 calls; when full, the oldest call that is not an unsubscribe, a group leave or `unregisterPush` is dropped first) and run in order once it does, with `initialize` as well as `initializeAsync`. `optOut`, `optIn`, `updateConsent` and `wipeData` made before initialization are applied by `initialize()` itself, before anything is collected or sent, and are kept on the device until then. An event, attribute write, alias or subscribe queued that way while the person is opted out or consent is denied (decided before initialization or stored by an earlier launch) is dropped at once, so a later `optIn()` or grant never sends it; unsubscribes, `identify` and `reset` are kept. An `optOut()` or `updateConsent(DENIED)` made before `initialize()` also drops the events, attribute writes, aliases and subscribes queued before it, as it does after initialization, so a later `optIn()` or grant before `initialize()` never sends them. A token set before initialization, ahead of any `identify`, `reset` or `wipeData`, is applied by `initialize()` itself, before its first request. Set the token and the handler with the static `Joryio.setSdkAuthenticationToken()` and `Joryio.setSdkAuthenticationErrorHandler()`, not inside `whenReady`: only those are taken ahead of the first request and kept through a `wipeData()` made before `initialize()`, so the erased user's unsubscribes still go out with their token. Everything else is on the instance, `Joryio.getInstance()`, which throws `IllegalStateException` if the SDK is not initialized - the one call that can. Use `Joryio.whenReady { sdk -> ... }` to run code once it is. Otherwise no public call throws into your code: a missing argument (a `null` from Java) is logged and ignored, a `null` config value means the default, and a `null` attribute value removes the attribute (`setAttribute("plan", null)` is `unsetAttribute("plan")`). A `NaN` or `Infinity` in an attribute value or an event property is dropped - that key only - with a log line, and the rest of the call goes ahead.

`identify`, `addAlias`, `incrementAttribute`, `unsetAttribute` and the subscription calls are kept in an encrypted outbox until the server confirms them, and sent in order: offline, a timeout, a 5xx, a 429 or a `200` with `success: false` keeps the write and retries it with backoff (after a restart too, honouring `Retry-After`, which even `flush()` waits out). A write the server will never accept (a `4xx` such as `400`, `409`, `413`, `422`, or a `403` from your identity policy) is dropped with a log line so it cannot block the rest. Offline, a timeout, a 429, a 5xx or a `402` (your account's ingestion is paused) never count against a write. A `402` does not hold back withdrawals either: an unsubscribe or the opt-out record queued behind a refused write is still sent while the other writes wait. A `401` or a `200` with `success: false` is retried at most ten times for that write and a `404` at most five - a subscription change answered `404` after the queued events, which create the profile, are sent. A write made before `identify()` and sent after it carries the user id too. Attribute calls on the same key are applied in the order they were made. `track()` drops, with a log line, an event the server would refuse for good: properties over 50 KB, more than 200 keys or nested deeper than 5 levels, or a name that is empty or over 255 characters - measured as the server measures them, so a `<` or `&` counts as one character.

### Automatic session data

**Session Start** events carry device and environment data: `$device_id`, `$platform`, `$manufacturer`, `$model`, `$os_name`, `$os_version`, `$os_sdk_int`, `$app_version`, `$build_number`, `$package_name`, `$screen_width`, `$screen_height`, `$locale`, `$language`, `$languages` and `$timezone`.

## Configuration options

All parameters of `JoryioConfig` are optional:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `userId` | `String?` | `null` | Start with a known user ID |
| `anonymousId` | `String?` | generated | Use your own anonymous ID |
| `batchSize` | `Int` | `50` | Events per batch (at most 500) |
| `flushInterval` | `Long` | `5000` | Milliseconds between automatic flushes (at least 1000) |
| `sendImmediately` | `Boolean` | `false` | Send each event immediately |
| `maxQueueSize` | `Int` | `1000` | Most events held on the device; beyond it the oldest are dropped. Events are never dropped for failing to send |
| `sessionTimeout` | `Long` | `1800000` | Milliseconds of inactivity (including time in the background) before a new session and a new "Session Start" |
| `trackSessionStart` | `Boolean` | `true` | Track "Session Start" automatically |
| `maxRetries` | `Int` | `3` | Immediate retries of a request on a transient error, before the queue backs off and tries later |
| `retryBackoffMs` | `Long` | `1000` | Initial backoff between those retries in ms |
| `requestTimeout` | `Long` | `10000` | Per-request timeout in ms |
| `enableSdkAuthentication` | `Boolean` | `false` | Attach a customer-minted JWT to every ingest request |
| `sdkAuthenticationToken` | `String?` | `null` | Initial JWT |
| `allowHtmlJsInAppMessages` | `Boolean` | `false` | Allow HTML in-app messages (see below) |
| `optOut` | `Boolean` | `false` | Start opted out of tracking (remembered, like `optOut()`) |
| `trackingConsent` | `TrackingConsent?` | `null` (not set) | `GRANTED`, `PENDING` or `DENIED` - see [Tracking consent](#tracking-consent) |
| `notificationChannelName` | `String` | `"Notifications"` | The notification channel name users see in system settings (Android 8+). Pass it localized |
| `requestPushPermissionAtLaunch` | `Boolean` | `false` | Ask for notification permission at the first foreground. Prefer asking at a moment the user understands |
| `enableDebug` | `Boolean` | `false` | Verbose logging; also enables the testing-only APIs |
| `logLevel` | `LogLevel?` | `null` | `VERBOSE`, `DEBUG`, `INFO`, `WARN` or `ERROR`. `null` follows `enableDebug` (`DEBUG` when on, `ERROR` when off) |

## Push notifications

Joryio sends Android push through FCM. Add Firebase to your app and upload your Firebase service account in the dashboard - see [Firebase (FCM) credentials](https://docs.joryio.com/developers/apps/fcm-credentials).

### 1. Register the messaging service

Add to your `AndroidManifest.xml`:

```xml
<service
    android:name="io.joryio.sdk.push.JoryioFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

With Firebase Messaging in the app, the SDK fetches the FCM token and registers it itself at initialization and whenever the token changes.

### 2. Ask for permission

On Android 13+ notifications need the runtime `POST_NOTIFICATIONS` permission (the SDK's manifest declares it). Ask when the user has a reason to say yes:

```kotlin
val sdk = Joryio.getInstance()
if (sdk.getPushPermissionStatus() == PushPermissionStatus.NOT_DETERMINED) {
    sdk.requestPushPermission(activity) { status ->
        // GRANTED, DENIED, NOT_DETERMINED or UNAVAILABLE
    }
}
```

On a grant the SDK registers the FCM token. Below Android 13 nothing is shown and the current Settings state is reported. Any `Activity` works: when yours is a plain `android.app.Activity` (no `ActivityResultRegistry`, as in Unity), the SDK shows the dialog from its own invisible activity and calls you back when the user answers.

The notification channel is named "Notifications" by default; set `notificationChannelName` in `JoryioConfig` to show your own, localized name in system settings.

### If you already have a messaging service

Keep it, and hand the SDK the token and the receipts:

```kotlin
Joryio.getInstance().registerPushToken(token) { result ->
    if (!result.success && result.isRetryable) {
        // transient failure (offline, DNS, 5xx): try again later
    }
}

// When a message arrives (the trackingId is in the data payload)
Joryio.getInstance().reportPushDelivered(trackingId)

// When the user taps it
Joryio.getInstance().trackPushClick(trackingId)
```

More push calls: `isPushEnabled()`, `getDeviceToken()`, `getPushPermissionStatus()` and `unregisterPush()`. `unregisterPush()` lasts: the token is forgotten on the device at once, the request is kept until the server confirms it, and the SDK does not register a token on its own again (not at the next launch, not when Firebase rotates the token) until you call `registerPushToken()` or the user grants permission through `requestPushPermission()`. A registration counts only when the server confirms it (`success: true`); anything else is reported as a failure and retried on the next foreground. Do not pass Firebase's automatic token callbacks (`onNewToken()` in your own messaging service, the token fetched at every launch) to `registerPushToken()` while the user has turned push off: it is the call that turns push back on. The SDK's own messaging service handles new tokens itself and never undoes `unregisterPush()`, and a `Joryio.unregisterPush()` made before `initialize()` wins over the token Firebase hands over at launch. The token is stored and sent trimmed.

`getPushPermissionStatus()` returns a `PushPermissionStatus`; its `publicName` is the name the other Joryio SDKs use (`granted`, `denied`, `notDetermined`, `unavailable`). The `push_permission` user attribute keeps the value `not_determined`.

### Data payload keys

Every Joryio push carries the same keys on Android, iOS and web. The names are also constants on `io.joryio.sdk.push.PushPayloadKeys`.

| Key | Effect |
|-----|--------|
| `joryio` | Always `"true"` on a Joryio push; other messages are ignored by `JoryioFirebaseMessagingService` |
| `trackingId` | Used for the delivery receipt and for click tracking. Absent on test sends, which are not counted |
| `jry_campaign_id` | The campaign id, or the journey id for a journey send. For your app; the SDK does not act on it |
| `jry_deep_link` | What a tap opens (see below) |
| `jry_image_url` | Image, when the message has no FCM notification image |
| `jry_sync` | Silent sync nudge. `in_app` (or `campaigns`) makes the SDK fetch in-app campaigns now; nothing is shown |
| `title`, `body` | Used when the message has no FCM notification block |

When the user taps a notification the SDK records the click and opens your launcher activity - whether the SDK drew the notification (app in the foreground) or Android did (app in the background). Every data key arrives as an intent extra under its own name, so the deep link is always `jry_deep_link`; navigating to it is up to your app:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    intent.getStringExtra(PushPayloadKeys.DEEP_LINK)?.let { link -> openDeepLink(link) }
}
```

If your launcher activity is `singleTop` or `singleTask`, call `setIntent(intent)` in `onNewIntent` so a tap on a notification while the app is running is recorded too.

A notification Android draws while the app is in the background never reaches the SDK before the tap, so no delivery receipt is sent for it: Android delivery counts are a lower bound, as on iOS.

## In-app messaging

With `joryio-android-ui`, the SDK draws eligible messages itself and tracks impressions, clicks and dismissals. Nothing to register.

Messages come in two shapes:

- **Native** - structured data (headline, body, image, buttons) drawn with Android views, using your app's theme, fonts, dark mode and TalkBack. No WebView.
- **HTML** - author-supplied markup and CSS, shown in a WebView.

**HTML messages are off by default**, because they run inside your app. Opting in is your team's decision:

```kotlin
Joryio.initializeAsync(
    context = this,
    sdkKey = "jry_sdk_android_YOUR_SDK_KEY",
    apiHost = "api-eu1.joryio.com",
    config = JoryioConfig(allowHtmlJsInAppMessages = true),
)
```

Leaving it off does not disable in-app messaging: native messages still display, and HTML campaigns are skipped and logged.

An HTML message stays in its own document: any navigation it starts - a link, a refresh, a frame - is cancelled, and a link the user taps opens in the browser. Its CSS cannot close its own `<style>` element.

### Rendering messages yourself

Set a callback to draw messages with your own UI. It overrides the SDK's rendering, so you will not get two copies. It is called on the main thread, and a message type this SDK version does not know arrives as `MessageType.CUSTOM`:

```kotlin
Joryio.getInstance().setInAppMessageCallback(
    callback = { campaign ->
        showMyMessage(campaign)
        Joryio.getInstance().trackInAppImpression(campaign.id, "displayed")
    },
    capabilities = listOf("content.native"),
)
```

You must then report what happened with `trackInAppImpression(campaignId, action)` - `"displayed"`, `"clicked"`, `"dismissed"` or `"converted"`. For a click, pass which button as the third argument, `elementId`: `trackInAppImpression(campaign.id, "clicked", "cta")`. Only `"displayed"` counts toward the campaign's frequency cap and the gap before the next campaign; a click or dismissal does not use up another showing, and handing a message to your callback spends nothing - a message you decide not to show does not hold back the next one. An action outside that list is logged and not sent. A message you never report shows no displays in reporting. `Joryio.clearInAppMessageCallback()` hands rendering back to the SDK. A presenter installed with `setInAppPresenter` reports through the `onEvent` callback it receives and must not also call `trackInAppImpression`, or the message is counted twice. `capabilities` says what your renderer can draw: add `"content.html"` only if you host a WebView.

Campaigns sync automatically; call `Joryio.syncInAppCampaigns()` to sync now. `resetDisplayedCampaigns()` and `evaluateInAppCampaigns()` are testing helpers that only run when `enableDebug` is on.

## E-commerce

```kotlin
val ecommerce = Joryio.getInstance().ecommerce(EcommerceConfig(currency = "USD"))

ecommerce.viewProduct(EcommerceProduct(productId = "SKU123", name = "Blue Shirt", price = 29.99))
ecommerce.addToCart(EcommerceCartItem(productId = "SKU123", name = "Blue Shirt", price = 29.99, quantity = 1))
ecommerce.purchase(EcommerceOrder(
    orderId = "ORD123",
    items = listOf(EcommerceCartItem(productId = "SKU123", name = "Blue Shirt", price = 29.99, quantity = 1)),
    value = 29.99
))
```

`purchase` sends `Order Completed` with `orderId` and `total` - the keys revenue reporting reads. If you track a purchase by hand, use those two keys: an order sent with `order_id` or `value` is recorded with no revenue. A purchase with no `orderId`, a `value` that is not a finite number, or an item without a `productId` is ignored with a log line, never recorded as a zero order; a `null` line in `items` is skipped; no tracker method throws, and `EcommerceTracker.create()` works before initialization.

The classes are in `io.joryio.sdk.ecommerce`. Also available: `viewCategory`, `search`, `removeFromCart`, `cartViewed`, `updateCart`, `startCheckout`, `addPaymentInfo`, `orderFulfilled`, `orderDelivered`, `orderCancelled`, `orderRefunded`, `addToWishlist`, `removeFromWishlist`, `shareProduct`, `applyCoupon`, `removeCoupon` and `submitReview`. See [E-commerce tracking](https://docs.joryio.com/developers/sdk/ecommerce-tracking).

## Subscription preferences

Whether a person may be **messaged** on a channel - separate from `optOut()`, which is whether they may be **tracked**.

```kotlin
Joryio.setSubscription("email", "unsubscribed")
Joryio.addToSubscriptionGroup("list_abc123", "email")
Joryio.removeFromSubscriptionGroup("list_abc123", "email")
```

Channels: `email`, `sms`, `whatsapp`, `push`, `viber`. Statuses: `optedIn`, `subscribed`, `unsubscribed`.

## Privacy controls

```kotlin
Joryio.optOut()                         // stop tracking
Joryio.optIn()                          // resume - the only call that lifts an opt-out
Joryio.isUserOptedOut()                 // the opt-out switch only
Joryio.wipeData()                       // erase everything this SDK stored on the device

val (userId, anonymousId) = Joryio.getInstance().getIdentity()
```

`wipeData()` erases the anonymous and user id, the event queue, pending writes, in-app state and impression history, the session, the device id and the push token, and unregisters that token on the server. It does not opt the user out - call `optOut()` as well if that is intended - and it keeps an existing opt-out: deleting data is not consent.
Push then stays off until the app calls `registerPushToken()` or a permission is granted again - a token FCM hands over later is not registered automatically.

Opt-out is stored, so it survives restarts, and it covers every write: events, attributes (including increments and unsets), aliases, subscribes, push registration, delivery receipts and click tracking, and in-app reports. Opting out also discards events and writes not yet sent. Withdrawals are the exception: an unsubscribe (`setSubscription(channel, "unsubscribed")`, `removeFromSubscriptionGroup`) and a push unregister are accepted and delivered while opted out, while consent is `DENIED` or `PENDING`, and after `wipeData()`. `optOut()` records `tracking_opted_out = true` on the profile (no `$` prefix), sent whatever the consent, and `optIn()` sets it back to `false` once consent allows sending - each only when the switch changes, including an opt-out decided at or before initialization, and retried until the server has it. A record still queued when its user logs out stays that user's and is delivered to their profile, never re-addressed to the next person. `identify()` while opted out or while consent is `DENIED` is kept on the device only - nothing is sent - so that an unsubscribe made meanwhile carries the user id; the identify runs as usual once tracking is allowed again - `optIn()`, `updateConsent(GRANTED)`, or a later launch whose stored or configured state allows tracking - a new `identify()` replaces it, a `userId` in the config wins over it, and `reset()` or `wipeData()` forget it. The opt-out record and a push unregister made meanwhile carry that user id too, and under SDK Authentication a refusal of those asks your handler for that user's token. Before initialization `Joryio.isUserOptedOut()` and `Joryio.getTrackingConsent()` answer what an earlier launch stored; with nothing stored, `Joryio.getTrackingConsent()` answers `GRANTED`, the same as on iOS.

### Tracking consent

Where you need consent before collecting, start with `trackingConsent = TrackingConsent.PENDING`:

```kotlin
Joryio.initializeAsync(this, "jry_sdk_android_YOUR_SDK_KEY", "api-eu1.joryio.com",
    JoryioConfig(trackingConsent = TrackingConsent.PENDING))

// Later, from your consent screen:
Joryio.updateConsent(TrackingConsent.GRANTED)   // everything held is sent, in order
Joryio.updateConsent(TrackingConsent.DENIED)    // everything held is discarded
```

Consent (your banner) and opt-out (the person's own `optOut()` / `optIn()`) are two separate switches, the same on every Joryio SDK. The SDK collects only when consent is `GRANTED` and the person has not opted out - any "no" wins. Consent never lifts an opt-out, and `optOut()` never changes the stored consent.

- `PENDING`: events and attributes are kept on the device (up to `maxQueueSize`) and nothing is sent - no in-app messages, no push registration - until `GRANTED` releases them in order or `DENIED` discards them. This launch only; it never overrides a stored `DENIED`. A notification tap is held too and reported on `GRANTED`.
- `DENIED`: nothing is collected and what is held is discarded. Remembered across restarts until `updateConsent(TrackingConsent.GRANTED)` or an explicit `trackingConsent = TrackingConsent.GRANTED`. It does not opt the person out.
- Not set (`trackingConsent = null`, the default): a stored `DENIED` stays; otherwise the SDK behaves as `GRANTED`.

`getTrackingConsent()` returns the current value.

### Data at rest

- Sensitive key-value data (anonymous and user ID, session, FCM token) is stored in `EncryptedSharedPreferences` with a Keystore-backed AES-256-GCM key. If that store cannot be decrypted (typically after a restore from backup, which never includes Keystore keys), the SDK deletes and recreates it, encrypted. Any other Keystore error is retried and never deletes anything: if the Keystore stays unusable, that launch uses plain `SharedPreferences` rather than crashing your app. Such a launch starts without the stored identity; at the next launch that can open the encrypted store, its last change to each value wins - a logout, `wipeData()`, `optIn()` or a consent grant made then included - while the anonymous id and device id it had to generate do not replace the stored ones, and queued withdrawals and writes from both launches are kept. If that launch logged out or signed in another user, the stored user's anonymous id is not carried over and their unsent attributes are never sent as the new user - dropped after a logout, sent under their own ids otherwise. A master key that stays unusable - on three launches in a row with no store opening under it, and either a cause that says it cannot decrypt or failures that have lasted at least a day - is replaced by a new key of the SDK's own (your app's androidx key is never touched). The encrypted store under the old key is then discarded: whatever it still held that the plain-file launches did not write again, queued withdrawals included, is lost; a store the old key can still open is moved to the new key first. The main thread never waits for a Keystore retry.
- The offline event queue (Room) is kept in the app's no-backup directory and relies on Android's file-based encryption.

### Backup

You do not need backup rules for the SDK to work: the event queue lives in `noBackupFilesDir`, and a restored encrypted store is detected and recreated. If your app declares its own rules and you want the SDK's files left out explicitly, reference or copy the SDK's resources:

```xml
<application
    android:fullBackupContent="@xml/joryio_backup_rules"
    android:dataExtractionRules="@xml/joryio_data_extraction_rules">
```

The SDK does not set these attributes in its own manifest, so it never conflicts with yours.

### Minified builds (R8)

The SDK ships its own R8/ProGuard rules inside both artifacts, applied automatically when your app sets `isMinifyEnabled = true` (R8 full mode included). You do not add any rules. Without `firebase-messaging` in your app the build still succeeds; push is simply off.

## SDK authentication (optional)

When SDK authentication is enabled for your workspace, every ingest request must carry a JWT minted by your backend:

```kotlin
Joryio.initializeAsync(
    context = this,
    sdkKey = "jry_sdk_android_YOUR_SDK_KEY",
    apiHost = "api-eu1.joryio.com",
    config = JoryioConfig(enableSdkAuthentication = true, sdkAuthenticationToken = initialToken),
)

Joryio.setSdkAuthenticationErrorHandler { error ->
    // error.reason: MISSING, INVALID, EXPIRED, SUB_MISMATCH ...
    fetchFreshToken { Joryio.setSdkAuthenticationToken(it) }
}
```

The handler is called for every authentication `401` of the user who is signed in now, including before any token was set (reason `MISSING`) - never for a logged-out user's leftovers. Events go out in batches of one user at a time; events and writes of a user who has since logged out that the server refuses, for any authentication reason, are dropped, so they never hold back the next user's. `reset()` takes the token out of the session; it stays with the outgoing user's leftovers. Each request carries the token whose `sub` is that request's user - never one that names someone else - and a request of an anonymous visitor carries none. A token set for the next user before `reset()`, also while no one is signed in, is kept for them and used again at their `identify()`; `wipeData()` deletes it. Otherwise set the next user's token with `setSdkAuthenticationToken` once they sign in. Events and writes the outgoing user had already queued still go out with that user's token - kept in the encrypted store, so also after the app is closed, and deleted once they are delivered - so a normal logout loses nothing. `identify()` of a different user without `reset()` does the same for the previous user: their unsent attributes go out under their own ids and the token goes with their leftovers (unless you already set the new user's token, recognised by its `sub`). A queued unsubscribe is only ever replaced by a newer change of the same user.

## Utilities

```kotlin
Joryio.flush()                              // send queued events now
val sdk = Joryio.getInstance()
sdk.getQueueSize()
sdk.getUserId()
sdk.getAnonymousId()
sdk.getSessionId()
sdk.getSessionInfo()
sdk.currentApiEndpoint
sdk.lastTransportError
```

A session ends after `sessionTimeout` without activity, whether the app was in the background or stayed open; the first event after that starts a new session with its Session Start. `reset()` starts a new session too. A launch within `sessionTimeout` of the last activity continues that session without a new Session Start. A session that started while tracking was refused (opted out or consent denied) is not continued that way: the first launch that may track starts a new session with its Session Start. Reading `getSessionId()` or `getSessionInfo()` never starts or extends a session.

The delivery and consent rules, and every public method with its parameter names, are on the [Android SDK page](https://docs.joryio.com/developers/sdk/android-sdk#delivery-and-lifecycle).

## Documentation

- [Android SDK guide](https://docs.joryio.com/developers/sdk/android-sdk)
- [Firebase (FCM) credentials](https://docs.joryio.com/developers/apps/fcm-credentials)
- [Tracking events](https://docs.joryio.com/developers/sdk/tracking-events)
- [E-commerce tracking](https://docs.joryio.com/developers/sdk/ecommerce-tracking)
- [Changelog](./CHANGELOG.md)

## Support

- Issues: https://github.com/joryio/joryio-android-sdk/issues
- Email: support@joryio.com

## License

Commercial - see [LICENSE](./LICENSE). Use of the SDK requires an active Joryio subscription or agreement.
