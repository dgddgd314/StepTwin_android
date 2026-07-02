package com.example.steptwin.ui.map

/**
 * 카카오 지도 네이티브 라이브러리 로드 성공 여부.
 *
 * 카카오 지도 SDK 는 arm64/armeabi 네이티브 라이브러리만 제공한다.
 * x86/x86_64 에뮬레이터에서는 로드에 실패하므로, 그 경우 지도 대신 안내를 보여준다.
 */
object MapSupport {
    @Volatile
    var available: Boolean = false
}
