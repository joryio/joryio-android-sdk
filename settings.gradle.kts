pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "1.9.20"
    }
    // The module declares the LEGACY `kotlin-kapt` id, because that is the one
    // that resolves off a host app's Kotlin buildscript classpath when the SDK
    // is consumed as a source module (the RN bridge does exactly that, and the
    // modern `org.jetbrains.kotlin.kapt` id would force every consumer to
    // declare a version in its own settings.gradle).
    //
    // The legacy id has no published plugin-marker artifact, so THIS project's
    // standalone build has to map it to the real module itself.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "kotlin-kapt") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "joryio-sdk-android"

// Two artifacts, the common pattern among in-app SDKs (Firebase splits
// inappmessaging from inappmessaging-display the same way):
//
//   :joryio     -> io.joryio:joryio-android      tracking, identity, push,
//                                                networking, in-app SYNC and
//                                                eligibility. No UI, no WebView.
//   :joryio-ui  -> io.joryio:joryio-android-ui   in-app RENDERING. Depends on
//                                                base at an exact version.
//
// An integrator declares ONE of them. `-ui` pulls base transitively, so the two
// can never drift apart in a consumer build - the consumer names one version.
include(":joryio")
include(":joryio-ui")
