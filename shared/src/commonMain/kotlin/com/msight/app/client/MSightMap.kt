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

/** Meter offsets from the intersection refPoint — same convention as SdsmOffset (x=east, y=north). */
data class MapLaneNode(
    val offsetX: Double,
    val offsetY: Double
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
    /** Bearing (0–360°, clockwise from north) a driver is heading when approaching this arm. */
    val approachBearingDeg: Double,
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
    val arms: List<MapArm>
)
