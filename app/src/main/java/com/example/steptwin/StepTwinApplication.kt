package com.example.steptwin

import android.app.Application
import android.util.Log
import com.example.steptwin.ui.map.MapSupport
import com.kakao.vectormap.KakaoMapSdk
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class StepTwinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 카카오 지도 SDK 초기화. 키는 local.properties 의 KAKAO_NATIVE_APP_KEY 에서 주입된다.
        // 카카오 지도는 ARM 네이티브 라이브러리만 제공하므로 x86/x86_64 기기에서는 로드에 실패한다.
        // UnsatisfiedLinkError 는 Error(Exception 아님)라 Throwable 로 잡는다.
        try {
            KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
            MapSupport.available = true
        } catch (t: Throwable) {
            MapSupport.available = false
            Log.e(
                "StepTwin",
                "카카오 지도 SDK 초기화 실패 — 이 기기 ABI(예: x86_64 에뮬레이터)에서는 지도를 지원하지 않습니다. 실제 ARM 폰에서 실행하세요.",
                t,
            )
        }
    }
}
