<p align="center">
  <img src="docs/icon/icon.png" alt="CoCode" width="112">
</p>

# CoCode

[中文](README.md)

**CoCode runs a coding-agent server locally on an Android phone — no computer, no root.**

CoCode is derived from AndChamber (MIT) and runs **Cox** in an Alpine Linux environment under PRoot (no root). Local installation, updates, page authentication and model configuration are dedicated to Cox. OpenChamber installation and remote connections are retired. Cox retains the T3 Code protocol, so existing compatible server records continue to work.

- **Pick your agents**: install Codex, Claude Code, OpenCode, Grok and Pi; Cursor uses Cox's bundled SDK. Adding one preserves the others; removal requires confirmation and retains projects and home configuration.
- **Model center**: open Cox's model center from Home to configure service URLs, API keys, protocols, models and agent bindings. Cox starts automatically when needed and owns the saved configuration and secrets.
- **Agent settings**: manage authentication, Antigravity and ACP registry installers in Cox. Fx and Muse require an upstream executable configured in Cox. CoCode no longer signs into Codex or reads/displays its login status.
- **App update prompt**: checks this project's GitHub Releases on opening, including 0.x prereleases, and offers the download page. No automatic installation or task interruption.
- **Distinct icon**: a phone with a terminal prompt, not the AndChamber cube.
- No phone-control or browser-control tools.

Runtime errors are notified natively; agent notifications from the web UI are not implemented yet.

### Runtime composition

| Component | Version / source |
|---|---|
| Alpine Linux | 3.24.1 (pinned URL and SHA-256 per ABI) |
| Node.js | Alpine 24.x |
| Cox server | npm `@doublepi123/cox`, resolves latest on install, bundled fallback 0.1.29 (T3 Code protocol); node-pty rebuilt inside Alpine |
| Coding-agent CLIs | `@openai/codex` (Codex), `@anthropic-ai/claude-code` (Claude Code), `@opencode/cli` (OpenCode), `@xai-official/grok` (Grok), `@earendil-works/pi-coding-agent` (Pi); latest at install time |
| PRoot | locally built 5.1.107.98 |
| Ports | Cox 3773 (loopback only) |

### Shared folders (optional)

After explicit consent and storage permission, these directories are live-bound. Restart the server to apply binding changes.

| Under `/sdcard/CoCode/` | Guest path |
|---|---|
| `projects/` | `~/projects` |
| `opencode/` | `~/.config/opencode` |
| `codex/` | `~/.codex-shared`: Codex `config.toml`, `AGENTS.md`, `skills/`, `rules/`, `prompts/` (linked from `~/.codex`) |
| `claude/` | `~/.claude` (entire directory) |

**Privacy warning: Claude login credentials, sessions and history are public too. Other storage-authorized apps can read or copy credentials and access your accounts. Enabling requires acknowledging this risk.** Configuration files may also contain API keys.

Codex shares configuration only: its credentials, sessions, history and lock files always stay in the app-private `~/.codex`, because Android shared storage does not implement the `flock` locks Codex relies on (saving a sign-in there fails with `os error 38`). When upgrading from the earlier full mapping, public Codex sessions and credentials are copied back to private storage; the public copies are kept, no longer used, and should be deleted manually.

First enable copies existing files without overwriting shared files or deleting private originals. Disabling does not copy changes back: the older private copies become visible and may be stale. Public data remains; re-enable to use it again. Shared storage cannot preserve symbolic links or executable permissions; use `~/projects-private` for projects needing them.

Upgrading with sharing already enabled preserves the older projects/OpenCode-only mappings until the new credential-sharing warning is acknowledged. Migration retains private originals and uses private snapshots and recovery records for interrupted writes. Existing public files are not replaced, and incomplete copies are not silently accepted as successful migrations.

`~/.claude.json`, OpenCode's `~/.local/share/opencode`, service secrets, plugin `node_modules`, and both servers' data remain private. Sharing OpenCode configuration between the two servers does not require public storage.

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

See [Cox settings verification](docs/testing-cox-settings.md) for Agent installation methods and validation scope.
