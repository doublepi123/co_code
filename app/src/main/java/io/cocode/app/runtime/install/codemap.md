# app/src/main/java/io/cocode/app/runtime/install/

## Responsibility

Build, verify and activate Alpine + Node + Cox with selected agents. All environment mutations run in staging and commit through one atomic directory swap; persistent home/workspace and credentials are never the staging install home.

## Design

- `RuntimeInstaller.kt`: installer/reporting contracts, schema-2 `RuntimeMetadata`, `InstalledRuntime` and `PackageVersions`. T3-named fields preserve historical readers and saved identities.
- `RuntimeInstallerImpl.kt`: recover swaps, normalize symlinks, verify ABI and recorded server assets, filter agent metadata against the manifest and actual files, and delegate mutations. Unknown retired server ids do not become enum values. An explicit nonempty server map is authoritative over stale `t3Version`.
- A retired-only environment is reported as not installed without erasing or rewriting it. Regular installation stages a fresh Cox environment and preserves user directories and rollback safety.
- `RuntimeEnvironmentInstaller.kt`: fresh install, update, agent apply and compatibility server apply. Every update/apply copies the environment, retargets staged symlinks, uses a disposable staging home, checks survivors and atomically activates verified metadata/assets.
- Retired server package trees, bin links, policy files and PTY smoke scripts are removed only from the staged rootfs using no-follow deletion. Historical state/credentials in persistent home are not cleanup targets.
- Cox npm installation resolves latest with the bundled fallback, validates/pins the version, rebuilds `node-pty` for musl and verifies package identity, CLI/UI assets, native modules, offline Cursor prewarm and PTY behavior.
- `AgentSpec.installation=npm` uses actual npm package metadata and CLI `--version` verification. Grok and Pi join Codex, Claude and OpenCode. Native/package/probe failures abort staging instead of marking unsupported binaries installed.
- Bundled Cursor uses metadata version `bundled`, requires the SDK package inside Cox, runs no CLI version probe and is never uninstalled from Cox when deselected. Npm update discovery skips it.
- `APK_PACKAGES` supplies Node/npm, bash, git, curl, ripgrep, SSH, certificates, Python, uv, tar, gzip, bzip2, unzip, gcompat and native build tools. Staged verification also repairs missing managed-provider prerequisites in older environments. uv makes ACP Registry's Python distributions available alongside npm distributions; full archive tools support binary distributions, including `unzip -Z1` that BusyBox does not provide.
- `InstalledComponents.kt`: verified npm Cox and legacy T3/Cox bundle identities, agent file checks, authoritative recorded server versions and asset filtering.
- `NpmPackageVersionSource.kt`: stable registry discovery with timeouts, injected base URL and no downgrade. Old T3 product versions do not prevent Cox replacement.
- `VerifiedRuntimeDownloader.kt`, `RuntimeArchive.kt`: streamed download, checksum verification and safe tar/deb extraction.
- `EnvironmentActivation.kt`: atomic active/staging/rollback rename protocol, interrupted-swap recovery and symlink normalization. Recovery never rolls back an already committed activation.

## Invariants And Tests

Active program files are untouched until activation. Every expected surviving server remains verified; a missing selected agent is restored before verification. Npm package/bin removal never follows symlinks or removes a bundled agent's parent directory. Installer commands use no server password and no persistent home.

Runtime JVM tests cover activation crash points, no-follow deletion, recorded-server migration, retired-only detection and selection migration, agent survivor restoration, package removal, real npm package selection, bundled-agent deletion guards, SDK checks, registry discovery and guest output teardown. Device tests remain responsible for actual ABI/PRoot/native CLI execution; successful registry metadata is not proof of Android binary compatibility.
