<p align="center">
  <img src="docs/icon/icon.png" alt="CoCode" width="112">
</p>

# CoCode

[中文](README.md)

**CoCode runs a coding-agent server locally on an Android phone — no computer, no root.**

CoCode is derived from AndChamber (MIT). The on-device runtime starts the T3 Code server (port 3773) inside Alpine Linux under PRoot (no root) and installs the coding-agent CLIs you select from npm. A foreground service keeps the server running, each WebView profile signs in separately, and remote T3 Code servers, shared storage and downloads are also supported.

Runtime errors are notified natively; agent notifications from the web UI are not implemented yet.

### Runtime composition

| Component | Version / source |
|---|---|
| Alpine Linux | 3.24.1 (pinned URL and SHA-256 per ABI) |
| Node.js | Alpine 24.x |
| T3 Code server | GitHub release from `doublepi123/t3code`, default 0.1.6-pw.5 |
| Coding-agent CLIs | `@openai/codex` (Codex), `@anthropic-ai/claude-code` (Claude Code), `@opencode/cli` (OpenCode); latest at install time |
| PRoot | locally built 5.1.107.98 |
| Port | 3773 (loopback only) |

`opencode/` in the shared `CoCode/` folder is still bound to `~/.config/opencode`; `projects/` is bound to `~/projects`.

## Build

JDK 17, Android SDK (platform 35, build-tools 35, NDK 29.0.14206865) and Python 3.12+ are required.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The version lives in `gradle.properties` as `cocode.versionName` / `cocode.versionCode`.

## Credits and license

- Derived from AndChamber, whose PRoot runtime pipeline and related code were ported from [AndCode](https://github.com/yuga-hashimoto/and-code) (MIT).
- Components downloaded at runtime keep their own licenses; see [NOTICE](NOTICE).

Released under the [MIT license](LICENSE).
