# Changelog

## 1.3.3 - 2026-11-03

Fixed: an in-app message counted as displayed the moment it was drawn, even when its screen closed straight away - a sign-in redirect, a logout. The person saw at most a flash, yet `in_app.displayed` was reported and the frequency cap was spent, so a capped campaign could be used up without ever being seen. A message the SDK draws now counts as displayed only after it has stayed on a resumed screen for `IN_APP_DISPLAY_DWELL_MS` (1000 ms, `io.joryio.sdk.messaging`); if the screen pauses the dwell stops, and it restarts in full on resume. A screen that finishes or is destroyed first reports nothing, spends nothing, and the message is offered on the next screen. Messages are drawn only on the resumed screen that is not finishing, destroyed or being recreated. A click or a dismissal before the dwell counts - `displayed` is reported first, then the interaction. A reset, identity change, opt-out or consent change before the dwell reports nothing. Presenters gain `InAppMessagePresenter.EVENT_HIDDEN` for a message removed without the person acting on it. Apps that draw messages themselves (`setInAppMessageCallback`) should report `displayed` by the same rule. Same name, value and rule on web, iOS, React Native and Unity; event names and properties are unchanged.

## 1.3.2 - 2026-10-25

Added: `JoryioConfig.holdInAppMessagesForHandler` (off by default). An app that draws in-app messages itself and calls `setInAppMessageCallback` after `initialize()` could have the first message drawn by the SDK: the sync `initialize()` starts could finish before the callback was set. With the option on, a message that is ready before the callback is held - not drawn, not reported, nothing spent - and handed to the callback when it is set; `clearInAppMessageCallback()` instead ends the hold and the SDK draws the held message. Reset, wipe and opt-out drop a held message. A held message is bound to the person it was chosen for: identify to another user, reset, wipe, opt-out and consent changes drop it, and delivery re-checks the anonymous id, user id and age (at most 5 minutes). The same option, by the same name, on iOS, React Native and Unity.

## 1.3.1 - 2026-10-24

Fixed: HTML in-app messages did not work on Android since 1.3.0. The script the SDK injects into the message page carried a link pattern with doubled backslashes, which the WebView rejected as a syntax error, so the whole bootstrap stopped: no `joryioBridge`, no height reporting, no button or link handling, no forms. The pattern is fixed and pinned by a test.

Fixed: an HTML modal or slide-up could grow but never shrink. The height is now measured from the message body, so a short message is as short as its content and a message re-fits when it gets shorter (a form replaced by its thank-you line). Heights stay capped at 90% of the screen; the message scrolls inside beyond that.

Added: `InAppCampaign.maxWidth` for HTML modals and slide-ups (dp - the same number as CSS pixels in the message, set by the "Max width" field in the dashboard; modal 600 and slide-up 400 when unset). With the `joryio-android-ui` artifact, the message is laid out at min(maxWidth, window width - margins) before its content loads, so the author's media queries see the real width; the slide-up sits at the bottom end with 12dp margins.

## 1.3.0 - 2026-10-06

First public release.
