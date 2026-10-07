package io.joryio.sdk.ui

import android.app.Activity
import io.joryio.sdk.InAppMessagePresenter
import io.joryio.sdk.models.InAppCampaign

/**
 * The presenter the base SDK finds when `joryio-android-ui` is on the classpath.
 *
 * Base looks this class up BY NAME
 * (`io.joryio.sdk.ui.DefaultInAppMessagePresenter`) and calls this exact
 * constructor, so **the fully-qualified name and the constructor signature are
 * public API between the two artifacts**. Renaming either is a breaking change
 * that no compiler will catch - it would simply stop displaying messages, in
 * silence. Keep them, or change base's lookup in the same commit.
 *
 * Everything else here is an implementation detail free to move.
 */
internal class DefaultInAppMessagePresenter(
    allowHtmlJsInAppMessages: Boolean,
    debugEnabled: Boolean,
) : InAppMessagePresenter {

    init {
        UiLog.debugEnabled = debugEnabled
    }

    private val renderer = InAppMessageRenderer(
        allowHtmlJsInAppMessages = allowHtmlJsInAppMessages,
    )

    override fun present(
        activity: Activity,
        campaign: InAppCampaign,
        onEvent: (action: String, meta: Map<String, Any?>) -> Unit,
    ): Boolean = try {
        // Return what the renderer reports, not a blanket true: it already
        // declines content it cannot draw, and claiming success there would
        // count an impression for a message nobody saw.
        renderer.show(activity, campaign, onEvent)
    } catch (e: Throwable) {
        // Never let a rendering fault take the host app down: an in-app message
        // is marketing, and a crash on someone else's launch screen is a far
        // worse outcome than a message that did not appear. Returning false
        // also stops the caller counting an impression nobody saw.
        UiLog.warn("Failed to display in-app message ${campaign.id}: ${e.message}")
        false
    }
}
