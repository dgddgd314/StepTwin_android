import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

// local.properties 에서 민감/환경 값을 읽는다 (VCS 에 커밋되지 않음).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// 카카오 네이티브 앱 키. 각자 local.properties 에 KAKAO_NATIVE_APP_KEY=... 로 넣는다.
val kakaoNativeAppKey: String = localProperties.getProperty("KAKAO_NATIVE_APP_KEY") ?: ""

// 카카오 REST API 키 (로컬 검색/지오코딩용)
val kakaoRestApiKey: String = localProperties.getProperty("KAKAO_REST_API_KEY") ?: ""

// (선택) Anthropic API 키 — 말벗 자유 대화(Claude)용
val anthropicApiKey: String = localProperties.getProperty("ANTHROPIC_API_KEY") ?: ""

// (선택) ElevenLabs — 말벗 답변 음성 합성용(키/보이스ID 없으면 기기 내장 TTS 폴백)
val elevenLabsApiKey: String = localProperties.getProperty("ELEVENLABS_API_KEY") ?: ""
val elevenLabsVoiceId: String = localProperties.getProperty("ELEVENLABS_VOICE_ID") ?: ""

// 데모 서버 주소. 폰에서 접속 가능한 호스트 IP 를 local.properties 로 덮어쓸 수 있다.
// 빈 문자열(CI 시크릿 미설정 등)이면 기본값으로 폴백 — Retrofit baseUrl 크래시 방지.
val serverBaseUrl: String = localProperties.getProperty("SERVER_BASE_URL")
    ?.takeIf { it.isNotBlank() } ?: "http://172.30.1.66:8000/"

// release 서명. 값은 환경변수(CI) 또는 local.properties 에서 읽는다(둘 다 커밋 안 함).
// keystore 파일이 있을 때만 서명하고, 없으면 unsigned release 로 빌드(빌드는 성공).
fun signingValue(key: String): String? =
    System.getenv(key) ?: localProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = signingValue("RELEASE_STORE_FILE")
val hasReleaseSigning: Boolean = releaseStoreFile != null && file(releaseStoreFile).exists()

android {
    namespace = "com.example.steptwin"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.steptwin"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoNativeAppKey\"")
        buildConfigField("String", "KAKAO_REST_API_KEY", "\"$kakaoRestApiKey\"")
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"$elevenLabsApiKey\"")
        buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"$elevenLabsVoiceId\"")
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = signingValue("RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("RELEASE_KEY_ALIAS")
                keyPassword = signingValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.kakao.map)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    ksp(libs.hilt.android.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
