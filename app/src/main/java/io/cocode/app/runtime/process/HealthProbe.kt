package io.cocode.app.runtime.process

import io.cocode.app.runtime.ServerKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What a server's unauthenticated health document told us, for either supported kind.
 *
 * T3 Code answers `GET /.well-known/t3/environment` with `environmentId` (its stable identity) and
 * `serverVersion`. OpenChamber answers `GET /health` with `status`, `serverId` (stable identity),
 * `isOpenCodeReady`, `openCodeRunning` and `lastOpenCodeError`.
 *
 * Readiness polls must keep reaching the same identity, otherwise another process answered.
 */
data class HealthReport(
    val environmentId: String? = null,
    val serverVersion: String? = null,
    val kind: ServerKind = ServerKind.T3,
    val status: String? = null,
    val serverId: String? = null,
    val openCodeReady: Boolean? = null,
    val openCodeRunning: Boolean? = null,
    val lastOpenCodeError: String? = null,
) {
    /** The stable identity of the answering server for [kind]. */
    val identity: String?
        get() =
            when (kind) {
                ServerKind.T3 -> environmentId?.takeIf { it.isNotBlank() }
                ServerKind.OpenChamber -> serverId?.takeIf { it.isNotBlank() }
            }

    /** The expected server answered with its identity (and, for OpenChamber, a healthy status). */
    val serverOk: Boolean
        get() =
            when (kind) {
                ServerKind.T3 -> !environmentId.isNullOrBlank()
                ServerKind.OpenChamber -> status == "ok" && identity != null
            }

    /** T3: an environment identity is enough. OpenChamber: its OpenCode backend is ready too. */
    val ready: Boolean
        get() =
            when (kind) {
                ServerKind.T3 -> serverOk
                ServerKind.OpenChamber -> serverOk && openCodeReady == true
            }

    /**
     * OpenChamber only: its OpenCode backend is not running and it reported why. Readiness must fail
     * clearly instead of waiting for the full timeout.
     */
    val openCodeFailed: Boolean
        get() =
            kind == ServerKind.OpenChamber &&
                openCodeRunning == false &&
                !lastOpenCodeError.isNullOrBlank()
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

        /** OpenChamber's unauthenticated health document. */
        const val OPENCHAMBER_HEALTH_PATH = "/health"

        /** The health path of [kind]. Explicit kind, never inferred from port or address. */
        fun healthPath(kind: ServerKind): String =
            when (kind) {
                ServerKind.T3 -> ENVIRONMENT_PATH
                ServerKind.OpenChamber -> OPENCHAMBER_HEALTH_PATH
            }

        /** Parses a health body for [kind]. Pure, so the readiness rules are unit-tested. */
        fun parse(
            body: String,
            kind: ServerKind = ServerKind.T3,
        ): HealthReport? =
            runCatching {
                val json = Json.parseToJsonElement(body).jsonObject
                fun text(key: String): String? = (json[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                fun bool(key: String): Boolean? = (json[key] as? JsonPrimitive)?.booleanOrNull
                when (kind) {
                    ServerKind.T3 ->
                        HealthReport(
                            environmentId = text("environmentId")?.takeIf { it.isNotBlank() },
                            serverVersion = text("serverVersion")?.takeIf { it.isNotBlank() },
                            kind = ServerKind.T3,
                        )
                    ServerKind.OpenChamber ->
                        HealthReport(
                            serverVersion = text("openchamberVersion")?.takeIf { it.isNotBlank() },
                            kind = ServerKind.OpenChamber,
                            status = text("status"),
                            serverId = text("serverId")?.takeIf { it.isNotBlank() },
                            openCodeReady = bool("isOpenCodeReady"),
                            openCodeRunning = bool("openCodeRunning"),
                            lastOpenCodeError = text("lastOpenCodeError")?.takeIf { it.isNotBlank() },
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

/**
 * Readiness rules, pure. Trust order matters:
 *  1. Our own child printed the listen line (the pipe is private to us, so no foreign process can
 *     print it on our behalf). Node prints it only after a successful listen; EADDRINUSE exits.
 *  2. Only then is the health document believed, and only while its identity is stable across polls.
 *  3. OpenChamber additionally requires its OpenCode backend to be ready; a reported OpenCode
 *     failure ends the wait immediately with a clear reason.
 */
object Readiness {
    /**
     * The line the child prints after listening. T3 Code uses its pino line
     * `Listening on http://127.0.0.1:3773`; OpenChamber prints
     * `OpenChamber server listening on 127.0.0.1:3000`. Both are exact and kind-specific.
     */
    fun listenMarker(
        port: Int,
        kind: ServerKind = ServerKind.T3,
    ): String =
        when (kind) {
            ServerKind.T3 -> "Listening on http://127.0.0.1:$port"
            ServerKind.OpenChamber -> "OpenChamber server listening on 127.0.0.1:$port"
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

    /**
     * A server that reports ready is ready; a reported OpenCode failure fails clearly; anything else
     * keeps waiting.
     */
    fun verdict(latest: HealthReport?): Verdict {
        if (latest == null) return Verdict.Waiting
        if (latest.openCodeFailed) {
            return Verdict.Failed(
                "${latest.kind.displayName} could not start OpenCode: ${latest.lastOpenCodeError}",
            )
        }
        return if (latest.ready) Verdict.Ready else Verdict.Waiting
    }

    /** True when the identity stayed the same across readings (or none was reported). */
    fun identityStable(identities: List<String?>): Boolean =
        identities.filterNotNull().distinct().size <= 1
}
