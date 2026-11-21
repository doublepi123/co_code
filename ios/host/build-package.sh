#!/bin/bash
set -Eeuo pipefail
trap 'status=$?; printf "build-package: line %s failed with status %s: %s\n" "$LINENO" "$status" "$BASH_COMMAND" >&2' ERR

PIN=b14aa9e6da9df15f467d8aad0090888373cc9b21
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)

fail() { printf 'build-package: %s\n' "$*" >&2; exit 1; }
[ "$#" -eq 4 ] || fail "Usage: $0 KERNARIUM_DIR BOOT_BASE_DIR GUEST_IMAGE OUTPUT_DIR"
INPUT_CHECKOUT=$(cd "$1" 2>/dev/null && pwd -P) || fail "Kernarium checkout not found: $1"
BOOT_BASE_DIR=$(cd "$2" 2>/dev/null && pwd -P) || fail "boot base directory not found: $2"
GUEST_IMAGE=$(cd "$(dirname "$3")" 2>/dev/null && printf '%s/%s' "$(pwd -P)" "$(basename "$3")") || fail "guest image directory not found: $3"
OUTPUT_DIR=$4
BOOT_BASE=$BOOT_BASE_DIR/uml-boot-base-aarch64.cpio.gz
[ -f "$BOOT_BASE" ] || fail "missing ARM64 boot base: $BOOT_BASE"
[ -f "$GUEST_IMAGE" ] || fail "guest image not found: $GUEST_IMAGE"
case "$GUEST_IMAGE" in *.erofs|*.ext4) ;; *) fail 'guest image must have .erofs or .ext4 suffix' ;; esac
case "$(basename "$GUEST_IMAGE")" in *[[:space:],:\"]*) fail 'guest image filename cannot contain whitespace, comma, or quote' ;; esac
[ -n "${BOOT_BASE_SHA256:-}" ] || fail 'BOOT_BASE_SHA256 must be set to the Ubuntu producer checksum'
BOOT_SHA=$(shasum -a 256 "$BOOT_BASE" | awk '{print $1}')
[ "$BOOT_SHA" = "$BOOT_BASE_SHA256" ] || fail "boot base SHA-256 mismatch: expected $BOOT_BASE_SHA256, got $BOOT_SHA"

[ "$(uname -s)" = Darwin ] || fail 'requires macOS'
[ "$(uname -m)" = arm64 ] || fail 'requires an Apple silicon ARM64 runner'
for tool in git xcodebuild xcrun cmake ninja python3 shasum hdiutil llvm-objcopy; do
    command -v "$tool" >/dev/null 2>&1 || fail "missing required tool: $tool"
done
XCODE_VERSION=$(xcodebuild -version | awk '/^Xcode / {print $2; exit}')
case "$XCODE_VERSION" in 27|27.*) ;; *) fail "requires Xcode 27; found $XCODE_VERSION" ;; esac
SDK_VERSION=$(xcrun --sdk iphoneos --show-sdk-version)
case "$SDK_VERSION" in 27.*) ;; *) fail "requires iOS 27 SDK; found $SDK_VERSION" ;; esac
head=$(git -C "$INPUT_CHECKOUT" rev-parse HEAD 2>/dev/null) || fail 'Kernarium_DIR is not a Git checkout'
[ "$head" = "$PIN" ] || fail "expected Kernarium revision $PIN; found $head"
[ -z "$(git -C "$INPUT_CHECKOUT" status --porcelain --untracked-files=all)" ] || fail 'Kernarium checkout must be clean'

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd "$OUTPUT_DIR" && pwd -P)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/cocode-ios.XXXXXX")
SPARSE="$WORK/case-sensitive.sparseimage"
MOUNT="$WORK/case-sensitive"
mkdir -p "$MOUNT"
cleanup() {
    if [ "$mounted" = 1 ]; then
        cd /
        hdiutil detach -quiet "$MOUNT" || return
    fi
    rm -rf "$WORK"
}
mounted=0
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
hdiutil create -size 100g -type SPARSE -fs 'Case-sensitive APFS' -volname CoCodeBuild "$SPARSE"
hdiutil attach -nobrowse -mountpoint "$MOUNT" "$SPARSE"
mounted=1
: > "$MOUNT/CoCodeCaseProbe"
[ ! -e "$MOUNT/cocodecaseprobe" ] || fail 'the build volume is not case-sensitive'
rm "$MOUNT/CoCodeCaseProbe"
KERNARIUM_DIR="$MOUNT/Kernarium"
git clone --quiet --no-hardlinks --shared "$INPUT_CHECKOUT" "$KERNARIUM_DIR"
git -C "$KERNARIUM_DIR" checkout --quiet --detach "$PIN"
"$SCRIPT_DIR/apply.sh" "$KERNARIUM_DIR"

