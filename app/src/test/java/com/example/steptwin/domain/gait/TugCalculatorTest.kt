package com.example.steptwin.domain.gait

import org.junit.Assert.assertEquals
import org.junit.Test

class TugCalculatorTest {
    private val calculator = TugCalculator()

    @Test
    fun emptySamplesReturnNeutralWeights() {
        val result = calculator.calculate(emptyList())

        assertEquals(TugWeights.neutral(), result)
    }
}
