import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// AdMob IDs
val admobAppId = "ca-app-pub-9448422299959897~7087584222"
val admobBannerId = "ca-app-pub-9448422299959897/1128855548"
// Google's official TEST banner unit (used for debug builds so you never click your own real ads)
val admobTestBannerId = "ca-app-pub-3940256099942544/9214589741"

// Optional release signing (provided by GitHub Actions secrets or your local environment)
val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.quatrang.volumemixer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.quatrang.volumemixer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["admobAppId"] = admobAppId
    }

    signingConfigs {
        if (!signingKeystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$admobTestBannerId\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$admobBannerId\"")
            signingConfig = if (!signingKeystorePath.isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                // Lets you install the release APK for testing even without your own keystore.
                // Use your own keystore before uploading to Google Play.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Shizuku: gives the app ADB-level (shell) access without root
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Allows reflection on hidden framework audio APIs
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // AdMob + consent (GDPR) form
    implementation("com.google.android.gms:play-services-ads:24.4.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
}
