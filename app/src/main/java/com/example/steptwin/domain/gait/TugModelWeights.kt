package com.example.steptwin.domain.gait

/**
 * 자동 생성된 TUG 취약도 추정 MLP 가중치 (6 -> 8 -> 3, 은닉=sigmoid, 출력=선형).
 * 선행연구 방향으로 합성한 데이터로 오프라인 학습된 값이며, 온디바이스 추론에만 사용된다.
 * 학습 스크립트는 저장소에 포함하지 않는다(로컬 모델만 유지). DO NOT EDIT BY HAND.
 */
internal object TugModelWeights {
    val W1: Array<FloatArray> = arrayOf(
        floatArrayOf(0.324569f, -0.103743f, 0.432033f, -0.062865f, -0.681117f, 0.226586f),
        floatArrayOf(0.659444f, -0.672375f, 0.179239f, -0.373027f, -0.042067f, -0.178894f),
        floatArrayOf(0.173665f, -0.643636f, -0.298753f, 0.355278f, -0.586888f, 0.289965f),
        floatArrayOf(-0.495590f, 0.023998f, 0.035460f, 0.107701f, 0.924786f, -0.617258f),
        floatArrayOf(0.244330f, -0.003780f, -1.138132f, 0.383226f, -0.139017f, 0.099090f),
        floatArrayOf(-0.657508f, 0.327468f, 0.341659f, -0.368044f, -0.689053f, 0.670002f),
        floatArrayOf(0.812875f, -0.487813f, 0.477463f, -0.494059f, 0.287092f, 0.177728f),
        floatArrayOf(-0.383041f, 0.407388f, 0.385107f, -0.753231f, -0.126552f, -0.237177f),
    )

    val B1: FloatArray = floatArrayOf(-0.680383f, -0.044636f, -0.045422f, -0.231851f, 0.230221f, 0.113331f, -0.390562f, 0.022443f)

    val W2: Array<FloatArray> = arrayOf(
        floatArrayOf(-0.396472f, -0.777328f, -0.218359f, 0.557623f, -0.171948f, 0.933957f, -0.899847f, 0.409903f),
        floatArrayOf(0.344908f, 0.283698f, -0.270419f, -0.008047f, -1.322018f, 0.321735f, 0.651412f, 0.661348f),
        floatArrayOf(-0.513644f, 0.084171f, -0.590279f, 1.211815f, -0.071674f, -0.936382f, 0.299859f, 0.141457f),
    )

    val B2: FloatArray = floatArrayOf(0.737656f, 0.245097f, 0.640136f)
}
