package com.msight.app.client

data class MapRefPoint(
    val lat: Double,
    val lon: Double,
    val elevationM: Double?
)

/** Signal group identifier carried by a connection to an egress lane. */
data class MapLaneConnection(
    val signalGroup: Int
)

/**
 * Position of a single lane node relative to the intersection reference point.
 *  - [offsetX]: metres east of refPoint
 *  - [offsetY]: metres north of refPoint
 *  - [widthM]: lane width in metres at this node, running total of
 *    intersectionLaneWidth + all dWidth deltas up to and including this node.
 *    Represents the width from this node to the next node along the lane.
 *  - [elevationM]: absolute elevation in metres (refPoint.elevation + cumulative dElevation along
 *    the lane up to this node); null when the intersection carries no elevation data.
 */
data class MapLaneNode(
    val offsetX: Double,
    val offsetY: Double,
    val widthM: Double,
    val elevationM: Double?
)

data class MapLane(
    val laneID: Int,
    val armId: Int,
    val isIngress: Boolean,
    /** Cumulative absolute offsets from refPoint; first node = stop bar, last node = far end. */
    val nodes: List<MapLaneNode>,
    /** Non-empty only for ingress lanes. */
    val connections: List<MapLaneConnection>,
    val leftNeighborId: Int?,
    val rightNeighborId: Int?
)

data class MapArm(
    val armId: Int,
    val ingressLanes: List<MapLane>,
    val egressLanes: List<MapLane>,
    val straightSignalGroups: Set<Int>,
    val leftTurnSignalGroups: Set<Int>,
    val rightTurnSignalGroups: Set<Int>
)

data class MSightIntersectionMap(
    val dbId: Int,
    /** Intersection name — matches MSightSpatEvent.intersectionName for SPaT lookup. */
    val name: String,
    val intersectionId: Int,
    val intersectionRegion: Int?,
    val refPoint: MapRefPoint,
    val centerLat: Double,
    val centerLon: Double,
    /** Default lane width in metres for this intersection; individual nodes may vary via dWidth. */
    val laneWidthM: Double,
    val arms: List<MapArm>
)
