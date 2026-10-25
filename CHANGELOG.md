# Changelog

## 1.3.2 - 2026-10-25

Added: `JoryioConfig.holdInAppMessagesForHandler` (off by default). An app that draws in-app messages itself and calls `setInAppMessageCallback` after `initialize()` could have the first message drawn by the SDK: the sync `initialize()` starts could finish before the callback was set. With the option on, a message that is ready before the callback is held - not drawn, not reported, nothing spent - and handed to the callback when it is set; `clearInAppMessageCallback()` instead ends the hold and the SDK draws the held message. Reset, wipe and opt-out drop a held message. A held message is bound to the person it was chosen for: identify to another user, reset, wipe, opt-out and consent changes drop it, and delivery re-checks the anonymous id, user id and age (at most 5 minutes). The same option, by the same name, on iOS, React Native and Unity.

## 1.3.1 - 2026-10-24

Fixed: HTML in-app messages did not work on Android since 1.3.0. The script the SDK injects into the message page carried a link pattern with doubled backslashes, which the WebView rejected as a syntax error, so the whole bootstrap stopped: no `joryioBridge`, no height reporting, no button or link handling, no forms. The pattern is fixed and pinned by a test.

Fixed: an HTML modal or slide-up could grow but never shrink. The height is now measured from the message body, so a short message is as short as its content and a message re-fits when it gets shorter (a form replaced by its thank-you line). Heights stay capped at 90% of the screen; the message scrolls inside beyond that.

Added: `InAppCampaign.maxWidth` for HTML modals and slide-ups (dp - the same number as CSS pixels in the message, set by the "Max width" field in the dashboard; modal 600 and slide-up 400 when unset). With the `joryio-android-ui` artifact, the message is laid out at min(maxWidth, window width - margins) before its content loads, so the author's media queries see the real width; the slide-up sits at the bottom end with 12dp margins.

## 1.3.0 - 2026-10-06

First public release.
