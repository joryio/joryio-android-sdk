import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// base and -ui are released in LOCKSTEP at the same number, which is what stops
// a consumer assembling an incompatible pair (the common pattern for split
// base/-ui SDKs). The number lives ONCE, in gradle.properties
// (joryioSdkVersion), read by both modules.
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

android {
    namespace = "io.joryio.sdk.ui"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        targetSdk = 34
        // Shipped inside the AAR; see the file. It was declared and missing.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Must match :joryio exactly - see the long note in its build file: Java 11
    // bytecode for Unity 2021.3/2022.3 (JDK 11), and the Kotlin target derived
    // from this value (block at the end of the file) so React Native's root
    // plugin, which forces 17 on every subproject, never splits the two.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Sources + Javadoc jars: Maven Central refuses a release without them.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // The base SDK. `api`, not `implementation`: an app that depends on -ui gets
    // base's public surface (Joryio.track, identify, ...) without naming it, so
    // ONE coordinate is the whole integration.
    //
    // The PATH is resolved rather than hard-coded, because a Gradle project
    // path belongs to whoever registered it. In this build base is `:joryio`;
    // an app including the SDK by directory - which is how the demo apps and
    // any monorepo consumer link it - registers it as `:joryio-sdk`. Hard-coding
    // either one fails in the other build with "Project with path ':joryio'
    // could not be found", which is a confusing error for something that is
    // really just a naming difference.
    api(project(if (rootProject.findProject(":joryio") != null) ":joryio" else ":joryio-sdk"))

    // The same exact pin in Gradle MODULE METADATA. The POM pin below only
    // binds Maven consumers: Gradle reads the .module file first, where the
    // project dependency published as a plain `requires`, so a Gradle app that
    // also asked for a newer base silently got a mismatched pair - and the
    // reflective presenter lookup fails by displaying nothing, not by failing
    // to build. A constraint on the published coordinate is ignored inside a
    // build that links base as a project (nothing resolves that coordinate
    // there), so it binds exactly the published case. Only when there is a
    // real version: "unspecified" (a consumer build) has nothing to pin.
    if (sdkVersion != "unspecified") {
        constraints {
            api("io.joryio:joryio-android") {
                version { strictly(sdkVersion) }
                because("joryio-android-ui reflects into joryio-android; the two ship in lockstep")
            }
        }
    }

    // `api` like base: the public surface exposes Kotlin types.
    api("org.jetbrains.kotlin:kotlin-stdlib:1.9.20")
    implementation("androidx.core:core-ktx:1.12.0")

    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "io.joryio"
            artifactId = "joryio-android-ui"
            version = sdkVersion

            // Maven Central refuses a POM without name, description, url,
            // licenses, developers and scm. The licence is COMMERCIAL (owner
            // decision 2026-10-06): the SDK is not open source, and the LICENSE
            // file in the public repository is the text that applies.
            pom {
                name.set("Joryio Android SDK UI")
                description.set("In-app message rendering for the Joryio Android SDK. Depends on io.joryio:joryio-android at the same version.")
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

                // Pin base to the EXACT same version rather than letting Gradle
                // resolve a range. The reflective lookup between the two
                // artifacts (base finds DefaultInAppMessagePresenter by name)
                // has no compile-time check, so a mismatched pair would fail by
                // displaying nothing rather than by failing to build. An exact
                // pin makes that impossible at resolution time.
                pom.withXml {
                    // `from(components["release"])` already wrote the
                    // <dependencies> element AND the joryio-android entry (from
                    // `api(project(":joryio"))`). Appending a second
                    // <dependencies> made an invalid POM that Gradle refused
                    // to publish (Teamder, 2026-09-28); appending a second
                    // joryio-android entry would be a duplicate declaration.
                    // So: find the entry that is there and pin ITS version.
                    val root = asNode()
                    val nameOf = { n: Any? -> (n as? groovy.util.Node)?.name().toString().substringAfter('}') }
                    val deps = (root.children().firstOrNull { nameOf(it) == "dependencies" } as? groovy.util.Node)
                        ?: root.appendNode("dependencies")
                    val existing = deps.children().filterIsInstance<groovy.util.Node>().firstOrNull { dep ->
                        dep.children().filterIsInstance<groovy.util.Node>().any { nameOf(it) == "artifactId" && it.text() == "joryio-android" }
                    }
                    if (existing != null) {
                        val version = existing.children().filterIsInstance<groovy.util.Node>().firstOrNull { nameOf(it) == "version" }
                        if (version != null) version.setValue("[$sdkVersion]") else existing.appendNode("version", "[$sdkVersion]")
                    } else {
                        deps.appendNode("dependency").apply {
                            appendNode("groupId", "io.joryio")
                            appendNode("artifactId", "joryio-android")
                            appendNode("version", "[$sdkVersion]")
                            appendNode("scope", "compile")
                        }
                    }
                }
            }
        }
    }
}

// The Kotlin target follows the Java target - see :joryio's build file.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = android.compileOptions.targetCompatibility.toString()
}
