package com.msight.app.client

private object JvmPlatformContext : PlatformContext()

private const val DEFAULT_TEST_RUNTIME_MILLIS = 10000L
private const val DEFAULT_CLOUD_URL = "https://yj9zamc3jf.execute-api.us-east-1.amazonaws.com"
private const val DEFAULT_APP_ID = "msight-demo"
private const val DEFAULT_CLIENT_ID = "client-001"
private const val DEFAULT_ROAD_USER_SUBTYPE = "passenger_car"

fun main() {
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
        throw throwable
    }

    println("MSightClient constructed; starting location updates")
    client.start()
    println("Waiting ${runtimeMillis}ms to observe websocket and upload logs")
    Thread.sleep(runtimeMillis)
    println("Closing MSightClient")
    client.close()
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
