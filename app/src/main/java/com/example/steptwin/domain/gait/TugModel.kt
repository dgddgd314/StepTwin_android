package com.example.steptwin.domain.gait

import kotlin.math.exp

/**
 * 온디바이스 TUG 취약도 추정 모델의 순수 Kotlin 추론기.
 * 오프라인 학습된 MLP(6 -> 8 -> 3, 은닉=sigmoid, 출력=선형) 가중치([TugModelWeights])를 사용한다.
 * 외부 런타임/의존성 없이 forward pass 만 수행한다.
 */
internal object TugModel {

    /** 정규화된 6개 feature 를 입력받아 [속도, 회전, 근력] 취약도(0~1)를 반환한다. */
    fun predict(features: FloatArray): FloatArray {
        val w1 = TugModelWeights.W1
        val b1 = TugModelWeights.B1
        val w2 = TugModelWeights.W2
        val b2 = TugModelWeights.B2

        val hidden = FloatArray(b1.size) { j ->
            var sum = b1[j]
            for (i in features.indices) sum += w1[j][i] * features[i]
            sigmoid(sum)
        }

        return FloatArray(b2.size) { k ->
            var sum = b2[k]
            for (j in hidden.indices) sum += w2[k][j] * hidden[j]
            sum.coerceIn(0f, 1f)
        }
    }

    private fun sigmoid(z: Float): Float = 1f / (1f + exp(-z))
}