# Build products remain on the case-sensitive volume and outside upload output.
BOOT_DESCRIPTOR="$KERNARIUM_DIR/tools/rootfs/boot-ramdisk/accepted-aarch64.env"
BOOT_RELEASE="cocode-local-$(printf '%s' "$BOOT_SHA" | cut -c1-12)"
BOOT_SIZE=$(wc -c < "$BOOT_BASE" | tr -d ' ')
cat > "$BOOT_DESCRIPTOR" <<EOF
UML_BOOT_BASE_SCHEMA=1
UML_BOOT_BASE_RELEASE='$BOOT_RELEASE'
UML_BOOT_BASE_FILE='uml-boot-base-aarch64.cpio.gz'
UML_BOOT_BASE_ARCHITECTURE='aarch64'
UML_BOOT_BASE_SIZE=$BOOT_SIZE
UML_BOOT_BASE_SHA256='$BOOT_SHA'
EOF
python3 -B "$KERNARIUM_DIR/tools/rootfs/boot-ramdisk/verify_base.py" --descriptor "$BOOT_DESCRIPTOR" --archive "$BOOT_BASE" || fail 'boot base failed structural verification'
mkdir -p "$KERNARIUM_DIR/.build/rootfs/$BOOT_RELEASE"
cp "$BOOT_BASE" "$KERNARIUM_DIR/.build/rootfs/$BOOT_RELEASE/uml-boot-base-aarch64.cpio.gz"

LINE="$KERNARIUM_DIR/kernel/mainline-6.18"
KERNEL_ARTIFACTS="$KERNARIUM_DIR/.build/kernel/artifacts"
if [ -n "${COCODE_IOS_KERNEL_CACHE_DIR:-}" ] && [ -d "$COCODE_IOS_KERNEL_CACHE_DIR" ]; then
    mkdir -p "$(dirname "$KERNEL_ARTIFACTS")"
    cp -R "$COCODE_IOS_KERNEL_CACHE_DIR" "$KERNEL_ARTIFACTS"
else
    KERNEL_SOURCE="$MOUNT/linux"
    printf 'UML_KERNEL_SOURCE_DIR=%s\n' "$KERNEL_SOURCE" > "$LINE/source-location.conf"
    "$LINE/scripts/prepare-source.sh"
    "$KERNARIUM_DIR/dependencies/prepare.sh" --product kernel-host-tools
    "$KERNARIUM_DIR/dependencies/prepare.sh" --archive-manifest "$KERNARIUM_DIR/dependencies/kernel-host-tools/archives.tsv"
    "$KERNARIUM_DIR/dependencies/build-kernel-host-tools.sh"
    for product in linux android; do
        if [ "$product" = linux ]; then
            descriptor="$LINE/artifacts/accepted-4k.env"
        else
            descriptor="$LINE/artifacts/accepted-android-4k.env"
        fi
        (
            . "$descriptor"
            kernel_build="$MOUNT/build-kernel-$product"
            "$LINE/build/build-kernel.sh" --slice arm64 --product "$product" --build "$kernel_build"
            "$LINE/build/publish-artifact.py" --source "$KERNEL_SOURCE" --build "$kernel_build" \
                --product "$product" --output-root "$KERNEL_ARTIFACTS" \
                --release-id "$UML_KERNEL_ARTIFACT_RELEASE_ID" --objcopy "$(command -v llvm-objcopy)"
        )
    done
    if [ -n "${COCODE_IOS_KERNEL_CACHE_DIR:-}" ]; then
        cp -R "$KERNEL_ARTIFACTS" "$COCODE_IOS_KERNEL_CACHE_DIR"
    fi
fi

