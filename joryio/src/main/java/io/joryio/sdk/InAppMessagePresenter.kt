package io.joryio.sdk

import android.app.Activity
import io.joryio.sdk.models.InAppCampaign

/**
 * How an in-app message gets DRAWN.
 *
 * The base SDK decides *whether* and *when* to show a message - sync,
 * eligibility, targeting, frequency capping - and then hands it to a presenter.
 * It never names a view class, which is what allows the rendering layer to ship
 * as a separate artifact (`joryio-android-ui`) that an app can leave out
 * entirely.
 *
 * That split is the common pattern among in-app SDKs - Firebase splits
 * `firebase-inappmessaging` from `firebase-inappmessaging-display`. The reason
 * is not bytes - it is that an app taking only analytics and push should not
 * link a WebView at all, which is a procurement question for some security
 * teams rather than a size preference.
 *
 * Base ships with NO implementation. It discovers one reflectively at
 * initialize() if `joryio-android-ui` is on the classpath, so adding the UI
 * artifact is the entire integration step - there is no register() call to
 * forget. An app that wants to draw its own UI calls
 * [Joryio.setInAppMessageCallback] instead, which takes precedence over any
 * presenter.
 */
interface InAppMessagePresenter {

    /**
     * Draw [campaign] over [activity].
     *
     * @param onEvent invoked when the user interacts: `action` is the event
     *   (`"clicked"` / `"dismissed"` / ...) and `meta` carries whatever that
     *   event has to say - the button id, a url. Passed through rather than
     *   narrowed, so adding a field to an event does not change this contract.
     * @return true if the message was displayed. Returning false lets the
     *   caller treat it as not-shown - it must NOT count an impression for a
     *   message the user never saw, which is exactly the accounting error that
     *   makes a channel look healthier than it is.
     */
    fun present(
        activity: Activity,
        campaign: InAppCampaign,
        onEvent: (action: String, meta: Map<String, Any?>) -> Unit,
    ): Boolean
}
