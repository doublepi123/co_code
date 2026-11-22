package io.cocode.app.runtime

import io.cocode.app.runtime.install.PackageVersions
import io.cocode.app.runtime.install.RuntimeEnvironmentInstaller
import io.cocode.app.runtime.manifest.AgentSpec

internal data class InstalledRuntimeState(
    val installed: PackageVersions,
    val effectiveAgents: Set<String>,
    val installedT3IsLegacy: Boolean,
    val installedCoxNeedsNpmMigration: Boolean,
)

internal object UpdateAvailability {
    fun resolve(
        state: InstalledRuntimeState,
        registry: PackageVersions?,
        manifest: Pair<String?, List<AgentSpec>>,
    ): RuntimeUpdate? {
        val (pinnedT3Version, manifestAgents) = manifest
        val installed = state.installed
        val t3Current = installed.servers[ServerKind.T3] ?: installed.t3
        val t3Installed = ServerKind.T3 in installed.servers
        val replacesLegacyT3 = t3Installed && state.installedT3IsLegacy
        val targetT3 =
            if (replacesLegacyT3) {
                registry?.t3 ?: pinnedT3Version ?: t3Current
            } else if (t3Installed) {
                newestOf(listOf(registry?.servers?.get(ServerKind.T3), registry?.t3, pinnedT3Version, t3Current))
                    ?: t3Current
            } else {
                t3Current
            }
        val t3 = VersionChange(current = t3Current, target = targetT3)
        val agents =
            state.effectiveAgents.mapNotNull { id ->
                val current = installed.agents[id] ?: return@mapNotNull null
                val spec = manifestAgents.firstOrNull { it.id == id }
                val target =
                    if (spec?.versionPolicy == AgentSpec.VERSION_POLICY_COX) {
                        registry?.agents?.get(id)
                            ?: spec.fallbackVersion.takeIf(String::isNotBlank)?.let { newestOf(listOf(it, current)) }
                            ?: current
                    } else {
                        newestOf(listOf(registry?.agents?.get(id), spec?.fallbackVersion?.takeIf(String::isNotBlank), current))
                            ?: return@mapNotNull null
                    }
                val displayName = spec?.displayName ?: id
                AgentChange(id = id, displayName = displayName, change = VersionChange(current, target))
            }
        val servers =
            installed.servers
                .filterKeys { it != ServerKind.T3 }
                .mapValues { (kind, current) ->
                    VersionChange(current, newestOf(listOf(registry?.servers?.get(kind), current)) ?: current)
                }
        val update =
            RuntimeUpdate(
                t3 = t3,
                agents = agents,
                servers = servers,
                replacesLegacyT3 = replacesLegacyT3,
                migratesCoxToNpm = t3Installed && state.installedCoxNeedsNpmMigration &&
                    newestOf(listOf(registry?.t3, pinnedT3Version))?.let {
                        RuntimeEnvironmentInstaller.compareVersions(it, t3Current) >= 0
                    } == true,
            )
        return update.takeIf { it.hasChanges }
    }

    fun newestOf(versions: Collection<String?>): String? =
        versions.filterNotNull().maxWithOrNull { a, b -> RuntimeEnvironmentInstaller.compareVersions(a, b) }
}
