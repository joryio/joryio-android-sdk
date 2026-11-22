package io.joryio.sdk.cards

/**
 * Content cards (docs/PLACEMENTS_PLAN.md section 11, BACKLOG D444 Phase 3): a
 * persistent feed per person - a message center inside the app. Cards come
 * from card campaigns and the journey step "Add card".
 *
 * Draw the feed with `CardFeedView` (joryio-android-ui) or `JoryioCardFeed()`
 * (joryio-android-compose), or read it with `Joryio.getCards()` and draw it
 * yourself, reporting with `markCardViewed` / `markCardRead` /
 * `markCardClicked` / `dismissCard`.
 *
 * The names and values here are the same on every Joryio SDK, pinned by the
 * backend's SDK parity spec.
 */

/** The feed goes to the network at most once per this many seconds; extra calls coalesce. */
const val CARD_MIN_REFRESH_SECONDS: Int = 30

/** How long the feed is served from the device cache before it is asked for again (the answer's `ttlSeconds` wins). */
const val CARD_FEED_TTL_SECONDS: Int = 300

/** The capability a card-aware SDK declares. */
const val CARD_SDK_CAPABILITY: String = "cards"

/** The silent push `jry_sync` value that refreshes the feed. */
const val CARD_SYNC_TYPE: String = "cards"

/** What the SDK reports about a card: the `action` of `POST v1/in-app/track`. Wire values. */
object CardActions {
    const val VIEWED = "viewed"
    const val READ = "read"
    const val CLICKED = "clicked"
    const val DISMISSED = "dismissed"
}

/** `stateScope` wire values. */
object CardStateScope {
    /** Read / clicked / dismissed are kept per person on the server, the same on every device. */
    const val SERVER = "server"

    /** An anonymous visitor: this device keeps what was read and dismissed. */
    const val DEVICE = "device"
}

/** One card of the person's feed. */
data class Card(
    /** Stable id of this card for this person. */
    val id: String,
    /** Campaign id, or `journey:<canvasId>:<nodeId>` for a journey card. */
    val campaignId: String,
    val variantId: String?,
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    /** Button label; null = no button (the whole card opens [url] when set). */
    val buttonText: String?,
    /** An http(s) URL or the app's own deep link; null = nothing to open. */
    val url: String?,
    val pinned: Boolean,
    /** ISO 8601. */
    val createdAt: String,
    /** ISO 8601, or null. */
    val expiresAt: String?,
    val read: Boolean,
    val clicked: Boolean,
    /** Signed token every report of this card carries. Internal: never shown, never logged. */
    internal val deliveryToken: String,
) {
    /** The card as JSON for Joryio's React Native and Unity bridges (no delivery token). */
    fun toJson(): String = toJsonObject().toString()

    internal fun toJsonObject(): com.google.gson.JsonObject = com.google.gson.JsonObject().apply {
        addProperty("id", id)
        addProperty("campaignId", campaignId)
        addProperty("variantId", variantId)
        addProperty("title", title)
        addProperty("body", body)
        addProperty("imageUrl", imageUrl)
        addProperty("buttonText", buttonText)
        addProperty("url", url)
        addProperty("pinned", pinned)
        addProperty("createdAt", createdAt)
        addProperty("expiresAt", expiresAt)
        addProperty("read", read)
        addProperty("clicked", clicked)
    }
}

/** The person's feed (or one page of it). */
data class CardFeed(
    /** Pinned first, then newest first. */
    val cards: List<Card>,
    /** Unread cards across the whole feed, not this page: the badge. */
    val unreadCount: Int,
    /** Pass to `getCards(cursor)` for the next page; null on the last one. */
    val nextCursor: String?,
    /** [CardStateScope.SERVER] or [CardStateScope.DEVICE]. */
    val stateScope: String,
) {
    /**
     * The feed as JSON for Joryio's React Native and Unity bridges:
     * `{ cards: [...], unreadCount, nextCursor, stateScope }` - no delivery
     * tokens (reports go back by card id).
     */
    fun toJson(): String {
        val o = com.google.gson.JsonObject()
        o.add("cards", com.google.gson.JsonArray().apply { cards.forEach { add(it.toJsonObject()) } })
        o.addProperty("unreadCount", unreadCount)
        o.addProperty("nextCursor", nextCursor)
        o.addProperty("stateScope", stateScope)
        return o.toString()
    }

    companion object {
        val EMPTY = CardFeed(emptyList(), 0, null, CardStateScope.SERVER)
    }
}
