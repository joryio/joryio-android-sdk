package io.joryio.sdk.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.joryio.sdk.placements.PLACEMENT_PRODUCT_CARD_WIDTH
import io.joryio.sdk.placements.Placement
import io.joryio.sdk.ui.PlacementView

/**
 * A Joryio placement in Compose (docs/PLACEMENTS_PLAN.md, D444):
 *
 * ```kotlin
 * JoryioPlacement("home_hero", Modifier.fillMaxWidth())
 * ```
 *
 * The same view as [PlacementView] - fetching, caching, viewability (half of
 * an item on screen for a second), holdout exposure and the
 * impression / click / dismissal reports - hosted with AndroidView, so a
 * Compose screen and an XML screen behave identically. [onRender] is called
 * after each draw with what was drawn.
 *
 * An item's recommended products are drawn as a row of product cards
 * ([showProducts], [productButtonText], [productCardWidth] in dp - the
 * PlacementView properties of the same names).
 */
@Composable
fun JoryioPlacement(
    placementKey: String,
    modifier: Modifier = Modifier,
    onRender: ((Placement) -> Unit)? = null,
    showProducts: Boolean = true,
    productButtonText: String? = null,
    productCardWidth: Int = PLACEMENT_PRODUCT_CARD_WIDTH,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> PlacementView(context, placementKey) },
        update = { view ->
            view.setPlacementKey(placementKey)
            view.onRender = onRender
            view.showProducts = showProducts
            view.productButtonText = productButtonText
            view.productCardWidth = productCardWidth
        },
    )
}
