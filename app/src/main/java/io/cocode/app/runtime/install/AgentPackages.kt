package io.cocode.app.runtime.install

import io.cocode.app.runtime.fs.deleteTreeOrThrow
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.manifest.T3Spec
import java.io.File

internal data class GithubReleaseEndpoints(
    val releaseBaseUrl: String = AgentPackages.DEFAULT_GITHUB_BASE,
    val apiBaseUrl: String = AgentPackages.DEFAULT_GITHUB_API_BASE,
)

internal object AgentPackages {
    const val BUNDLED_AGENT_VERSION = "bundled"
    const val DEFAULT_GITHUB_BASE = "https://github.com"
    const val DEFAULT_GITHUB_API_BASE = "https://api.github.com"

    private val VERSION_PATTERN = Regex("""\d+(?:\.\d+)+""")
    private val PINNED_NPM_VERSION = Regex("""^\d+(?:\.\d+)+(?:-[a-zA-Z0-9.-]+)?$""")
    private val SAFE_URL_BASE =
        Regex("""^https://[A-Za-z0-9.:/_-]+$|^http://(?:127\.0\.0\.1|localhost)(?::\d+)?(?:/[A-Za-z0-9._-]*)?$""")

    fun extractVersion(output: String): String =
        VERSION_PATTERN.find(output)?.value ?: output.trim().removePrefix("v")

