plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ghostkey.app"
    compileSdk = 34

    // === VERSION CONFIG ===
    // CI (GitHub Actions): versionCode = github.run_number (auto-increments)
    // F-Droid: builds from git tag, ignores CI, uses the fallback number below.
    //
    // ⚠️  GOLDEN RULE for every new release:
    //   1. Bump versionName (e.g. "3.0.2")
    //   2. Bump the fallback number below (e.g. 30)
    //   3. Add fastlane/metadata/android/{en-US,ar}/changelogs/<fallback>.txt
    //   4. git commit + git push → CI auto-tags + builds + releases
    val ciVersionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 29

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!ksFile.isNullOrBlank()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "ghostkey"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "com.ghostkey.app"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = "3.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use release keystore when available (CI); omit signing otherwise
            // so F-Droid can apply its own signature cleanly.
            val releaseConfig = signingConfigs.findByName("release")
            signingConfig = if (releaseConfig?.storeFile != null) releaseConfig else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/*.version"
            excludes += "META-INF/*.kotlin_module"
            excludes += "org/bouncycastle/pqc/**"
            excludes += "org/bouncycastle/x509/**"
        }
    }
}

dependencies {
    // AndroidX & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Jetpack Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Standalone Embedded OpenPGP Engine (Bouncy Castle)
    implementation("org.bouncycastle:bcpg-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // OkHttp with Tor SOCKS5 Proxy & WebSocket support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR Code Generation & Scanning (In-Person Pairing)
    implementation("com.google.zxing:core:3.5.3")

    // CameraX for Live In-Person QR Code Scanning
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

