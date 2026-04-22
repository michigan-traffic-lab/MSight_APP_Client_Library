package com.msight.app.androidapp

data class RecordedPoint(
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double
)

data class TrialRecord(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val points: List<RecordedPoint>
)
