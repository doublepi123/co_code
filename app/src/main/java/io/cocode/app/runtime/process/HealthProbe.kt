package io.cocode.app.runtime.process

import io.cocode.app.runtime.ServerKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL

/** Cox's unauthenticated environment document; identity must remain stable across readiness polls. */
data class HealthReport(
    val environmentId: String? = null,
    val serverVersion: String? = null,
    val kind: ServerKind = ServerKind.T3,
) {
    /** The stable identity of the answering server for [kind]. */
    val identity: String?
        get() =
            when (kind) {
                ServerKind.T3 -> environmentId?.takeIf { it.isNotBlank() }
            }

    /** A nonempty environment identity is required before trusting the server. */
    val serverOk: Boolean
        get() =
            when (kind) {
                ServerKind.T3 -> !environmentId.isNullOrBlank()
            }

    /** Cox is ready when it reports its environment identity. */
    val ready: Boolean
        get() =
            when (kind) {
                ServerKind.T3 -> serverOk
            }

}

/** Reads a server's health document on loopback. */
fun interface HealthProbe {
    /** Null when nothing answered or the body was not JSON. */
    fun read(port: Int): HealthReport?

    /** Convenience: true only when the server reports the expected kind and is ready. */
    fun isHealthy(port: Int): Boolean = read(port)?.ready == true

    companion object {
        /** T3 Code's environment document. */
        const val ENVIRONMENT_PATH = "/.well-known/t3/environment"

        /** The health path of [kind]. Explicit kind, never inferred from port or address. */
        fun healthPath(kind: ServerKind): String =
            when (kind) {
                ServerKind.T3 -> ENVIRONMENT_PATH
            }

        /** Parses a health body for [kind]. Pure, so the readiness rules are unit-tested. */
        fun parse(
            body: String,
            kind: ServerKind = ServerKind.T3,
        ): HealthReport? =
            runCatching {
                val json = Json.parseToJsonElement(body).jsonObject
                fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                when (kind) {
                    ServerKind.T3 ->
                        HealthReport(
                            environmentId = text("environmentId")?.takeIf { it.isNotBlank() },
                            serverVersion = text("serverVersion")?.takeIf { it.isNotBlank() },
                            kind = ServerKind.T3,
                        )
                }
            }.getOrNull()

        /** GET the health document of [kind]. T3 is the historical default. */
        fun loopback(kind: ServerKind = ServerKind.T3): HealthProbe =
            HealthProbe { port ->
                val connection = URL("http://127.0.0.1:$port${healthPath(kind)}").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 2_000
                    // Node on a phone can stall for seconds under the web UI's initial request burst.
                    connection.readTimeout = 5_000
                    // Health must never be judged through a redirect to somewhere else.
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode !in 200..299) return@HealthProbe null
                    parse(connection.inputStream.bufferedReader().use { it.readText() }, kind)
                } catch (_: Exception) {
                    null
                } finally {
                    connection.disconnect()
                }
            }

        /** T3 Code's health probe. */
        val Loopback: HealthProbe = loopback(ServerKind.T3)

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

/** Readiness trusts the owned child's listen marker before a stable health identity. */
object Readiness {
    /** The exact line Cox prints after successfully binding its loopback listener. */
    fun listenMarker(
        port: Int,
        kind: ServerKind = ServerKind.T3,
    ): String =
        when (kind) {
            ServerKind.T3 -> "Listening on http://127.0.0.1:$port"
        }

    /** True when our child's own output contains the listen line of [kind] for [port]. */
    fun childListening(
        output: List<String>,
        port: Int,
        kind: ServerKind = ServerKind.T3,
    ): Boolean = output.any { it.contains(listenMarker(port, kind)) }

    sealed interface Verdict {
        data object Waiting : Verdict

        data object Ready : Verdict

        /** The server is up but cannot become usable; retrying the same launch cannot help. */
        data class Failed(val reason: String) : Verdict
    }

    /** An environment identity makes Cox ready; no response or identity keeps waiting. */
    fun verdict(latest: HealthReport?): Verdict {
        if (latest == null) return Verdict.Waiting
        return if (latest.ready) Verdict.Ready else Verdict.Waiting
    }

    /** True when the identity stayed the same across readings (or none was reported). */
    fun identityStable(identities: List<String?>): Boolean =
        identities.filterNotNull().distinct().size <= 1
}
