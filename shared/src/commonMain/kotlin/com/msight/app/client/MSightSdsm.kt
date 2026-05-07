package com.msight.app.client

import kotlinx.serialization.Serializable

@Serializable
data class SdsmRefPos(
    val lat: Double,
    val long: Double
)

@Serializable
data class SdsmOffset(
    val offsetX: Double,
    val offsetY: Double
)

@Serializable
data class SdsmPosConfidence(
    val pos: String,
    val elevation: String
)

@Serializable
data class SdsmTimestamp(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Double,
    val offset: Int
)

@Serializable
data class SdsmRefPosConf(
    val semiMajor: Double,
    val semiMinor: Double,
    val orientation: Double
)

@Serializable
data class SdsmVehicleSize(
    val width: Double,
    val length: Double
)

data class SdsmDetectedObject(
    val objectType: String,
    val objTypeCfd: Int,
    val objectID: Int,
    val measurementTime: Double,
    val timeConfidence: String,
    val pos: SdsmOffset,
    val posConfidence: SdsmPosConfidence,
    val speed: Double,
    val speedConfidence: String,
    val heading: Double,
    val headingConf: String,
    val vehicleSize: SdsmVehicleSize?,
    val vehicleClass: Int?
)
