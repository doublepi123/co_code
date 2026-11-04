# Cox-only settings and Agent integration

CoCode now runs Cox as its only local server and accepts Cox-compatible remote
server records. OpenChamber installation, launching, protocol selection and
native Codex device/API-key login are removed. Provider authentication belongs
to Cox; CoCode does not query or display Codex login state.

## Configuration flow

After setup, Home offers **Model center** and **Agent settings and installation**.
Opening either shortcut records its fixed Cox route, discards any pooled local
chat page, starts Cox when stopped and opens the page through the existing
profile-bound session authentication flow. Model-service URLs, protocols, API
keys, discovered/manual models and provider bindings are configured and saved
by Cox, without a second native credential store.

The model center requires Cox 0.1.21 or newer. An older installed runtime keeps
Agent settings available and receives an update hint instead of an invalid
model-center link. The bundled setup fallback is Cox 0.1.29.

## Agent installation coverage

| Agent | Installation and configuration |
|---|---|
| Codex | npm `@openai/codex`; authentication in Cox |
| Claude Code | npm `@anthropic-ai/claude-code`; Cox driver `claudeAgent` |
| OpenCode | npm `@opencode/cli` |
| Grok | npm `@xai-official/grok` |
| Pi | npm `@earendil-works/pi-coding-agent` |
| Cursor | SDK included with Cox; selecting verifies its presence, deselecting preserves the SDK |
| Antigravity | Managed installer in Cox Agent settings |
| ACP registry agents | Cox registry and managed installers; `uv` and archive prerequisites included |
| Fx, Muse | Upstream/manual executables configured in Cox; no automatic CoCode installer |

Npm package changes remain staged and verified before an atomic activation.
Failed installs retain the previous environment. New Cox provider entries use
modern instance envelopes and preserve instances already configured or disabled.

## Upgrade and data handling

- The stable local identity stays `local-t3`; the original `local` identity still
  migrates to it. Retired local identities are never reopened as another server.
- Retired-only environments can run Cox setup again while preserving the external
  guest home and workspace. Mixed metadata retains Cox and drops retired server
  identities. Program cleanup is limited to the staged retired package, bin links
  and policy files.
- Saved remote entries declaring a retired or unknown protocol are not loaded
  as Cox. Reading them does not overwrite their stored records.
- Existing Codex credentials and sessions are not deleted by removing the native
  login implementation. Cox and its agents continue to own those files.

## Verification

On November 4, 2026:

- 544 JVM tests pass, including staged installation/selection, retired metadata,
  provider defaults, authentication isolation and old Cox feature gating.
- 61 offline Python tests pass.
- Full debug APK and instrumentation APK builds pass with the native runtime
  build task included (unchanged native outputs are reused by Gradle).
- 17 targeted Android 35 x86_64 emulator tests pass: Cox settings callbacks and
  disabled controls, saved protocol migration, maintenance wording and remote
  server ordering. The test application ID is `io.cocode.coxtest`, separate from
  the production app.

These checks do not certify ARM64 physical-device installation, provider
accounts, model inference or every upstream Agent binary on Android/Alpine.
Older dual-server testing and release documents describe historical results.
