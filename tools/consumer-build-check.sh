#!/usr/bin/env bash
# Build the two modules THE WAY AN APP CONSUMES THEM: as projects of another
# Gradle build (`include ':joryio-sdk'` + projectDir), not as this repo's own
# root build. That is the arrangement two bugs slipped through (Teamder,
# 2026-09-28): the standalone build read this repo's gradle.properties and the
# consumer's did not. Needs JDK 17 and ANDROID_HOME, like the SDK itself.
#
# Phase 2 builds a MINIFIED release app (R8 full mode, the AGP 8 default) on
# top of the SDK, twice: with firebase-messaging on the classpath and without
# it. Until 1.3.0 the consumer R8 rules file was declared and missing, so a
# minified app failed to build without Firebase ("Missing class
# com.google.firebase...") and, with it, renamed every Gson wire field. The
# check fails the build on either, and reads R8's mapping to prove the wire
# models and the reflectively-found presenter kept their names.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp -R "$HERE/gradle" "$HERE/gradlew" "$TMP/"
# The app root's settings: the SDK's own pluginManagement block (plugin
# versions + the legacy kotlin-kapt mapping an app's classpath provides), then
# the two modules included the way an app includes them - by projectDir, under
# the names the React Native bridge uses.
sed -n '1,/^rootProject.name/p' "$HERE/settings.gradle.kts" | sed 's/^rootProject.name.*/rootProject.name = "consumer-app"/' > "$TMP/settings.gradle.kts"
cat >> "$TMP/settings.gradle.kts" <<SETTINGS
include(":joryio-sdk")
project(":joryio-sdk").projectDir = file("$HERE/joryio")
include(":joryio-sdk-ui")
project(":joryio-sdk-ui").projectDir = file("$HERE/joryio-ui")
SETTINGS
# A root build with NO joryioSdkVersion, the way an app's root looks.
printf 'android.useAndroidX=true\n' > "$TMP/gradle.properties"
(cd "$TMP" && ./gradlew :joryio-sdk:compileReleaseKotlin :joryio-sdk-ui:compileReleaseKotlin --no-daemon -q)
echo "consumer arrangement: both modules compile"

# ── Phase 2: a minified app, with and without firebase-messaging ────────────

APP="$TMP/minified"
mkdir -p "$APP/app/src/main/java/com/example/consumer"
cp -R "$HERE/gradle" "$HERE/gradlew" "$APP/"
cat > "$APP/settings.gradle.kts" <<SETTINGS
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("com.android.library") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "1.9.20"
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "kotlin-kapt") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "minified-consumer"
include(":app")
include(":joryio-sdk")
project(":joryio-sdk").projectDir = file("$HERE/joryio")
include(":joryio-sdk-ui")
project(":joryio-sdk-ui").projectDir = file("$HERE/joryio-ui")
SETTINGS
# R8 full mode stated explicitly, so a future AGP default cannot weaken the check.
printf 'android.useAndroidX=true\nandroid.enableR8.fullMode=true\n' > "$APP/gradle.properties"

cat > "$APP/app/src/main/java/com/example/consumer/ConsumerApp.kt" <<'KOTLIN'
package com.example.consumer

import android.app.Application
import io.joryio.sdk.Joryio
import io.joryio.sdk.JoryioConfig

/** Touches the public API the way an app does, so R8 keeps what an app keeps. */
class ConsumerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Joryio.initializeAsync(this, "jry_sdk_android_check", "api.example.com", JoryioConfig())
        Joryio.track("Opened", mapOf("count" to 1))
        Joryio.identify("user-1")
        Joryio.addAlias("loyalty_id", "L-1")
        Joryio.setAttribute("plan", "pro")
        Joryio.whenReady { sdk ->
            sdk.registerPushToken("token") { }
            sdk.trackInAppImpression("c1", "displayed")
        }
    }
}
KOTLIN

# The app adds NO keep rules of its own - only a request to print what was kept.
printf -- '-printseeds build/seeds.txt\n' > "$APP/app/check-rules.pro"

build_variant() {
    local label="$1" firebase_dep="$2" service="$3"
    cat > "$APP/app/build.gradle.kts" <<GRADLE
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.consumer"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.consumer"
        minSdk = 23
        targetSdk = 34
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "check-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":joryio-sdk-ui"))
    $firebase_dep
}
GRADLE
    cat > "$APP/app/src/main/AndroidManifest.xml" <<MANIFEST
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name=".ConsumerApp" android:label="consumer">
        $service
    </application>
</manifest>
MANIFEST
    (cd "$APP" && ./gradlew :app:clean :app:assembleRelease --no-daemon -q)

    test -f "$APP/app/build/outputs/mapping/release/mapping.txt" \
        || { echo "FAIL ($label): no R8 mapping - was the build minified?"; exit 1; }
    # R8's seeds: what the keep rules (ours, shipped in the AARs) pinned by
    # name. The mapping file omits members R8 left unchanged, so it cannot
    # prove a field kept its name; the seeds can.
    local seeds="$APP/app/build/seeds.txt"
    test -f "$seeds" || { echo "FAIL ($label): R8 wrote no seeds"; exit 1; }
    for cls in io.joryio.sdk.models.Event io.joryio.sdk.network.SetAttributesRequest \
               io.joryio.sdk.network.BatchResponse io.joryio.sdk.network.AddAliasRequest \
               io.joryio.sdk.ui.DefaultInAppMessagePresenter io.joryio.sdk.Joryio \
               io.joryio.sdk.core.JoryioContextInitializer; do
        grep -q "^$cls\$" "$seeds" || { echo "FAIL ($label): $cls is not kept by the consumer rules"; exit 1; }
    done
    for member in "io.joryio.sdk.network.SetAttributesRequest: java.lang.String anonymousId" \
                  "io.joryio.sdk.network.BatchResponse: java.util.List failedIndices" \
                  "io.joryio.sdk.network.BatchResponse: java.util.List rejectedIndices" \
                  "io.joryio.sdk.network.OutboxEntry: int attempts" \
                  "io.joryio.sdk.network.OutboxEntry: java.lang.String key" \
                  "io.joryio.sdk.network.AddAliasRequest: java.lang.String aliasLabel" \
                  "io.joryio.sdk.models.Event: java.util.Date timestamp" \
                  "io.joryio.sdk.network.OutboxEntry: java.lang.String body" \
                  "io.joryio.sdk.network.GenericResponse: java.lang.Boolean success"; do
        grep -qF "$member" "$seeds" || { echo "FAIL ($label): '$member' is not kept by the consumer rules"; exit 1; }
    done
    # androidx.startup instantiates the SDK's Initializer by the name in the
    # merged manifest: the entry must be there.
    grep -rq "io.joryio.sdk.core.JoryioContextInitializer" "$APP/app/build/intermediates/merged_manifests" \
        || { echo "FAIL ($label): JoryioContextInitializer is not in the merged manifest"; exit 1; }
    echo "minified app ($label): R8 full mode builds, wire models and presenter keep their names"
}

build_variant "without firebase-messaging" "" ""
build_variant "with firebase-messaging" \
    'implementation("com.google.firebase:firebase-messaging:23.3.1")' \
    '<service android:name="io.joryio.sdk.push.JoryioFirebaseMessagingService" android:exported="false"><intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter></service>'
