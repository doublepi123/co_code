package io.cocode.app.runtime.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What `GET /.well-known/t3/environment` told us. [environmentId] is the server's stable identity:
 * readiness polls must keep reaching the same one, otherwise another process answered.
 */
data class HealthReport(
    val environmentId: String?,
    val serverVersion: String? = null,
) {
    /** The T3 Code web server answered with its environment identity. */
    val serverOk: Boolean get() = !environmentId.isNullOrBlank()
}

/** Reads T3 Code's environment document on loopback. */
fun interface HealthProbe {
    /** Null when nothing answered or the body was not JSON. */
    fun read(port: Int): HealthReport?

    /** Convenience: true only when the web server reports an environment. */
    fun isHealthy(port: Int): Boolean = read(port)?.serverOk == true

    companion object {
        const val ENVIRONMENT_PATH = "/.well-known/t3/environment"

        /** Parses the environment body. Pure, so the readiness rules are unit-tested. */
        fun parse(body: String): HealthReport? =
            runCatching {
                val json = Json.parseToJsonElement(body).jsonObject
                fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                HealthReport(
                    environmentId = text("environmentId")?.takeIf { it.isNotBlank() },
                    serverVersion = text("serverVersion")?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()

        /** GET http://127.0.0.1:<port>/.well-known/t3/environment. */
        val Loopback: HealthProbe =
            HealthProbe { port ->
                val connection = URL("http://127.0.0.1:$port$ENVIRONMENT_PATH").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 2_000
                    // Node on a phone can stall for seconds under the web UI's initial request burst.
                    connection.readTimeout = 5_000
                    if (connection.responseCode !in 200..299) return@HealthProbe null
                    parse(connection.inputStream.bufferedReader().use { it.readText() })
                } catch (_: Exception) {
                    null
                } finally {
                    connection.disconnect()
                }
            }

        /** True when nothing is listening on loopback [port] (connect is refused). */
        fun isPortFree(port: Int): Boolean =
            runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                    false
                }
            }.getOrDefault(true)
    }
}

/**
 * Readiness rules, pure. Trust order matters:
 *  1. Our own child printed the listen line (the pipe is private to us, so no foreign process can
 *     print it on our behalf). Node prints it only after a successful listen; EADDRINUSE exits.
 *  2. Only then is the environment document believed, and only while the environment id is stable
 *     across polls.
 */
object Readiness {
    /** The pino-style line T3 Code prints after listening, e.g. `Listening on http://127.0.0.1:3773`. */
    fun listenMarker(port: Int): String = "Listening on http://127.0.0.1:$port"

    /** True when our child's own output contains the listen line for [port]. */
    fun childListening(
        output: List<String>,
        port: Int,
    ): Boolean = output.any { it.contains(listenMarker(port)) }

    sealed interface Verdict {
        data object Waiting : Verdict

        data object Ready : Verdict
    }

    /** A server that answered with an environment id is ready; anything else keeps waiting. */
    fun verdict(latest: HealthReport?): Verdict =
        if (latest?.serverOk == true) Verdict.Ready else Verdict.Waiting

    /** True when the environment id stayed the same across readings (or none was reported). */
    fun identityStable(environmentIds: List<String?>): Boolean =
        environmentIds.filterNotNull().distinct().size <= 1
}
