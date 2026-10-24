import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Generates Room's JoryioDatabase_Impl. WITHOUT this the room-runtime
    // dependency below compiles fine and then throws at RUNTIME - "Cannot find
    // implementation for JoryioDatabase" - inside initialize(), which meant the
    // Android SDK could never start. A missing annotation processor is invisible
    // until the first database access, so keep it next to the room deps.
    //
    // kapt rather than KSP deliberately: this module is consumed as a SOURCE
    // MODULE by host apps (the RN bridge does exactly that), and KSP would
    // require every one of those apps to declare the KSP plugin version in its
    // own settings.gradle. kapt ships with the Kotlin plugin they already have,
    // so the SDK stays self-contained. Revisit if the SDK is only ever consumed
    // as a published AAR.
    id("kotlin-kapt")
    id("maven-publish")
}

android {
    namespace = "io.joryio.sdk"
    compileSdk = 34

    /**
     * Android Lint is already part of AGP - no extra tooling. Configured so a
     * run MEANS something: dependency-staleness warnings drowned the two
     * findings that were actually about our code, and a report nobody reads is
     * the same as no report.
     *
     * Run with: ./gradlew :joryio:lintRelease
     */
    lint {
        warningsAsErrors = false
        abortOnError = true
        // Version-bump nagging, not correctness. Dependencies are upgraded
        // deliberately, not because a linter noticed a newer number exists.
        disable += setOf("GradleDependency", "OldTargetApi", "KaptUsageInsteadOfKsp")
    }

    defaultConfig {
        minSdk = 23
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Shipped INSIDE the AAR and applied to every app that minifies. The
        // file was declared here and did not exist, so an R8 build of a host
        // app renamed the Gson wire models (request bodies arrived with fields
        // called `a`, `b`) and stripped the TypeToken generics the queue reads
        // back. Each rule's reason is in the file.
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Java 11 BYTECODE (scan 5, Unity finding B1). Unity 2021.3 and 2022.3 -
    // the oldest Editors the Unity package supports - build Android with
    // OpenJDK 11, and their javac refuses a class file of version 61 (Java 17)
    // when it compiles the Unity bridge against this AAR: "class file has
    // wrong version 61.0, should be 55.0". Nothing in the SDK needs a Java 17
    // language or library feature, so the AAR targets 11 (class version 55);
    // AGP 8 still RUNS on JDK 17, which is a different thing from the target.
    //
    // The Kotlin target is not written down here: it FOLLOWS the Java target
    // (see the KotlinCompile block at the end of this file). When the SDK is
    // consumed as an included Gradle project, React Native's
    // `com.facebook.react.rootproject` plugin rewrites every module's
    // compileOptions to 17 in finalizeDsl, and a Kotlin target pinned to a
    // different number fails Kotlin's jvm-target validation outright:
    //
    //   Inconsistent JVM-target compatibility detected for tasks
    //   'compileDebugJavaWithJavac' (17) and 'compileDebugKotlin' (11)
    //
    // Derived at task-configuration time - after finalizeDsl - the two always
    // agree: 11 in the published AAR, 17 inside a React Native build.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Sources and Javadoc jars next to the AAR: Maven Central refuses a
    // release without both, so the release workflow only has to sign.
    // withJavadocJar() runs the Dokka that AGP bundles over the Kotlin
    // sources - no extra plugin to declare.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Kotlin. `api`, not `implementation`: the public API exposes Kotlin
    // types (kotlin.Pair, Function1, Unit - registerPushToken(token, cb),
    // getIdentity()), so a pure-Java app compiling against the SDK needs the
    // stdlib on its COMPILE classpath. As `implementation` the POM scoped it
    // `runtime` and such an app did not compile (scan 5, Android NICE 8; the
    // Unity package worked around it in JoryioDependencies.xml).
    api("org.jetbrains.kotlin:kotlin-stdlib:1.9.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    // ActivityResultRegistry, for requesting POST_NOTIFICATIONS without asking the
    // host app to plumb onRequestPermissionsResult back to us. Present already in
    // every AppCompat / React Native app; declared so we do not rely on it
    // arriving transitively.
    implementation("androidx.activity:activity-ktx:1.8.2")
    // Compile-time only, and only for lint: firebase-messaging (compileOnly)
    // drags fragment 1.0.0 onto the compile classpath, and lint's
    // InvalidFragmentVersionForActivityResult then flags every
    // registerForActivityResult call - a false positive for a ComponentActivity
    // (JoryioPermissionActivity), which never goes through a Fragment. Nothing
    // is added to a consumer's runtime classpath.
    compileOnly("androidx.fragment:fragment:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    // Already on the classpath through lifecycle-process; declared because the
    // SDK now registers its own Initializer (PreInitStore's context).
    implementation("androidx.startup:startup-runtime:1.1.1")

    // Security: EncryptedSharedPreferences + MasterKey (encrypt sensitive data at rest).
    // Requires API 23+ at runtime; minSdk IS 23, so it is always available.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking
    // 2.11: its embedded R8 rules keep the generic signatures a suspend
    // function's Response<T> needs in R8 FULL mode (the AGP 8 default). 2.9's
    // did not, and a minified app failed every call with "Response must include
    // generic type".
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON. 2.11 ships its own R8 rules (TypeToken subclasses keep their
    // generic signature) and ToNumberPolicy, which keeps an integer property an
    // integer through the on-disk queue.
    implementation("com.google.code.gson:gson:2.11.0")

    // Database
    implementation("androidx.room:room-runtime:2.6.0")
    implementation("androidx.room:room-ktx:2.6.0")
    // Required: generates the _Impl classes room-runtime looks up at runtime.
    kapt("androidx.room:room-compiler:2.6.0")

    // Firebase Cloud Messaging (optional - for push notifications)
    compileOnly("com.google.firebase:firebase-messaging:23.3.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // A real Android runtime on the JVM - Room, SharedPreferences, Looper, the
    // Application lifecycle - so the SDK facade is tested end to end rather
    // than through hand-written fakes of the platform.
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    // The SDK's real HTTP stack against a local server: what is asserted is
    // the request that actually left the device.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

kapt {
    arguments {
        // Room writes the schema of every database version here, so a
        // migration is checked against what the previous release actually
        // created. exportSchema used to be false and the queue fell back to
        // DESTRUCTIVE migration: an upgrade silently deleted every unsent event.
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

// The published version of both artifacts, read from THIS repository's
// gradle.properties by path. Gradle only reads gradle.properties from the
// ROOT project and the user home, and a React Native app consumes these
// modules as projects of its own build (`include ':joryio-sdk'` +
// projectDir), so `project.findProperty(...)` is null there and an error()
// took every consumer's build down at configuration time (Teamder,
// 2026-09-28). The version only matters when PUBLISHING: a consumer that
// never publishes gets "unspecified" and builds; a publish without the
// property fails at publish time, where it belongs.
val sdkVersion: String = run {
    val props = Properties()
    val own = file("${projectDir.parentFile}/gradle.properties")
    if (own.isFile) own.inputStream().use { props.load(it) }
    (project.findProperty("joryioSdkVersion") as String?)
        ?: props.getProperty("joryioSdkVersion")
        ?: "unspecified"
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "io.joryio"
            artifactId = "joryio-android"
            // ONE number for base and -ui: gradle.properties (joryioSdkVersion), see sdkVersion above.
            version = sdkVersion

            // Maven Central refuses a POM without name, description, url,
            // licenses, developers and scm. The licence is COMMERCIAL (owner
            // decision 2026-10-06): the SDK is not open source, and the LICENSE
            // file in the public repository is the text that applies.
            pom {
                name.set("Joryio Android SDK")
                description.set("Joryio SDK for Android: identity, event tracking, push notifications and in-app message sync. No UI.")
                url.set("https://github.com/joryio/joryio-android-sdk")
                licenses {
                    license {
                        name.set("Joryio SDK Commercial License")
                        url.set("https://github.com/joryio/joryio-android-sdk/blob/main/LICENSE")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("joryio")
                        name.set("Joryio")
                        email.set("support@joryio.com")
                        organization.set("Joryio")
                        organizationUrl.set("https://joryio.com")
                    }
                }
                scm {
                    url.set("https://github.com/joryio/joryio-android-sdk")
                    connection.set("scm:git:https://github.com/joryio/joryio-android-sdk.git")
                    developerConnection.set("scm:git:ssh://git@github.com/joryio/joryio-android-sdk.git")
                }
            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

// The Kotlin target follows the Java target, whatever set it - see the note
// at compileOptions above. Configured lazily so it reads the FINAL value.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = android.compileOptions.targetCompatibility.toString()
}
