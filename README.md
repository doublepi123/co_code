<p align="center">
  <img src="docs/icon/icon.png" alt="CoCode" width="112">
</p>

# CoCode

[English](README.en.md)

**CoCode 在 Android 手机上本地运行一个编码代理服务器，不依赖电脑，也不需要 root。**

CoCode 由 AndChamber（MIT）派生而来，在 Alpine Linux（PRoot，无 root）环境中专门运行 **Cox**。本机安装、更新、页面认证和模型配置都围绕 Cox；不再安装或连接 OpenChamber。Cox 沿用 T3 Code 协议，已有兼容服务器记录仍可使用。

- **代理任选**：可安装 Codex、Claude Code、OpenCode、Grok、Pi；Cursor 使用 Cox 内置 SDK。追加安装保留已有代理；卸载需要确认，项目与主目录配置保留。
- **模型中心**：首页可直接打开 Cox 的模型中心，配置服务地址、API 密钥、协议和模型，并绑定到 Agent。Cox 停止时会先自动启动，配置与密钥由 Cox 管理。
- **Agent 设置**：首页进入 Cox 设置，管理认证、Antigravity 和 ACP 注册表安装。Fx、Muse 需要在 Cox 中配置上游可执行文件；不提供虚构的 npm 安装包。CoCode 不再提供 Codex 登录，也不查询或展示 Codex 登录态。
- **App 更新提示**：打开 App 时检查本项目的 GitHub Releases（包括 0.x 预发布版），有新版时提示前往下载；不会自动安装或中断任务。
- **独立图标**：手机与终端提示符，不再使用 AndChamber 的立方体。
- 不包含手机操控或浏览器操控工具。

运行时错误会通过系统通知提醒；网页里的代理通知暂未实现。

### 运行时组成

| 组件 | 版本 / 来源 |
|---|---|
| Alpine Linux | 3.24.1（每个 ABI 固定 URL 与 SHA-256） |
| Node.js | Alpine 24.x |
| Cox 服务器 | npm `@doublepi123/cox`，安装时解析 latest，内置回退版本 0.1.29（兼容 T3 Code 协议）；在 Alpine 内重编译 node-pty |
| 编码代理 CLI | `@openai/codex`（Codex）、`@anthropic-ai/claude-code`（Claude Code）、`@opencode/cli`（OpenCode）、`@xai-official/grok`（Grok）、`@earendil-works/pi-coding-agent`（Pi），安装时取最新版 |
| PRoot | 本地编译的 5.1.107.98 |
| 端口 | Cox 3773（仅回环） |

### 公共目录（可选）

启用并授予存储访问权限后，下列目录会实时映射；需重启服务才能应用映射变更。

| `/sdcard/CoCode/` 下 | 服务内路径 |
|---|---|
| `projects/` | `~/projects` |
| `opencode/` | `~/.config/opencode` |
| `codex/` | `~/.codex-shared`：Codex 的 `config.toml`、`AGENTS.md`、`skills/`、`rules/`、`prompts/`（从 `~/.codex` 链接过去） |
| `claude/` | `~/.claude`（整个目录） |

**隐私警告：Claude 的登录凭据、会话和历史记录也会进入公共目录。其他获存储权限的 App 可以读取、复制这些凭据并访问你的账号。开启前必须明确确认此风险。** 配置文件本身也可能包含 API Key，请自行保护。

Codex 只共享配置：它的登录凭据、会话、历史和锁文件始终留在 App 私有的 `~/.codex`。原因是 Android 公共存储不支持 Codex 依赖的 `flock` 文件锁，放在公共目录会导致登录无法保存（`os error 38`）。从旧版完整映射升级时，公共目录里的 Codex 会话和凭据会复制回私有目录，公共副本不会删除，也不再使用，建议手动删除。

首次开启会复制已有文件，不覆盖公共目录里的同名文件、不删除私有原件。关闭后不自动复制回来：改用开启前的私有副本，可能过时；公共目录保留，重新开启可继续使用。共享存储不支持符号链接和可执行权限；需要这些能力的项目请用私有工作区 `~/projects-private`。

从旧版升级且原本已开启共享时，先保持仅项目和 OpenCode 的映射；必须重新确认凭据共享，才会迁移 Codex 和 Claude 目录。迁移保留私有原件，并通过私有快照和恢复记录处理写入中断；不会覆盖已有公共文件，也不会把中断后的不完整副本当成迁移成功。

`~/.claude.json`、OpenCode 的 `~/.local/share/opencode`、服务密码及插件 `node_modules`、两种服务器的数据仍在私有目录。两种服务共享 OpenCode 配置不要求开启公共目录。

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

验证范围与各 Agent 安装方式见 [Cox 设置验证](docs/testing-cox-settings.md)。
