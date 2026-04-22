package com.msight.app.client

actual fun sleepMillis(ms: Long) {
    Thread.sleep(ms)
}

fun main() {
    LocationEmitter().start(10)
}