# Replaying the pinned kernel tree can produce a different Git commit ID.
# CMake still checks the descriptor's final tree, ABI and artifact inventory.
for descriptor in "$LINE/artifacts/accepted-4k.env" "$LINE/artifacts/accepted-android-4k.env"; do
    (
        . "$descriptor"
        release=$(< "$KERNEL_ARTIFACTS/$UML_KERNEL_ARTIFACT_DIRECTORY_NAME/kernel/kernel.release")
        expected_series=${UML_KERNEL_ARTIFACT_RELEASE%-g*}
        [[ "$release" =~ ^6[.]18[.][0-9]+-g[0-9a-f]+$ && "$release" = "$expected_series"-g* ]] ||
            fail "unexpected rebuilt kernel release: $release"
        sed "s/^UML_KERNEL_ARTIFACT_RELEASE=.*/UML_KERNEL_ARTIFACT_RELEASE='$release'/" "$descriptor" > "$descriptor.local"
        mv "$descriptor.local" "$descriptor"
    )
done

for product in apple-gpu-vulkan-experiment ios-network ios-storage ios-compression; do
    "$KERNARIUM_DIR/dependencies/prepare.sh" --product "$product"
done
"$KERNARIUM_DIR/dependencies/build-apple-gpu.sh" --platform ios
"$KERNARIUM_DIR/dependencies/build-ios-network.sh" --platform ios
cd "$KERNARIUM_DIR"
cmake --preset kernarium-ios-device
cmake --build --preset kernarium-ios-device --parallel
cmake --preset kernarium-ios-device-android
cmake --build --preset kernarium-ios-device-android --parallel
"$KERNARIUM_DIR/Kernarium/scripts/package-ios-frameworks.sh" --sdk device

ARCHIVE="$WORK/CoCode.xcarchive"
xcodebuild -project "$KERNARIUM_DIR/Kernarium/Kernarium.xcodeproj" \
    -scheme Kernarium -configuration Release -destination 'generic/platform=iOS' \
    -derivedDataPath "$WORK/derived" -archivePath "$ARCHIVE" CODE_SIGNING_ALLOWED=NO archive
if [[ -n "${COCODE_IOS_ARCHIVE_ZIP:-}" ]]; then
    /usr/bin/ditto -c -k --keepParent "$ARCHIVE" "$COCODE_IOS_ARCHIVE_ZIP"
fi
APP="$ARCHIVE/Products/Applications/Kernarium.app"
[ -d "$APP" ] || fail "Xcode archive missing app: $APP"
/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist" | grep -Fx io.cocode.ios >/dev/null || fail 'app bundle identifier is not io.cocode.ios'
EXECUTABLE=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Info.plist")
file "$APP/$EXECUTABLE" | grep -E 'Mach-O.*arm64' >/dev/null || fail 'app executable is not Mach-O ARM64'
if codesign -dv "$APP" >/dev/null 2>&1; then fail 'Xcode archive unexpectedly signed the app'; fi

STAGE="$WORK/ipa"
mkdir -p "$STAGE/Payload"
cp -R "$APP" "$STAGE/Payload/CoCode.app"
IPA="$OUTPUT_DIR/CoCode-unsigned.ipa"
(cd "$STAGE" && /usr/bin/zip -qry "$IPA" Payload)
unzip -t "$IPA" >/dev/null || fail 'IPA ZIP structure is invalid'
unzip -Z1 "$IPA" | grep -Fx Payload/CoCode.app/Info.plist >/dev/null || fail 'IPA lacks Payload/CoCode.app/Info.plist'
cp "$GUEST_IMAGE" "$OUTPUT_DIR/$(basename "$GUEST_IMAGE")"
cat > "$OUTPUT_DIR/README.txt" <<EOF
CoCode unsigned iOS package

Sign CoCode-unsigned.ipa with your Apple development team before installation.
After installing, copy $(basename "$GUEST_IMAGE") to the app's Documents directory.
The current app does not import a guest image from its bundle.

Kernarium pin: $PIN
Boot base producer checksum: $BOOT_SHA
EOF
(cd "$OUTPUT_DIR" && shasum -a 256 CoCode-unsigned.ipa "$(basename "$GUEST_IMAGE")" README.txt > SHA256SUMS)
(cd "$OUTPUT_DIR" && shasum -a 256 -c SHA256SUMS) || fail 'output checksum verification failed'
printf 'Created %s and guest image %s in %s\n' CoCode-unsigned.ipa "$(basename "$GUEST_IMAGE")" "$OUTPUT_DIR"
