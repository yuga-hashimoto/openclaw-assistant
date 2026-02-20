plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.openclaw.assistant.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Encrypted SharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Tink (required by security-crypto)
    implementation("com.google.crypto.tink:tink-android:1.10.0")

    // Coroutines (exposed via api — Flow types used in public API)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room (exposed via api — entity/DAO types used in public API)
    val roomVersion = "2.6.1"
    api("androidx.room:room-runtime:$roomVersion")
    api("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Firebase Crashlytics (used by OpenClawClient for error logging)
    implementation(platform("com.google.firebase:firebase-bom:32.7.4"))
    implementation("com.google.firebase:firebase-crashlytics")
}
