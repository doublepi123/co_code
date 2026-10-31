<p align="center">
  <img src="docs/icon/icon.png" alt="CoCode" width="112">
</p>

# CoCode

[English](README.en.md)

**CoCode 在 Android 手机上本地运行一个编码代理服务器，不依赖电脑，也不需要 root。**

CoCode 由 AndChamber（MIT）派生而来。本机运行时在 Alpine Linux（PRoot，无 root）中启动 T3 Code 服务器（端口 3773），并从 npm 安装你选择的编码代理 CLI；前台服务保持服务器运行，WebView 使用独立配置登录，另外也支持连接远程 T3 Code 服务器、公共目录与下载。

### 运行时组成

| 组件 | 版本 / 来源 |
|---|---|
| Alpine Linux | 3.24.1（每个 ABI 固定 URL 与 SHA-256） |
| Node.js | Alpine 24.x |
| T3 Code 服务器 | GitHub `doublepi123/t3code` 发布包，默认 0.1.6-pw.5 |
| 编码代理 CLI | `@openai/codex`（Codex）、`@anthropic-ai/claude-code`（Claude Code）、`@opencode/cli`（OpenCode），安装时取最新版 |
| PRoot | 本地编译的 5.1.107.98 |
| 端口 | 3773（仅回环） |

共享目录 `CoCode/` 中的 `opencode/` 仍会挂载到 `~/.config/opencode`；`projects/` 挂载到 `~/projects`。

## 构建

需要 JDK 17、Android SDK（platform 35、build-tools 35、NDK 29.0.14206865）与 Python 3.12+。

```bash
export ANDROID_HOME=$HOME/Android/Sdk
python3 scripts/prepare_android_runtime_sources.py --output-dir build/runtime-sources
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

版本号在 `gradle.properties` 的 `cocode.versionName` / `cocode.versionCode` 中维护。

## 致谢与许可

- 派生自 AndChamber，其 PRoot 运行时管线等代码移植自 [AndCode](https://github.com/yuga-hashimoto/and-code)（MIT）。
- 运行时下载的组件遵循各自的许可证，详见 [NOTICE](NOTICE)。

本项目以 [MIT 许可证](LICENSE) 发布。
