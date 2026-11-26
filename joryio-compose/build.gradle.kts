import java.util.Properties

// Jetpack Compose for Joryio placements: JoryioPlacement(placementKey).
//
// A module of its own so an app without Compose never pulls the Compose
// runtime or needs the Compose compiler - joryio and joryio-ui stay
// View-only (the React Native and Unity bridges build them from source).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

val sdkVersion: String = run {
    val props = Properties()
    val own = file("${projectDir.parentFile}/gradle.properties")
    if (own.isFile) own.inputStream().use { props.load(it) }
    (project.findProperty("joryioSdkVersion") as String?)
        ?: props.getProperty("joryioSdkVersion")
        ?: "unspecified"
}

android {
    namespace = "io.joryio.sdk.compose"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        targetSdk = 34
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        // The Compose compiler that matches Kotlin 1.9.20 (the SDK's Kotlin).
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    api(project(if (rootProject.findProject(":joryio-ui") != null) ":joryio-ui" else ":joryio-sdk-ui"))
    // compileOnly would leave an app without Compose free of it, but this
    // module exists only for Compose apps; `api` keeps the versions honest.
    api("androidx.compose.runtime:runtime:1.5.4")
    api("androidx.compose.ui:ui:1.5.4")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "io.joryio"
            artifactId = "joryio-android-compose"
            version = sdkVersion
            pom {
                name.set("Joryio Android SDK Compose")
                description.set("Jetpack Compose placements for the Joryio Android SDK. Depends on io.joryio:joryio-android-ui at the same version.")
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = android.compileOptions.targetCompatibility.toString()
}
