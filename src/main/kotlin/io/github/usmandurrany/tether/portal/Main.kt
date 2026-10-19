package io.github.usmandurrany.tether.portal

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlin.system.exitProcess

fun main() {
    val config = try {
        Config.fromEnv()
    } catch (e: IllegalArgumentException) {
        System.err.println("tether-debug: ${e.message}")
        exitProcess(2)
    }
    val store = Store(config.dataDir.resolve("tether-debug.db"))
    Runtime.getRuntime().addShutdownHook(Thread { store.close() })
    println("tether-debug portal listening on port ${config.port} ($config)")
    embeddedServer(Netty, port = config.port) { portal(config, store) }.start(wait = true)
}
