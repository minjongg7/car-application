pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Maven 저장소 (Pleos-Playground/pleos-sample-vehicle-app README 기준). Pleos 그룹만 이 저장소에서 가져온다.
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            content { includeGroupByRegex("ai\\.pleos\\.playground(\\..*)?") } // TTS SDK 가 ai.pleos.playground.security 그룹도 쓴다
        }
    }
}
rootProject.name = "차량용 앱"
// :app            네비게이션 베이스 앱 (피로도 가드 포함, 메인 런처)
// :core           공용 차량 에너지 데이터/설정/테마
// :feature-energy 추가 앱 ① Energy Trading Hub (V2L 스마트 방전 + V2G 대시보드)
// :feature-care   추가 앱 ② Predictive Care (BMS 시각화 + 예측 정비)
// :feature-family 추가 화면 ③ Family Care (나이별 동승자 콘텐츠 + 휴식 제안)
include(":app", ":core", ":feature-energy", ":feature-care", ":feature-family")
