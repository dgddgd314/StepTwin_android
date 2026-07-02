package com.example.steptwin.domain.route

import com.example.steptwin.domain.gait.TugWeights
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCalculatorTest {
    private val calculator = RouteCalculator()

    @Test
    fun routeChangesByDominantVulnerability() {
        val turnVulnerableRoute = calculator.recommend(
            TugWeights(
                speedWeight = 0.1f,
                turnWeight = 1.0f,
                strengthWeight = 0.1f,
            )
        )
        val strengthVulnerableRoute = calculator.recommend(
            TugWeights(
                speedWeight = 0.1f,
                turnWeight = 0.1f,
                strengthWeight = 1.0f,
            )
        )

        val turnEdgeIds = turnVulnerableRoute.segments.map { it.edge.id }
        val strengthEdgeIds = strengthVulnerableRoute.segments.map { it.edge.id }

        assertNotEquals(turnEdgeIds, strengthEdgeIds)
        assertTrue(turnEdgeIds.contains("market-gate-straight"))
        assertTrue(strengthEdgeIds.contains("station-main-road"))
    }
}
