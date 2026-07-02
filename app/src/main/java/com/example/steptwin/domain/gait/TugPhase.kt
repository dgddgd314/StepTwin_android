package com.example.steptwin.domain.gait

/** TUG 동작 구간 자동 분류 결과. */
enum class TugPhase {
    Still, // 정지 / 자세 전환(앉기·일어서기)
    Walk, // 보행
    Turn, // 회전
}
