plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offerpilot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.offerpilot.app"
        minSdk = 30
        targetSdk = 35
        // CI run number, so every published build is a newer version than the last one
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        // Release tags (v1.2.3) set FKUB_VERSION_NAME; other builds are 0.1.<run number>
        versionName = System.getenv("FKUB_VERSION_NAME") ?: ("0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0"))
    }

    // A fixed debug key checked into the repo. Without it every CI runner generates its own debug key, the APK's
    // signature changes on every build, and Android refuses to install it over the previous one.
    // This key only signs public debug builds; it is not a secret.
    signingConfigs {
        getByName("debug") {
            storeFile = file("fkub-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release key: held only by the maintainer. CI decodes it from GitHub Actions secrets into a temp file;
        // it is never committed. Without it, release builds are left unsigned (never signed with the public debug key).
        val releaseStore = System.getenv("FKUB_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = System.getenv("FKUB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FKUB_KEY_ALIAS")
                keyPassword = System.getenv("FKUB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // On-device text recognition (bundled model: no Play Services download, no network)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
