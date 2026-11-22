# app/src/main/java/io/cocode/app/runtime/manifest/

## Responsibility

Parse and validate `assets/runtime-manifest.json`, the source of truth for Alpine rootfs pins, Cox's npm fallback version, supported ABI downloads and selectable agent installation methods.

## Design

- `RuntimeManifest`: schema 1, runtime/Alpine versions, Cox port, `T3Spec`, agent specs and architecture map. Unknown fields are ignored for older asset compatibility.
- `T3Spec`: only the supported Cox npm package is accepted, with a stable three-part fallback version.
- `AgentSpec`: `id`, `npmPackage`, `bin`, `displayName`, `installation` (`npm` default, `github-release`, or `bundled`), `versionPolicy` (`latest` default or `cox`), `fallbackVersion`, `githubRepo`, and per-ABI `assets`. Package, repository, asset and binary names are validated before building shell commands, and every `github-release` agent must declare an asset for every supported architecture.
- `installation=bundled` is supported only for `cursor`, with empty package and binary fields. The SDK comes from Cox, not a fictitious Cursor CLI npm package.
- `RuntimeArchitecture`: HTTPS archive URL and lowercase SHA-256. Missing ABIs, invalid ports, duplicate agent ids and unknown installation methods fail validation.
- `RuntimeManifestReader`: injected asset reader and pure serialization/validation; no Android APIs.

## Integration

The current agent list contains OMO Native (`omo-ai`, `versionPolicy=cox` with fallback `5.1.69`), Oh My Pi (`installation=github-release` from `can1357/oh-my-pi` standalone musl binaries), npm Codex, Claude Code, OpenCode, Grok and Pi (`@earendil-works/pi-coding-agent`), plus bundled Cursor SDK. Antigravity, ACP Registry, Fx and Muse are provider-instance entries managed/configured through Cox, not npm agent specs. Runtime installers and version discovery branch on the installation method and version policy; bundled agents never trigger registry requests or package-tree deletion.
