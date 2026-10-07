# Joryio Android SDK - consumer R8/ProGuard rules.
#
# Packaged inside the AAR and applied automatically to every app that builds
# with minifyEnabled. Nothing here is for the app to copy. Verified by
# tools/consumer-build-check.sh, which minifies a real app in R8 FULL mode with
# and without firebase-messaging on the classpath.
#
# Until 1.3.0 build.gradle.kts declared this file and it did not exist, so a
# minified app shipped an SDK that could not talk to the server.

# --- Optional push dependency -----------------------------------------------
# firebase-messaging is compileOnly: an app without push does not have it, and
# R8 then fails the build with "Missing class com.google.firebase...". The SDK
# checks the classpath at runtime (PushNotificationManager.isFcmOnClasspath),
# so the missing classes are never touched.
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- Wire models (Gson) -----------------------------------------------------
# Gson reads and writes FIELD NAMES, so the field names are the wire format.
# Every wire field also carries @SerializedName; keeping the fields as well
# stops R8 from removing ones that are only ever written reflectively (a
# response field nothing in the SDK reads still has to deserialize).
-keep class io.joryio.sdk.models.** { <fields>; <init>(...); }
-keep class io.joryio.sdk.network.** { <fields>; <init>(...); }
-keepclassmembers enum io.joryio.sdk.** { <fields>; }

# Gson needs the generic signature of every TypeToken subclass (the queue,
# pending attributes and impression history are read back through one); in R8
# full mode an anonymous subclass loses it and fromJson throws.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken

# --- Retrofit interface -----------------------------------------------------
# A Retrofit service is an interface implemented by a runtime proxy; its
# methods and their annotations are read reflectively.
-keep,allowobfuscation interface io.joryio.sdk.network.JoryioApi
-keepclassmembers interface io.joryio.sdk.network.JoryioApi { <methods>; }
# A suspend function's return type lives in the generic signature of its
# Continuation parameter; full mode strips it unless these stay.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# --- Reflection between the two artifacts -----------------------------------
# joryio-android-ui calls Joryio by NAME so it has no compile-time coupling, and
# the base looks the UI presenter up by name. The UI side's own rules keep the
# presenter; these keep what it calls here.
-keep class io.joryio.sdk.Joryio { public *; }
-keep class io.joryio.sdk.Joryio$Companion { public *; }
-keep interface io.joryio.sdk.InAppMessagePresenter { *; }

# --- androidx.startup ---------------------------------------------------------
# Named in the merged manifest and instantiated by androidx.startup by name:
# the class name and its no-argument constructor must survive.
-keep class io.joryio.sdk.core.JoryioContextInitializer { <init>(); }
