package com.msight.app.client

/**
 * Intersection geometry derived from the SAE J2735 MAP message served by MSight Cloud.
 *
 * The cloud returns MAP data in J2735 form, where lane geometry is a chain of *relative* node
 * deltas from the intersection's reference point. The parser in `MSightClient` flattens those
 * deltas into the absolute metre offsets held here, and groups lanes into [MapArm]s, so consumers
 * never have to walk the J2735 structure themselves.
 *
 * Coordinates: every offset in this file is metres in a local east/north frame centred on
 * [MSightIntersectionMap.refPoint] — `offsetX` east, `offsetY` north. Converting back to
 * latitude/longitude is a flat-earth approximation valid over the ~200 m an intersection spans.
 */

/** The intersection's reference point: the origin of the local east/north offset frame. */
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

/**
 * One lane of the intersection, as a polyline of [nodes].
 *
 * Crosswalk lanes are dropped during parsing, so every instance here is a vehicle lane.
 *
 * @property laneID Lane identifier, unique within the intersection.
 * @property armId Identifier of the approach this lane belongs to; all lanes sharing an `armId`
 *   form one [MapArm].
 * @property isIngress True for lanes carrying traffic *into* the intersection (the ones a driver
 *   approaches on, and the only ones with signal groups), false for egress lanes.
 * @property leftNeighborId Lane immediately to the left, or null at the edge of the roadway.
 *   Whether that neighbour is ingress or egress is how [MapArm]'s movement classification tells a
 *   left-turn pocket from a through lane.
 * @property rightNeighborId Lane immediately to the right, or null at the edge of the roadway.
 */
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

/**
 * One approach (arm) of the intersection: all lanes entering and leaving on the same leg, plus
 * the signal groups that govern each movement from that leg.
 *
 * The signal-group sets are what let a SPaT be turned into a colour a driver recognises: once
 * the device is matched to an arm, [MSightApproachDetector.extractArmSignals] looks up these
 * groups in the latest [SpatIntersection]. They are derived from lane-neighbour geometry rather
 * than read directly from the MAP, because J2735 records which egress lane a movement connects
 * to, not what a driver would call that movement.
 */
data class MapArm(
    val armId: Int,
    val ingressLanes: List<MapLane>,
    val egressLanes: List<MapLane>,
    /** Signal groups governing the through movement from this arm. */
    val straightSignalGroups: Set<Int>,
    /** Signal groups governing the left turn from this arm. */
    val leftTurnSignalGroups: Set<Int>,
    /** Signal groups governing the right turn from this arm. Not currently populated. */
    val rightTurnSignalGroups: Set<Int>
)

/**
 * A complete intersection map as returned by `GET /v1/maps/search` or `GET /v1/maps/{name}`.
 *
 * @property dbId MSight Cloud's own row id for this map.
 * @property name Intersection name — matches MSightSpatEvent.intersectionName for SPaT lookup.
 * @property intersectionId J2735 intersection id, matching [SpatIntersectionId.id].
 * @property refPoint Origin of the local offset frame used by every [MapLaneNode] below.
 * @property centerLat Latitude the cloud indexes this map by for radius search. Close to, but not
 *   necessarily identical with, [refPoint].
 * @property centerLon Longitude the cloud indexes this map by for radius search.
 */
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
