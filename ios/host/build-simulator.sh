#!/bin/bash
set -Eeuo pipefail
trap 'status=$?; printf "build-simulator: line %s failed with status %s: %s\n" "$LINENO" "$status" "$BASH_COMMAND" >&2' ERR

PIN=b14aa9e6da9df15f467d8aad0090888373cc9b21
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
fail() { printf 'build-simulator: %s\n' "$*" >&2; exit 1; }
[ "$#" -eq 4 ] || fail "Usage: $0 KERNARIUM_DIR BOOT_BASE_DIR GUEST_MANIFEST OUTPUT_DIR"
INPUT_CHECKOUT=$(cd "$1" 2>/dev/null && pwd -P) || fail "Kernarium checkout not found: $1"
BOOT_BASE_DIR=$(cd "$2" 2>/dev/null && pwd -P) || fail "boot base directory not found: $2"
GUEST_MANIFEST=$(cd "$(dirname "$3")" 2>/dev/null && printf '%s/%s' "$(pwd -P)" "$(basename "$3")") || fail "guest manifest directory not found: $3"
OUTPUT_DIR=$4
BOOT_BASE=$BOOT_BASE_DIR/uml-boot-base-aarch64.cpio.gz
[ -f "$BOOT_BASE" ] || fail "missing ARM64 boot base: $BOOT_BASE"
[ -f "$GUEST_MANIFEST" ] || fail "guest manifest not found: $GUEST_MANIFEST"
python3 - "$GUEST_MANIFEST" <<'PY' || fail 'guest manifest is invalid'
import json, re, sys
from urllib.parse import urlparse
manifest = json.load(open(sys.argv[1], encoding='utf-8'))
required = {'version', 'url', 'compressedSHA256', 'compressedSize', 'imageSHA256', 'imageSize', 'format'}
assert required <= set(manifest) <= required | {'guestTree'}
assert manifest['format'] == 2
assert re.fullmatch(r'[0-9A-Za-z._-]+', manifest['version'])
assert urlparse(manifest['url']).scheme == 'https'
for key in ('compressedSHA256', 'imageSHA256'):
    assert re.fullmatch(r'[0-9a-f]{64}', manifest[key])
for key in ('compressedSize', 'imageSize'):
    assert isinstance(manifest[key], int) and manifest[key] > 0
PY
[ -n "${BOOT_BASE_SHA256:-}" ] || fail 'BOOT_BASE_SHA256 must be set to the Ubuntu producer checksum'
BOOT_SHA=$(shasum -a 256 "$BOOT_BASE" | awk '{print $1}')
[ "$BOOT_SHA" = "$BOOT_BASE_SHA256" ] || fail "boot base SHA-256 mismatch: expected $BOOT_BASE_SHA256, got $BOOT_SHA"
[ "$(uname -s)" = Darwin ] || fail 'requires macOS'
[ "$(uname -m)" = arm64 ] || fail 'requires an Apple silicon ARM64 runner'
for tool in git xcodebuild xcrun cmake ninja python3 shasum hdiutil llvm-objcopy; do
    command -v "$tool" >/dev/null 2>&1 || fail "missing required tool: $tool"
