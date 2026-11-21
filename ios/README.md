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

The result is `build/ios/guest/cocode-linux.erofs`. The image contains Cox 0.2.22, OMO 5.1.79, and a musl build of `node-pty`. The builder checks the filesystem before exporting it. Exact Cox and OMO versions are pinned; Alpine and npm may resolve newer transitive packages on a later build.

## GitHub Actions build

The [CoCode iOS workflow](../.github/workflows/ios.yml) runs on iOS changes to `main`, on `ios-v*` tags, on iOS pull requests, and on manual dispatch. It builds the ARM64 guest and boot ramdisk on Linux, compresses the image with zstd, then packages the iOS app on an Apple silicon Xcode 27 runner.

Pushes to `main`, manual runs and `ios-v*` tags publish a public GitHub Release. An `ios-v*` tag makes a regular release; the other two make an `ios-build-N` prerelease. Each release holds `CoCode-unsigned.ipa`, `cocode-linux.erofs.zst`, `CoCodeGuest.json` and `SHA256SUMS`. Pull requests only upload workflow artifacts.

The IPA embeds `CoCodeGuest.json`, which names the release URL, sizes and SHA-256 digests of the compressed and the decompressed image. On first launch, or after an update that names a new image, CoCode downloads the image without credentials, checks both digests, and moves it into place before it starts Linux. A failed check keeps Linux from starting and offers a retry. Sign the IPA before installing it on an iPhone; no file has to be copied by hand.

The guest starts BusyBox init, sets up its terminal devices and network, and runs Cox on guest port `3773`. The iOS host publishes that port on iOS loopback only. CoCode stores projects in `/root/projects`, Cox data in `/root/.cox`, and the generated local login password in a private file under `/root`. Those paths must be backed by the host's persistent disk.

## Verify on an iPhone

Follow the host build and installation steps in [host/README.md](host/README.md). Then run the following on the installed CoCode app:

1. Open Cox and create a project under `/root/projects`. Open its terminal, run `git init`, then run `printf '%s\n' cocode-ios-pty`. Pass when the terminal streams the standalone line `cocode-ios-pty`.
2. Configure a model and select OMO. Ask it to create `device-probe.txt` containing `cocode-ios-device`, then run `git status --short` and show its output. Pass when Cox shows both the file and the command result.
3. Force quit and reopen CoCode. Pass when the same project and conversation are visible and `cat device-probe.txt` returns `cocode-ios-device`.

Capture the device model and system version, the build revision, a screenshot of Cox after boot, the terminal and Agent results, and the reopened project. Keep the local password and provider credentials out of captures.

The guest scripts and this guide are MIT licensed with CoCode. The code under `host/` that modifies or embeds Kernarium follows [Kernarium's GPL-2.0-or-later licensing](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/LICENSES/README.md). Third-party packages keep their respective licenses.
