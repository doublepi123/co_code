# CoCode for iOS

CoCode for iOS is a foreground prototype that runs Cox and OMO on the device. Kernarium runs the ARM64 Linux guest, and the native shell opens the Cox web UI. Model inference uses the provider configured in Cox and requires a network connection.

## Build the Linux image

From the CoCode checkout, with Docker Buildx and an ARM64 builder or an emulation-enabled builder:

```sh
docker buildx build \
  --platform linux/arm64 --target bundle \
  --output type=local,dest=build/ios/guest \
  ios/guest
```

The result is `build/ios/guest/cocode-linux.erofs`, a format 2 image. It holds Alpine with Node.js and the toolchain that builds `node-pty`, the component manager `/usr/lib/cocode/manager.mjs`, and read-only seeds of Cox and OMO at the versions pinned in the Dockerfile. The builder checks the filesystem before exporting it.

## Independent updates

The Linux image, Cox, the agents and the app are versioned separately:

- On its first boot the manager copies each seed to `/opt/cocode/components/<id>/<version>` on the persistent disk and links `/opt/cocode/bin`. A later image never changes an installed component.
- The manager runs Cox and serves guest port `3774`, which the host publishes on iOS loopback only. Every request needs the local login password. It installs, updates and removes Cox and the agents (OMO, Oh My Pi, Codex, Claude Code, OpenCode, Grok, Pi) from npm or their releases, verifies them in a staging directory and switches them atomically. Cox restarts after its own update.
- CoCode's Home › Components and updates shows each part. A new image comes from the `ios-image-channel` release asset `CoCodeGuest-v2.json`; CoCode verifies it and puts it in place the next time it opens.
- The app is updated through TestFlight or the App Store. An installed image of another format is replaced with the one the app names.

## GitHub Actions build

The [CoCode iOS workflow](../.github/workflows/ios.yml) runs on iOS changes to `main`, on `ios-v*` tags, on iOS pull requests, and on manual dispatch. It reuses the channel's image when it was built from the same `ios/guest` tree; otherwise it builds the ARM64 image, compresses it with zstd and publishes it as an `ios-image-N` prerelease, then moves the channel to it on `main` and `ios-v*` tags. It builds the boot ramdisk on Linux and packages the iOS app on an Apple silicon Xcode 27 runner.

Pushes to `main`, manual runs and `ios-v*` tags publish a public GitHub Release. An `ios-vX.Y.Z` tag publishes release `ios-vX.Y.Z` and sets the app version to `X.Y.Z`; `ios-v0.*` tags are prereleases like the project's other 0.x releases. Pushes to `main` and manual runs publish an `ios-build-N` prerelease whose app version is the next patch after the newest `ios-vX.Y.Z` tag. Every build uses its workflow run number as the build number. Each app release holds `CoCode-unsigned.ipa`, `CoCodeGuest.json` and `SHA256SUMS`; each image release holds `cocode-linux.erofs.zst`, `CoCodeGuest.json` and `SHA256SUMS`. Pull requests only upload workflow artifacts.

The IPA embeds `CoCodeGuest.json`, which names the image's format, release URL, sizes and SHA-256 digests of the compressed and the decompressed image. On first launch, or when the installed image has another format, CoCode downloads that image without credentials, checks both digests, and moves it into place before it starts Linux. A failed check keeps Linux from starting and offers a retry. Sign the IPA before installing it on an iPhone; no file has to be copied by hand.

On `main`, the workflow also exports the archive using Apple-managed distribution signing in the `testflight` GitHub environment. It checks the app signature, bundle identifier and both runtime memory entitlements before uploading an internal-only build to App Store Connect. Configure the environment for the `main` branch with `APPLE_TEAM_ID`, `APPLE_API_KEY_ID`, `APPLE_API_ISSUER` and the PEM contents of `APPLE_API_PRIVATE_KEY`. The Apple team must have an app record for `io.cocode.ios` and access to cloud-managed distribution signing.

The guest starts BusyBox init, sets up its terminal devices and network, and runs the component manager, which runs Cox on guest port `3773`. The iOS host publishes that port on iOS loopback only. CoCode stores projects in `/root/projects`, Cox data in `/root/.cox`, and the generated local login password in a private file under `/root`. Those paths must be backed by the host's persistent disk.

The [iOS Simulator QA workflow](../.github/workflows/ios-simulator-qa.yml) can be started manually from any dispatched ref. It builds the simulator app, captures first-launch screens in light and dark appearance with simulator logs, and optionally runs selected `KernariumTests` when `only_testing` is supplied.

## Verify on an iPhone

Follow the host build and installation steps in [host/README.md](host/README.md). Then run the following on the installed CoCode app:

1. Open Cox and create a project under `/root/projects`. Open its terminal, run `git init`, then run `printf '%s\n' cocode-ios-pty`. Pass when the terminal streams the standalone line `cocode-ios-pty`.
2. Configure a model and select OMO. Ask it to create `device-probe.txt` containing `cocode-ios-device`, then run `git status --short` and show its output. Pass when Cox shows both the file and the command result.
3. Force quit and reopen CoCode. Pass when the same project and conversation are visible and `cat device-probe.txt` returns `cocode-ios-device`.

Capture the device model and system version, the build revision, a screenshot of Cox after boot, the terminal and Agent results, and the reopened project. Keep the local password and provider credentials out of captures.

The guest scripts and this guide are MIT licensed with CoCode. The code under `host/` that modifies or embeds Kernarium follows [Kernarium's GPL-2.0-or-later licensing](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/LICENSES/README.md). Third-party packages keep their respective licenses.
