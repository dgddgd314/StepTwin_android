package com.example.steptwin.domain.route

import com.example.steptwin.domain.gait.TugWeights

enum class RouteNode(val label: String) {
    CheongnyangniStation("청량리역"),
    GyeongdongMarketGate("경동시장 입구"),
    MarketAlley("시장 골목 구간"),
    StraightWalkway("직선 보도축"),
    MainRoad("큰길 우회로"),
    HospitalGate("경희의료원 입구"),
    KyungheeMedicalCenter("경희의료원"),
}

data class RouteEdge(
    val id: String,
    val from: RouteNode,
    val to: RouteNode,
    val distanceMeters: Int,
    val narrowRisk: Float,
    val stairsRisk: Float,
    val slopeRisk: Float,
    val curbRisk: Float,
    val turnRisk: Float,
)

data class RouteSegment(
    val from: RouteNode,
    val to: RouteNode,
    val edge: RouteEdge,
    val cost: Double,
)

data class RecommendedRoute(
    val segments: List<RouteSegment>,
    val totalDistanceMeters: Int,
    val totalCost: Double,
    val reason: String,
    val weights: TugWeights,
)
