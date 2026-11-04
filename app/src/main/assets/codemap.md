# app/src/main/assets/

## Responsibility

Static runtime inputs packaged into the APK:

- `runtime-manifest.json`: schema-1 Alpine rootfs URL/SHA-256 per supported ABI, Cox npm identity/fallback, port 3773 and selectable agent specs.
- `android-shim.cjs`: Node preload workaround for Android SELinux restrictions on network-interface enumeration.

## Design And Flow

`RuntimeManifestReader` validates the manifest supplied by DI. Runtime `2026.11.04.1` uses Cox fallback `0.1.29`; setup resolves npm latest and pins the result. Architectures remain arm64-v8a and x86_64 with verified Alpine 3.24.1 archives.

Selectable npm agents are Codex, Claude Code, OpenCode, Grok (`@xai-official/grok`) and Pi (`@earendil-works/pi-coding-agent`). Cursor is explicitly `installation=bundled`, with no npm package/bin; its SDK is installed and verified as part of Cox. Other Cox providers remain opt-in provider instances in Cox settings, with their actual managed/manual installation methods rather than fake npm agent entries.

The installer stages rootfs/packages, writes the shim at `/opt/cocode/android-shim.cjs`, verifies native modules, PTY and offline Cursor SDK prewarm, then atomically activates. Launch sets `NODE_OPTIONS` to preload the shim. Persistent home and workspace are separate from the replaceable environment. Manifest changes take effect on install/repair/update, not by deleting user data.

## Integration

Consumers are `runtime/manifest`, `runtime/install`, `runtime/process` and `di/AppModule.kt`. Changing archive URLs/checksums together is mandatory. Existing T3-named Kotlin fields retain the Cox/T3 protocol compatibility identity; no second server is configured by this asset.
