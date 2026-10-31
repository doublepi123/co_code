<p align="center">
  <img src="docs/icon/icon.png" alt="CoCode" width="112">
</p>

# CoCode

[中文](README.md)

**CoCode runs a coding-agent server locally on an Android phone — no computer, no root.**

CoCode is derived from AndChamber (MIT). This version keeps the on-device Alpine/PRoot runtime, foreground service, notifications, remote servers, shared storage and downloads; the bundled server and UI will be replaced in a later version, with details to follow.

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
