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
 * behave identically. [emptyText] replaces "No messages yet"; [onDeepLink]
 * routes a card's app deep link (`myapp://...`).
 */
@Composable
fun JoryioCardFeed(
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    onDeepLink: ((String, Card) -> Unit)? = null,
    onRender: ((CardFeed) -> Unit)? = null,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> CardFeedView(context) },
        update = { view ->
            if (emptyText != null && view.emptyText != emptyText) view.emptyText = emptyText
            view.onDeepLink = onDeepLink
            view.onRender = onRender
        },
    )
}
