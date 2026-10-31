<p align="center">
  <img src="docs/icon/icon.png" alt="CoCode" width="112">
</p>

# CoCode

[English](README.en.md)

**CoCode 在 Android 手机上本地运行一个编码代理服务器，不依赖电脑，也不需要 root。**

CoCode 由 AndChamber（MIT）派生而来。当前版本保留了本机 Alpine/PRoot 运行时、前台服务、通知、远程服务器连接、公共目录与下载能力；内置服务器与界面将在后续版本中替换，具体说明待补充。

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
