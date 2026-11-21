# CoCode for iOS

This prototype uses the Linux runtime and the Xcode project from Kernarium at `b14aa9e6da9df15f467d8aad0090888373cc9b21`. The overlay gives the app the display name **CoCode** and the bundle identifier `io.cocode.ios`. The Xcode scheme and product path retain the upstream names.

## Apply and build on an Apple silicon Mac

From a CoCode checkout, use a separate Kernarium checkout on a path without spaces. Keep the checkout and Xcode build output outside the system temporary directory.

```sh
git clone https://github.com/imxieyi/Kernarium.git ../kernarium-cocode
git -C ../kernarium-cocode switch --detach b14aa9e6da9df15f467d8aad0090888373cc9b21
sh ios/host/apply.sh --check ../kernarium-cocode
sh ios/host/apply.sh ../kernarium-cocode
cd ../kernarium-cocode
```

Use Xcode with the iOS 27 SDK, CMake 3.27 or newer, Ninja and the host tools listed in `dependencies/README.md`. The existing Xcode target embeds both runtime frameworks, so packaging needs both kernel bundles. The CoCode launcher selects Linux.

- **Linux kernel.** Obtain the published arm64 bundle named `mainline-6.18-arm64-4k-20261119-6.18.60-ca1`, or follow the standalone build and publication guide. Build the `arm64` Linux slice with `build-kernel.sh`, then publish it with `publish-artifact.py`. Extract or publish directly under `.build/kernel/artifacts/`. The accepted descriptor records the exact identity CMake checks. [Accepted Linux descriptor](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/kernel/mainline-6.18/artifacts/accepted-4k.env) | [Build and publication guide](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/kernel/mainline-6.18/README.md#standalone-build-and-publication) | [Kernel builder](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/kernel/mainline-6.18/build/build-kernel.sh) | [Artifact publisher](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/kernel/mainline-6.18/build/publish-artifact.py)
- **Linux boot base.** Obtain `uml-boot-base-aarch64.cpio.gz` from the `starter-20260928-boot-base-r1` publication, or run `tools/rootfs/starter/build-all.sh --product boot-base-aarch64 --work "$BOOT_WORK" --output "$BOOT_OUTPUT"` on arm64 Linux with the starter prerequisites. Use absolute work and output directories. Verify the result with `tools/rootfs/boot-ramdisk/verify_base.py --descriptor tools/rootfs/boot-ramdisk/accepted-aarch64.env --archive "$BOOT_OUTPUT/uml-boot-base-aarch64.cpio.gz"` before placing it under `.build/rootfs/starter-20260928-boot-base-r1/`. CMake requires the accepted size and SHA-256. If a rebuild differs, obtain the exact published archive. [Accepted boot base descriptor](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/tools/rootfs/boot-ramdisk/accepted-aarch64.env) | [Starter build guide](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/tools/rootfs/starter/README.md#building) | [Starter producer](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/tools/rootfs/starter/build-all.sh)
- **Android kernel.** Extract the corresponding accepted Android kernel under `.build/kernel/artifacts/`. [Accepted Android descriptor](https://github.com/imxieyi/Kernarium/blob/b14aa9e6da9df15f467d8aad0090888373cc9b21/kernel/mainline-6.18/artifacts/accepted-android-4k.env)

```sh
for product in apple-gpu-vulkan-experiment ios-network ios-storage ios-compression; do
    dependencies/prepare.sh --product "$product" || exit 1
done
dependencies/build-apple-gpu.sh --platform ios
dependencies/build-ios-network.sh --platform ios
cmake --preset kernarium-ios-device
cmake --build --preset kernarium-ios-device
cmake --preset kernarium-ios-device-android
cmake --build --preset kernarium-ios-device-android
Kernarium/scripts/package-ios-frameworks.sh --sdk device
```

Set your development team in the ignored `GuestHost/Config/Signing.local.xcconfig` using its `.example` file. Run the following to build with device signing. The app uses the upstream runtime's memory entitlements and the Linux release framework. The guest image is an independent install input.

```sh
xcodebuild -project Kernarium/Kernarium.xcodeproj -scheme Kernarium \
    -configuration Release -destination 'generic/platform=iOS' \
    -derivedDataPath "$PWD/.build/cocode-xcode" -allowProvisioningUpdates build
```

## Install and smoke-test

Set `DEVICE` to the CoreDevice identifier. A local Xcode build needs a `CoCodeGuest.json` in `Kernarium/Kernarium/`; copy it from a published CoCode iOS release, or let `ios/host/build-package.sh` place it. Install and launch the app:

```sh
xcrun devicectl device install app --device "$DEVICE" \
    .build/cocode-xcode/Build/Products/Release-iphoneos/Kernarium.app
xcrun devicectl device process launch --device "$DEVICE" io.cocode.ios
```

On first launch CoCode downloads the image named in its bundled `CoCodeGuest.json` from the public GitHub Release, verifies the compressed and decompressed SHA-256 digests, and stores it as `Documents/cocode-linux.erofs`. Later launches start without the network while the bundled manifest names the same image. The guest's `/sbin/init` starts DHCP on `eth0` and runs Cox on guest TCP port 3773. SLiRP forwards `127.0.0.1:3773` on the iOS device to its DHCP guest `10.0.2.15:3773`. The Linux release runtime sets `console=ttyS0` and connects `ssl0` to its native console relay. Guest output written to `/dev/console` is visible in **Console**. Read the guest's `CoCode local password` message there, then enter it on Cox's sign-in page in the **Cox** tab. The WebKit session uses an ephemeral data store and a policy scoped to the exact local origin.

The app stores the sparse ext4 overlay in its private `Library/CoCode/cocode-state.ext4`. The first boot formats the disk, and later boots mount it as-is. `/root`, including Cox's password and state, is writable on this overlay. Keep the EROFS image and its existing overlay together for this prototype. The initial disk size can be selected before the first boot and stays fixed afterward.

On device, confirm that the terminal shows DHCP and the initial password, Cox signs in and its WebSocket connects, and a saved project survives terminating and reopening CoCode. Check that an external navigation or request to a different loopback port is blocked in the Cox tab and that TCP port 3773 cannot be reached from a second LAN device. Repeat the launch with a competing local listener on port 3773. CoCode should show a launch error that names the port and tells the user how to recover. The Cox tab should never display the competing listener's page.
