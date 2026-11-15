plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hmgapps.family"
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
    implementation(project(":core"))
    // Pleos TextToSpeech SDK (Pleos 음성 합성 플랫폼). 없거나 실패하면 Android 기본 TTS 로 대신 읽는다.
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // Pleos SpeechToText SDK (ON_DEVICE): 음성 놀이(끝말잇기·스무고개·지역 퀴즈)와 "쉬어갈까요?" 대답 듣기. 없으면 화면 입력으로 대신한다.
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    testImplementation("junit:junit:4.13.2")
}
