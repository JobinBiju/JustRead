plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version: release builds pass -PappVersionName=1.2.3 -PappVersionCode=10203 (see .github/workflows/release.yml).
val appVersionName = providers.gradleProperty("appVersionName").getOrElse("1.0.0")
val appVersionCode = providers.gradleProperty("appVersionCode").map { it.toInt() }.getOrElse(10000)

// Release signing comes from the environment; without it, release builds use the local debug key.
val releaseKeystore = providers.environmentVariable("JUSTREAD_KEYSTORE").orNull?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "app.reader.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.reader.justread"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("JUSTREAD_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("JUSTREAD_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("JUSTREAD_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            // the release key when one is configured, else the local debug key so the APK still installs for testing
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.runtime:runtime:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
}