    fun compareVersions(
        left: String,
        right: String,
    ): Int {
        val a = extractVersion(left).split('.').map { it.toIntOrNull() ?: 0 }
        val b = extractVersion(right).split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    fun resolveInstallVersions(
        rootfs: File,
        specs: Collection<AgentSpec>,
        explicit: Map<String, String> = emptyMap(),
    ): Map<String, String> =
        buildMap {
            putAll(explicit)
            for (spec in specs) {
                if (spec.versionPolicy == AgentSpec.VERSION_POLICY_COX) {
                    val coxPinned = InstalledComponents.coxSupportedOmoVersion(rootfs)
                    val target = coxPinned ?: explicit[spec.id]?.takeIf(String::isNotBlank) ?: spec.fallbackVersion
                    put(spec.id, target)
                }
            }
        }

    fun npmInstallScript(
        specs: List<AgentSpec>,
        versions: Map<String, String> = emptyMap(),
    ): String {
        val packages =
            specs.filter { it.installation == AgentSpec.INSTALLATION_NPM }.map { spec ->
                spec.validate()
                versions[spec.id]?.takeIf(String::isNotBlank)?.let {
                    require(PINNED_NPM_VERSION.matches(it)) { "Invalid agent version: $it" }
                    "${spec.npmPackage}@$it"
                } ?: spec.npmPackage
            }
        if (packages.isEmpty()) return "true"
        return "set -e; npm i -g --no-fund --no-audit ${packages.joinToString(" ")}"
    }

    fun githubReleaseInstallScript(
        spec: AgentSpec,
        abi: String,
        targetVersion: String? = null,
    ): String = githubReleaseInstallScript(spec, abi to targetVersion, GithubReleaseEndpoints())

    fun githubReleaseInstallScript(
        spec: AgentSpec,
        abiAndVersion: Pair<String, String?>,
        endpoints: GithubReleaseEndpoints,
    ): String {
        require(spec.installation == AgentSpec.INSTALLATION_GITHUB_RELEASE) {
            "Agent ${spec.id} is not a GitHub release agent"
        }
        spec.validate()
        val (abi, targetVersion) = abiAndVersion
        val asset = spec.assetFor(abi)
        val explicit =
            targetVersion?.takeIf(String::isNotBlank)?.also {
                require(T3Spec.VERSION.matches(it)) { "Invalid agent version: $it" }
            }
        val downloadRoot =
            endpoints.releaseBaseUrl.trimEnd('/').also {
                require(SAFE_URL_BASE.matches(it)) { "Invalid release URL: $it" }
            }
        val apiRoot =
            endpoints.apiBaseUrl.trimEnd('/').also {
                require(SAFE_URL_BASE.matches(it)) { "Invalid GitHub API URL: $it" }
            }
        val versionStep =
            if (explicit != null) {
                "ver='$explicit'"
            } else {
                "ver=\$(curl -fsSL --connect-timeout 10 --max-time 20 -H 'Accept: application/vnd.github+json' " +
                    "'$apiRoot/repos/${spec.githubRepo}/releases/latest' 2>/dev/null | " +
                    "node -e \"let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{try{const j=JSON.parse(d);" +
                    "const t=String(j.tag_name||'').replace(/^v/,'');" +
                    "const fb='${spec.fallbackVersion}';" +
                    "const cmp=(a,b)=>{const x=a.split('.').map(Number),y=b.split('.').map(Number);for(let i=0;i<3;i++)if(x[i]!==y[i])return x[i]-y[i];return 0;};" +
                    "process.stdout.write(!j.prerelease&&!j.draft&&/^\\\\d+\\\\.\\\\d+\\\\.\\\\d+\$/.test(t)&&cmp(t,fb)>=0?t:fb);}catch{process.stdout.write('${spec.fallbackVersion}');}})\" " +
                    "|| printf '%s' '${spec.fallbackVersion}')"
            }
        val installDir = "/${InstalledComponents.releaseAgentDirectory(spec)}"
        val installBin = "/${InstalledComponents.releaseAgentBinary(spec)}"
        val linkPath = "/usr/local/bin/${spec.bin}"
        return listOf(
            "set -e",
            versionStep,
            "work=\$(mktemp -d /tmp/cocode-agent-${spec.id}.XXXXXX)",
            "trap 'rm -rf \"\$work\"' EXIT",
            "curl -fsSL --retry 2 --connect-timeout 15 '$downloadRoot/${spec.githubRepo}/releases/download/v'\"\$ver\"'/SHA256SUMS.txt' -o \"\$work/SHA256SUMS.txt\"",
            "curl -fsSL --retry 2 --connect-timeout 15 '$downloadRoot/${spec.githubRepo}/releases/download/v'\"\$ver\"'/$asset' -o \"\$work/$asset\"",
            "sha=\$(awk -v f='$asset' '\$2 == f || \$2 == (\"*\" f) { print \$1; exit }' \"\$work/SHA256SUMS.txt\")",
            "case \"\$sha\" in [0-9a-f]*) [ \"\${#sha}\" -eq 64 ] ;; *) echo 'Missing SHA-256 for $asset' >&2; exit 1 ;; esac",
            "(cd \"\$work\" && printf '%s  %s\\n' \"\$sha\" '$asset' | sha256sum -c -)",
            "rm -rf '$installDir' '$linkPath'",
            "mkdir -p '$installDir' /usr/local/bin",
            "mv \"\$work/$asset\" '$installBin'",
            "chmod 0755 '$installBin'",
            "ln -s '../lib/cocode-agents/${spec.id}/${spec.bin}' '$linkPath'",
        ).joinToString("; ")
    }

    fun agentBinNames(spec: AgentSpec): List<String> =
        buildList {
            if (spec.installation == AgentSpec.INSTALLATION_BUNDLED) return@buildList
            add(spec.bin)
            if (spec.id == "opencode") add("opencode2")
        }

    fun removePackages(
        rootfs: File,
        specs: List<AgentSpec>,
    ) {
        for (spec in specs) {
            when (spec.installation) {
                AgentSpec.INSTALLATION_BUNDLED -> continue
                AgentSpec.INSTALLATION_NPM -> {
                    spec.validate()
                    deleteTreeOrThrow(File(rootfs, "usr/local/lib/node_modules/${spec.npmPackage}"))
                    for (bin in agentBinNames(spec)) deleteTreeOrThrow(File(rootfs, "usr/local/bin/$bin"))
                }
                AgentSpec.INSTALLATION_GITHUB_RELEASE -> {
                    spec.validate()
                    deleteTreeOrThrow(File(rootfs, InstalledComponents.releaseAgentDirectory(spec)))
                    for (bin in agentBinNames(spec)) deleteTreeOrThrow(File(rootfs, "usr/local/bin/$bin"))
                }
                else -> spec.validate()
            }
        }
    }
}
