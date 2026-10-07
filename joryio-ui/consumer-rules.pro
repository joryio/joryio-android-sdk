# Joryio Android SDK UI - consumer R8/ProGuard rules.
#
# Packaged inside the AAR and applied automatically to every app that builds
# with minifyEnabled. Verified by tools/consumer-build-check.sh. Until 1.3.0
# this kept only the presenter's constructor; the JavaScript bridge and the
# reflective calls into the base SDK were unprotected.

# The base SDK finds the presenter by NAME (Class.forName) and constructs it
# with (allowHtmlJsInAppMessages, debugEnabled). Renamed or shrunk, the lookup
# fails and in-app messages silently stop displaying.
-keep class io.joryio.sdk.ui.DefaultInAppMessagePresenter {
    <init>(boolean, boolean);
    public *;
}

# The message bridge: a WebView calls these methods by name from JavaScript.
-keepclassmembers class io.joryio.sdk.ui.** {
    @android.webkit.JavascriptInterface <methods>;
}

# The renderer calls the base SDK reflectively (getInstance,
# requestPushPermission) to keep this artifact free of a compile-time coupling.
-keep class io.joryio.sdk.Joryio { public *; }
