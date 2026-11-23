package io.joryio.sdk.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.joryio.sdk.cards.Card
import io.joryio.sdk.cards.CardFeed
import io.joryio.sdk.ui.CardFeedView

/**
 * The person's content cards in Compose (docs/PLACEMENTS_PLAN.md section 11,
 * D444 Phase 3):
 *
 * ```kotlin
 * JoryioCardFeed(Modifier.fillMaxSize())
 * ```
 *
 * The same view as [CardFeedView] - the feed, paging, the unread dot, the
 * dismiss control, the viewed / read / clicked / dismissed reports and the
 * link rule - hosted with AndroidView, so a Compose screen and an XML screen
 * behave identically. [emptyText] replaces "No messages yet", [pinnedLabel]
 * "Pinned", [dismissLabel] "Dismiss" and [unreadLabel] "Unread" (the same
 * labels as CardFeedView, iOS, web and React Native); [onDeepLink] routes a
 * card's app deep link (`myapp://...`).
 */
@Composable
fun JoryioCardFeed(
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    pinnedLabel: String? = null,
    dismissLabel: String? = null,
    unreadLabel: String? = null,
    onDeepLink: ((String, Card) -> Unit)? = null,
    onRender: ((CardFeed) -> Unit)? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> CardFeedView(context) },
        update = { view -> applyCardFeedOptions(view, emptyText, pinnedLabel, dismissLabel, unreadLabel, onDeepLink, onRender) },
    )
}

/** What [JoryioCardFeed] sets on its view (a null label keeps the default). */
internal fun applyCardFeedOptions(
    view: CardFeedView,
    emptyText: String?,
    pinnedLabel: String?,
    dismissLabel: String?,
    unreadLabel: String?,
    onDeepLink: ((String, Card) -> Unit)?,
    onRender: ((CardFeed) -> Unit)?,
) {
    if (emptyText != null && view.emptyText != emptyText) view.emptyText = emptyText
    if (pinnedLabel != null) view.pinnedLabel = pinnedLabel
    if (dismissLabel != null) view.dismissLabel = dismissLabel
    if (unreadLabel != null) view.unreadLabel = unreadLabel
    view.onDeepLink = onDeepLink
    view.onRender = onRender
}
