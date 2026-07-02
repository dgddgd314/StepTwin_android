package com.example.steptwin.domain.route

import com.example.steptwin.domain.gait.TugWeights
import java.util.PriorityQueue
import javax.inject.Inject

class RouteCalculator @Inject constructor() {
    private val edges = listOf(
        RouteEdge(
            id = "station-market-gate",
            from = RouteNode.CheongnyangniStation,
            to = RouteNode.GyeongdongMarketGate,
            distanceMeters = 300,
            narrowRisk = 0.4f,
            stairsRisk = 0.0f,
            slopeRisk = 0.2f,
            curbRisk = 0.2f,
            turnRisk = 0.2f,
        ),
        RouteEdge(
            id = "market-gate-alley",
            from = RouteNode.GyeongdongMarketGate,
            to = RouteNode.MarketAlley,
            distanceMeters = 220,
            narrowRisk = 0.9f,
            stairsRisk = 0.1f,
            slopeRisk = 0.2f,
            curbRisk = 0.5f,
            turnRisk = 0.9f,
        ),
        RouteEdge(
            id = "alley-hospital-gate",
            from = RouteNode.MarketAlley,
            to = RouteNode.HospitalGate,
            distanceMeters = 360,
            narrowRisk = 0.8f,
            stairsRisk = 0.3f,
            slopeRisk = 0.3f,
            curbRisk = 0.6f,
            turnRisk = 0.8f,
        ),
        RouteEdge(
            id = "market-gate-straight",
            from = RouteNode.GyeongdongMarketGate,
            to = RouteNode.StraightWalkway,
            distanceMeters = 410,
            narrowRisk = 0.4f,
            stairsRisk = 0.2f,
            slopeRisk = 0.4f,
            curbRisk = 0.5f,
            turnRisk = 0.1f,
        ),
        RouteEdge(
            id = "straight-hospital-gate",
            from = RouteNode.StraightWalkway,
            to = RouteNode.HospitalGate,
            distanceMeters = 370,
            narrowRisk = 0.3f,
            stairsRisk = 0.1f,
            slopeRisk = 0.3f,
            curbRisk = 0.4f,
            turnRisk = 0.1f,
        ),
        RouteEdge(
            id = "station-main-road",
            from = RouteNode.CheongnyangniStation,
            to = RouteNode.MainRoad,
            distanceMeters = 650,
            narrowRisk = 0.2f,
            stairsRisk = 0.0f,
            slopeRisk = 0.1f,
            curbRisk = 0.1f,
            turnRisk = 0.2f,
        ),
        RouteEdge(
            id = "main-road-hospital-gate",
            from = RouteNode.MainRoad,
            to = RouteNode.HospitalGate,
            distanceMeters = 760,
            narrowRisk = 0.2f,
            stairsRisk = 0.0f,
            slopeRisk = 0.2f,
            curbRisk = 0.1f,
            turnRisk = 0.2f,
        ),
        RouteEdge(
            id = "hospital-gate-center",
            from = RouteNode.HospitalGate,
            to = RouteNode.KyungheeMedicalCenter,
            distanceMeters = 160,
            narrowRisk = 0.2f,
            stairsRisk = 0.0f,
            slopeRisk = 0.3f,
            curbRisk = 0.1f,
            turnRisk = 0.2f,
        ),
    )

    fun recommend(
        weights: TugWeights,
        origin: RouteNode = RouteNode.CheongnyangniStation,
        destination: RouteNode = RouteNode.KyungheeMedicalCenter,
    ): RecommendedRoute {
        val nodes = RouteNode.entries
        val distances = nodes.associateWith { Double.POSITIVE_INFINITY }.toMutableMap()
        val previousNode = mutableMapOf<RouteNode, RouteNode>()
        val previousEdge = mutableMapOf<RouteNode, RouteEdge>()
        val queue = PriorityQueue(compareBy<RouteQueueItem> { it.cost })

        distances[origin] = 0.0
        queue += RouteQueueItem(origin, 0.0)

        while (queue.isNotEmpty()) {
            val current = queue.poll() ?: break
            if (current.cost > distances.getValue(current.node)) continue
            if (current.node == destination) break

            adjacentEdges(current.node).forEach { edge ->
                val nextNode = edge.otherNode(current.node)
                val nextCost = current.cost + personalizedCost(edge, weights)
                if (nextCost < distances.getValue(nextNode)) {
                    distances[nextNode] = nextCost
                    previousNode[nextNode] = current.node
                    previousEdge[nextNode] = edge
                    queue += RouteQueueItem(nextNode, nextCost)
                }
            }
        }

        val pathNodes = buildPath(origin, destination, previousNode)
        val segments = pathNodes.windowed(size = 2).mapNotNull { (from, to) ->
            val edge = previousEdge[to] ?: return@mapNotNull null
            RouteSegment(
                from = from,
                to = to,
                edge = edge,
                cost = personalizedCost(edge, weights),
            )
        }

        return RecommendedRoute(
            segments = segments,
            totalDistanceMeters = segments.sumOf { it.edge.distanceMeters },
            totalCost = segments.sumOf { it.cost },
            reason = routeReason(weights),
            weights = weights,
        )
    }

    private fun adjacentEdges(node: RouteNode): List<RouteEdge> {
        return edges.filter { it.from == node || it.to == node }
    }

    private fun RouteEdge.otherNode(node: RouteNode): RouteNode {
        return if (from == node) to else from
    }

    private fun personalizedCost(edge: RouteEdge, weights: TugWeights): Double {
        val baseDistanceCost = edge.distanceMeters / 100.0
        val speedPenalty = weights.speedWeight * ((edge.distanceMeters / 120.0) + edge.narrowRisk * 5.0)
        val turnPenalty = weights.turnWeight * edge.turnRisk * 14.0
        val strengthPenalty = weights.strengthWeight * (
            edge.stairsRisk * 12.0 +
                edge.slopeRisk * 8.0 +
                edge.curbRisk * 10.0
            )

        return baseDistanceCost + speedPenalty + turnPenalty + strengthPenalty
    }

    private fun buildPath(
        origin: RouteNode,
        destination: RouteNode,
        previousNode: Map<RouteNode, RouteNode>,
    ): List<RouteNode> {
        val reversed = mutableListOf(destination)
        var current = destination

        while (current != origin) {
            current = previousNode[current] ?: return emptyList()
            reversed += current
        }

        return reversed.asReversed()
    }

    private fun routeReason(weights: TugWeights): String {
        return when {
            weights.strengthWeight >= weights.turnWeight && weights.strengthWeight >= weights.speedWeight ->
                "근력 취약도가 높아 계단, 턱, 경사 비용을 크게 반영했습니다."
            weights.turnWeight >= weights.speedWeight ->
                "회전 취약도가 높아 방향 전환이 적은 구간을 우선했습니다."
            else ->
                "보행 속도 취약도가 높아 거리와 좁은 보도 부담을 함께 줄였습니다."
        }
    }

    private data class RouteQueueItem(
        val node: RouteNode,
        val cost: Double,
    )
}
