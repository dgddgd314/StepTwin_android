package com.example.steptwin

import android.app.Application
import com.kakao.vectormap.KakaoMapSdk
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StepTwinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 카카오 지도 SDK 초기화. 키는 local.properties 의 KAKAO_NATIVE_APP_KEY 에서 주입된다.
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
    }
}
