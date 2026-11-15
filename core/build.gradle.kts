plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hmgapps.core"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    api(composeBom)
    api("androidx.compose.material3:material3")
    api("androidx.compose.ui:ui")
    api("androidx.activity:activity-compose:1.9.2")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Pleos Vehicle SDK: 배터리·전비·주행가능거리·주행시간·기어·브레이크액 (모두 normal 권한)
    api("ai.pleos.playground:Vehicle:2.3.2")
    testImplementation("junit:junit:4.13.2")
}
