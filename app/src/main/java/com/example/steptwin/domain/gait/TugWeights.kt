package com.example.steptwin.domain.gait

data class TugWeights(
    val speedWeight: Float,
    val turnWeight: Float,
    val strengthWeight: Float,
) {
    companion object {
        fun neutral() = TugWeights(
            speedWeight = 0.35f,
            turnWeight = 0.35f,
            strengthWeight = 0.35f,
        )
    }
}

data class TugAnalysisResult(
    val weights: TugWeights,
    val syncedToServer: Boolean,
    val syncMessage: String?,
)
