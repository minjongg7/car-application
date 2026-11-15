plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// NaviHelper(NAVI_ROUTE 권한) 사용 스위치. 사전 승인이 안 된 채 제출해야 하면 -PnaviHelper=false 로 빌드하고
// AndroidManifest.xml 의 NAVI_ROUTE 선언도 지운다 (README "NAVI_ROUTE 승인" 참고).
val naviEnabled = (project.findProperty("naviHelper") as String?) != "false"

android {
    namespace = "com.fatigueguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fatigueguard.pro"
        minSdk = 28            // Pleos 요구사항: API 28 이상
        targetSdk = 35
        versionCode = 4
        versionName = "0.6.0"
        buildConfigField("boolean", "NAVI_HELPER_ENABLED", naviEnabled.toString())
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true // 디버그 전용 SDK 탐색 버튼에 BuildConfig.DEBUG 사용
    }
    // AAOS CarPropertyManager(속도). compileSdk 에 android.car 옵션 라이브러리가 없으면 compileSdk 를 34 로 낮출 것.
    useLibrary("android.car")
}

dependencies {
    // 공용 모듈 + 추가 앱 2종 (같은 APK 안에 들어가지만 런처에는 별도 앱 아이콘으로 보인다)
    implementation(project(":core"))
    implementation(project(":feature-energy"))
    implementation(project(":feature-care"))
    implementation(project(":feature-family"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Vector map renderer: 3D buildings and tilted automotive camera.
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    // Pleos Vehicle SDK (샘플 앱 README 기준 좌표/버전)
    implementation("ai.pleos.playground:Vehicle:2.3.2")

    // NaviHelper (여러 참가팀 저장소와 Pleos 문서에서 확인한 좌표/버전)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")

    testImplementation("junit:junit:4.13.2")
    // JVM 단위 테스트에서 org.json 이 android.jar 스텁으로 막히지 않게 (OSRM/Photon 파서 테스트용)
    testImplementation("org.json:json:20240303")
}
