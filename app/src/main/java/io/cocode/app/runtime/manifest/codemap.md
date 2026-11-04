# app/src/main/java/io/cocode/app/runtime/manifest/

## Responsibility

Parse and validate `assets/runtime-manifest.json`, the source of truth for Alpine rootfs pins, Cox's npm fallback version, supported ABI downloads and selectable agent installation methods.

## Design

- `RuntimeManifest`: schema 1, runtime/Alpine versions, Cox port, `T3Spec`, agent specs and architecture map. Unknown fields are ignored for older asset compatibility.
- `T3Spec`: only the supported Cox npm package is accepted, with a stable three-part fallback version.
- `AgentSpec`: `id`, `npmPackage`, `bin`, `displayName`, `installation` (default `npm`). Npm package and binary names are validated before building shell commands.
- `installation=bundled` is supported only for `cursor`, with empty package and binary fields. The SDK comes from Cox, not a fictitious Cursor CLI npm package.
- `RuntimeArchitecture`: HTTPS archive URL and lowercase SHA-256. Missing ABIs, invalid ports, duplicate agent ids and unknown installation methods fail validation.
- `RuntimeManifestReader`: injected asset reader and pure serialization/validation; no Android APIs.

## Integration

The current agent list contains npm Codex, Claude Code, OpenCode, Grok and Pi, plus bundled Cursor SDK. Pi uses the package declared by Cox's current installer, `@earendil-works/pi-coding-agent`. Antigravity, ACP Registry, Fx and Muse are provider-instance entries managed/configured through Cox, not npm agent specs. Runtime installers and version discovery branch on the installation method; bundled agents never trigger npm version requests or package-tree deletion.
