plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raza.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.raza.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"

        // Optional build-time default backend URL: -PrazaApiBaseUrl=https://your-host
        // Empty by default: the app then shows "cloud AI not set up" instead of guessing.
        val defaultApi = (project.findProperty("razaApiBaseUrl") as String?) ?: ""
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$defaultApi\"")
    }

    // One fixed signing key for every build. Without it each CI run generates a new random debug key, and Android
    // refuses to install a build over an older one signed with a different key ("App not installed").
    // This key is committed on purpose for sideloaded personal builds. It is NOT a Play Store release key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("raza-debug.keystore")
            storePassword = "razadebug"
            keyAlias = "razadebug"
            keyPassword = "razadebug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Keep Java and Kotlin bytecode targets identical.
    // This fixes the reported 1.8 vs 17 compile task incompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Encrypted storage for the backend access token.
    implementation("androidx.security:security-crypto:1.0.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar copy is only a stub).
    testImplementation("org.json:json:20240303")
}
