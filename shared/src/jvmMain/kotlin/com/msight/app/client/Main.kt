package com.msight.app.client

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Desktop smoke test for the shared library.
 *
 * Runs a real [MSightClient] against a real MSight Cloud deployment with a simulated location
 * source, printing every event it receives. It is the fastest way to check that a deployment is
 * reachable, that this client's ids are registered, and that the message parsers handle what the
 * cloud is actually sending — no device, no emulator, no UI.
 *
 * Run it with `./gradlew :shared:runJvmMain`. Every setting can be overridden by environment
 * variable or JVM system property; see the defaults below.
 */

private object JvmPlatformContext : PlatformContext()

/** Negative means run until interrupted. */

private const val DEFAULT_TEST_RUNTIME_MILLIS = -1L
// No default: a cloud URL is deployment-specific, and baking one in would both leak whichever
// deployment it names and let a misconfigured run silently connect to the wrong place. Supply it
// via MSIGHT_CLOUD_URL or -Dmsight.cloudUrl.
private const val DEFAULT_CLOUD_URL = ""
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

    if (cloudUrl.isBlank()) {
        println(
            """
            No MSight Cloud URL configured.

            Set the base URL of your MSight Cloud deployment — the HttpApiUrl printed by its
            CDK deploy — and run again:

                MSIGHT_CLOUD_URL=https://your-deployment.example.com ./gradlew :shared:runJvmMain

            Optional overrides: MSIGHT_APP_ID (default "$DEFAULT_APP_ID"),
            MSIGHT_CLIENT_ID (default "$DEFAULT_CLIENT_ID"),
            MSIGHT_TEST_RUNTIME_MILLIS (negative runs until interrupted).
            Each also accepts a JVM system property, e.g. -Dmsight.cloudUrl=...
            """.trimIndent()
        )
        return@runBlocking
    }

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

/** Reads a setting from the environment, then a JVM system property, then the default. */
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
