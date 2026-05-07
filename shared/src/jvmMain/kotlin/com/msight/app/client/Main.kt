package com.msight.app.client

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private object JvmPlatformContext : PlatformContext()

private const val DEFAULT_TEST_RUNTIME_MILLIS = -1L
private const val DEFAULT_CLOUD_URL = "https://7hmptbe8s3.execute-api.us-east-2.amazonaws.com"
private const val DEFAULT_APP_ID = "msight-demo"
private const val DEFAULT_CLIENT_ID = "client-001"
private const val DEFAULT_ROAD_USER_SUBTYPE = "passenger_car"

fun main() = runBlocking {
    val runtimeMillis = readLongSetting(
        envName = "MSIGHT_TEST_RUNTIME_MILLIS",
        propertyName = "msight.test.runtimeMillis",
        defaultValue = DEFAULT_TEST_RUNTIME_MILLIS
    )
    val cloudUrl = readStringSetting(
        envName = "MSIGHT_CLOUD_URL",
        propertyName = "msight.cloudUrl",
        defaultValue = DEFAULT_CLOUD_URL
    )
    val appId = readStringSetting(
        envName = "MSIGHT_APP_ID",
        propertyName = "msight.appId",
        defaultValue = DEFAULT_APP_ID
    )
    val clientId = readStringSetting(
        envName = "MSIGHT_CLIENT_ID",
        propertyName = "msight.clientId",
        defaultValue = DEFAULT_CLIENT_ID
    )

    println("Starting MSightClient JVM smoke test")
    println("cloudUrl=$cloudUrl")
    println("appId=$appId")
    println("clientId=$clientId")
    println("runtimeMillis=$runtimeMillis")

    val client = try {
        MSightClient(
            context = JvmPlatformContext,
            config = MSightClientConfig(
                cloudUrl = cloudUrl,
                appId = appId,
                clientId = clientId,
                roadUserType = MSightRoadUserType.VEHICLE,
                roadUserSubType = DEFAULT_ROAD_USER_SUBTYPE,
                deviceType = MSightDeviceType.CELLPHONE,
                locationUpdateFrequencyHz = 1.0
            )
        )
    } catch (throwable: Throwable) {
        println("Failed to create MSightClient: ${describeThrowable(throwable)}")
        return@runBlocking
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("Shutdown requested; closing MSightClient")
            client.close()
        }
    )

    val eventCollectionJob = launch {
        client.events.collect { event ->
            printEvent(event)
        }
    }

    println("MSightClient constructed; starting background client work")
    client.start()

    if (runtimeMillis < 0L) {
        println("Running until terminated because runtimeMillis is negative. Press Ctrl+C to stop.")
        awaitCancellation()
    }

    println("Waiting ${runtimeMillis}ms to observe websocket and upload logs")
    delay(runtimeMillis)
    println("Closing MSightClient")
    eventCollectionJob.cancelAndJoin()
    client.close()
}

private fun printEvent(event: MSightEvent) {
    when (event) {
        is MSightSimpleWarning -> println(
            "Received MSightSimpleWarning: timestampMillis=${event.timestampMillis}, message=${event.message}"
        )
        is MSightSdsmEvent -> {
            println(
                "Received MSightSdsmEvent: sensor=${event.sensorName} device=${event.deviceName} " +
                "frameId=${event.frameId} msgCnt=${event.msgCnt} objects=${event.objects.size} " +
                "captureTimestamp=${event.captureTimestamp}"
            )
            event.objects.forEach { obj ->
                val sizeStr = obj.vehicleSize?.let { " size=${it.width}x${it.length}m" } ?: ""
                println(
                    "  Object #${obj.objectID} type=${obj.objectType} " +
                    "offsetX=${obj.pos.offsetX} offsetY=${obj.pos.offsetY} " +
                    "speed=${obj.speed} heading=${obj.heading}$sizeStr"
                )
            }
        }
        else -> println("Received event: $event")
    }
}

private fun readStringSetting(
    envName: String,
    propertyName: String,
    defaultValue: String
): String {
    return System.getenv(envName)
        ?.takeIf { it.isNotBlank() }
        ?: System.getProperty(propertyName)
            ?.takeIf { it.isNotBlank() }
        ?: defaultValue
}

private fun readLongSetting(
    envName: String,
    propertyName: String,
    defaultValue: Long
): Long {
    val rawValue = readStringSetting(envName, propertyName, defaultValue.toString())
    return rawValue.toLongOrNull() ?: defaultValue
}

private fun describeThrowable(throwable: Throwable): String {
    val segments = mutableListOf<String>()
    var current: Throwable? = throwable

    while (current != null) {
        segments += current.toString()
        current = current.cause
    }

    return segments.joinToString(" <- caused by ")
}