done
XCODE_VERSION=$(xcodebuild -version | awk '/^Xcode / && !version {version=$2} END {print version}')
case "$XCODE_VERSION" in 27|27.*) ;; *) fail "requires Xcode 27; found $XCODE_VERSION" ;; esac
SDK_VERSION=$(xcrun --sdk iphonesimulator --show-sdk-version)
case "$SDK_VERSION" in 27.*) ;; *) fail "requires iOS Simulator 27 SDK; found $SDK_VERSION" ;; esac
head=$(git -C "$INPUT_CHECKOUT" rev-parse HEAD 2>/dev/null) || fail 'Kernarium_DIR is not a Git checkout'
[ "$head" = "$PIN" ] || fail "expected Kernarium revision $PIN; found $head"
[ -z "$(git -C "$INPUT_CHECKOUT" status --porcelain --untracked-files=all)" ] || fail 'Kernarium checkout must be clean'

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd "$OUTPUT_DIR" && pwd -P)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/cocode-ios-simulator.XXXXXX")
SPARSE="$WORK/case-sensitive.sparseimage"
MOUNT="$WORK/case-sensitive"
mkdir -p "$MOUNT"
cleanup() {
    if [ "$mounted" = 1 ]; then
        cd /
        # Simulator processes can hold the volume briefly after the tests end, and a
        # detach that fails can still unmount it, so judge by what remains mounted.
        for _ in 1 2 3 4 5; do
            hdiutil detach -quiet "$MOUNT" 2>/dev/null || hdiutil detach -force -quiet "$MOUNT" 2>/dev/null || true
            [ -z "$(ls -A "$MOUNT" 2>/dev/null)" ] && break
            sleep 2
        done
        if [ -n "$(ls -A "$MOUNT" 2>/dev/null)" ]; then
            printf 'build-simulator: could not detach %s\n' "$MOUNT" >&2
            return 1
        fi
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
        if [ "$product" = linux ]; then descriptor="$LINE/artifacts/accepted-4k.env"; else descriptor="$LINE/artifacts/accepted-android-4k.env"; fi
        (
            . "$descriptor"
            kernel_build="$MOUNT/build-kernel-$product"
            "$LINE/build/build-kernel.sh" --slice arm64 --product "$product" --build "$kernel_build"
            "$LINE/build/publish-artifact.py" --source "$KERNEL_SOURCE" --build "$kernel_build" \
                --product "$product" --output-root "$KERNEL_ARTIFACTS" \
                --release-id "$UML_KERNEL_ARTIFACT_RELEASE_ID" --objcopy "$(command -v llvm-objcopy)"
        )
    done
    if [ -n "${COCODE_IOS_KERNEL_CACHE_DIR:-}" ]; then cp -R "$KERNEL_ARTIFACTS" "$COCODE_IOS_KERNEL_CACHE_DIR"; fi
fi
for descriptor in "$LINE/artifacts/accepted-4k.env" "$LINE/artifacts/accepted-android-4k.env"; do
    (
        . "$descriptor"
        release=$(< "$KERNEL_ARTIFACTS/$UML_KERNEL_ARTIFACT_DIRECTORY_NAME/kernel/kernel.release")
        expected_series=${UML_KERNEL_ARTIFACT_RELEASE%-g*}
        [[ "$release" =~ ^6[.]18[.][0-9]+-g[0-9a-f]+$ && "$release" = "$expected_series"-g* ]] || fail "unexpected rebuilt kernel release: $release"
        sed "s/^UML_KERNEL_ARTIFACT_RELEASE=.*/UML_KERNEL_ARTIFACT_RELEASE='$release'/" "$descriptor" > "$descriptor.local"
        mv "$descriptor.local" "$descriptor"
    )
done

for product in apple-gpu-vulkan-experiment ios-network ios-storage ios-compression; do
    "$KERNARIUM_DIR/dependencies/prepare.sh" --product "$product"
done
"$KERNARIUM_DIR/dependencies/prepare.sh" --archive-manifest "$KERNARIUM_DIR/dependencies/llvm/archives.tsv"
# The Simulator's software GPU prefix links an LLVM built from source, so a
# cached copy from an earlier run is reused when the caller provides one.
SIM_GPU_PREFIX="$KERNARIUM_DIR/.build/dependencies/apple-gpu-vulkan-experiment/ios-simulator-arm64"
if [ -n "${COCODE_IOS_SIM_GPU_CACHE_DIR:-}" ] && [ -d "$COCODE_IOS_SIM_GPU_CACHE_DIR/prefix" ]; then
    mkdir -p "$(dirname "$SIM_GPU_PREFIX")"
    cp -R "$COCODE_IOS_SIM_GPU_CACHE_DIR/prefix" "$SIM_GPU_PREFIX"
fi
"$KERNARIUM_DIR/dependencies/build-apple-gpu.sh" --platform ios-simulator
if [ -n "${COCODE_IOS_SIM_GPU_CACHE_DIR:-}" ] && [ ! -d "$COCODE_IOS_SIM_GPU_CACHE_DIR/prefix" ]; then
    mkdir -p "$COCODE_IOS_SIM_GPU_CACHE_DIR"
    cp -R "$SIM_GPU_PREFIX" "$COCODE_IOS_SIM_GPU_CACHE_DIR/prefix"
fi
"$KERNARIUM_DIR/dependencies/build-ios-network.sh" --platform ios-simulator
cd "$KERNARIUM_DIR"
cmake --preset kernarium-ios-simulator
cmake --build --preset kernarium-ios-simulator --parallel
cmake --preset kernarium-ios-simulator-android
cmake --build --preset kernarium-ios-simulator-android --parallel
"$KERNARIUM_DIR/Kernarium/scripts/package-ios-frameworks.sh" --sdk simulator
cp "$GUEST_MANIFEST" "$KERNARIUM_DIR/Kernarium/Kernarium/CoCodeGuest.json"

DERIVED="$WORK/derived"
xcodebuild -project "$KERNARIUM_DIR/Kernarium/Kernarium.xcodeproj" \
    -scheme Kernarium -configuration Release \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$DERIVED" CODE_SIGNING_ALLOWED=NO build
APP="$DERIVED/Build/Products/Release-iphonesimulator/Kernarium.app"
[ -d "$APP" ] || fail "Xcode build missing app: $APP"
/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist" | grep -Fx io.cocode.ios >/dev/null || fail 'app bundle identifier is not io.cocode.ios'
EXECUTABLE=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Info.plist")
file "$APP/$EXECUTABLE" | grep -E 'Mach-O.*arm64' >/dev/null || fail 'app executable is not Mach-O ARM64'
cmp -s "$GUEST_MANIFEST" "$APP/CoCodeGuest.json" || fail 'app bundle lacks the guest download manifest'
[ -d "$APP/zh-Hans.lproj" ] || fail 'app bundle lacks Simplified Chinese resources'
/usr/bin/ditto "$APP" "$OUTPUT_DIR/Kernarium.app"
printf 'Built unsigned simulator app: %s\n' "$OUTPUT_DIR/Kernarium.app"

# Tests need the frameworks built above, which go with the work directory, so
# they run here. Their status is left for the caller, which still wants the app.
if [[ -n "${COCODE_IOS_TEST_ONLY:-}" ]]; then
    [[ -n "${COCODE_IOS_TEST_DESTINATION:-}" ]] || fail 'COCODE_IOS_TEST_DESTINATION must name the simulator for the tests'
    selected=()
    for test in $COCODE_IOS_TEST_ONLY; do selected+=(-only-testing:"$test"); done
    test_status=0
    # The app's own lines (sign-in, component manager) go to the unified log.
    simulator=${COCODE_IOS_TEST_DESTINATION##*id=}
    xcrun simctl spawn "$simulator" log stream --style compact --level info \
        --predicate 'process == "Kernarium" AND eventMessage CONTAINS "CoCode"' \
        > "$OUTPUT_DIR/KernariumTests-app.log" 2>&1 &
    log_stream=$!
    xcodebuild test -project "$KERNARIUM_DIR/Kernarium/Kernarium.xcodeproj" -scheme Kernarium \
        -destination "$COCODE_IOS_TEST_DESTINATION" -derivedDataPath "$WORK/test-derived" \
        -parallel-testing-enabled NO \
        -resultBundlePath "$OUTPUT_DIR/KernariumTests.xcresult" "${selected[@]}" CODE_SIGNING_ALLOWED=NO \
        > "$OUTPUT_DIR/KernariumTests-xcodebuild.log" 2>&1 || test_status=$?
    kill "$log_stream" 2>/dev/null || true
    printf '%s\n' "$test_status" > "$OUTPUT_DIR/KernariumTests.status"
    printf 'Selected Kernarium tests finished with status %s\n' "$test_status"
fi
