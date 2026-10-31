# Dual-server and shared-folder verification

This record covers the development implementation after v0.1.0. It is not evidence of physical-phone or vendor-background-policy compatibility.

## Automated checks

- Full JVM suite: 533 tests, no failures, errors or skips.
- Python build-tool suite: 61 tests passed.
- Android 15 / API 35 x86_64 emulator: 30 instrumentation tests, no failures, errors or skips. Gradle's connected-test XML and UTP results were inspected.
- The tested development APK SHA-256 was `53e23edfd84579e1ef9f50fecc59fd5437d9dd11cc6984e054f04bae4fd19b29`; the test APK was `393a7e16772127932932eb98b246096e807884b91e62a1f1d7bb36d1d75f936a`.

The instrumentation classes cover:

- `AgentPickerComposeTest`: empty drafts do not crash or enable setup; adding OpenCode retains Codex; removals require explicit confirmation.
- `ServerPickerComposeTest`: installation choices are separate from agent choices; switching and removal require confirmation.
- `SharedStorageConsentComposeTest`: credential exposure requires acknowledgement, including an existing v0.1.0 shared-storage preference. Legacy sharing remains projects/OpenCode-only until accepted.
- `MaintenanceProgressComposeTest`: adding OpenChamber does not claim to perform an unrelated T3 update.
- `AppUpdateDialogComposeTest`: version/notes and the independent Later/View release actions.
- `RemoteServerProtocolTest`: legacy remote entries remain T3; explicit OpenChamber kind survives reload; unknown kinds are not guessed.
- `SharedStorageGuestBindTest`: actual PRoot directory binds over Android shared storage accept atomic configuration and credential saves for both Codex and Claude.
- `SharedStorageMigrationDeviceTest`: actual shared-storage exclusive creation, pre-existing/raced target preservation, incomplete-copy conflict, completed-copy recovery, missing-target retry and failure before target creation when the private journal cannot be durable.

## Runtime checks

On the x86_64 emulator, a fresh T3-only setup with Codex completed. OpenCode was then added through the app while Codex remained selected. Both the committed metadata and the actual binaries remained present: `codex-cli 0.166.0` and `opencode v2.2.5`.

On an ARM64 host running an Android 12 / API 31 redroid container, upgrading an existing installation retained T3 data, agent installations and the existing application-data lifetime. Adding OpenChamber 2.5.3 preserved the T3 installation and all three agents. OpenChamber's separate musl node-pty build succeeded. T3 switching back and restart restoration were verified. OpenChamber activation initially failed because another application occupied port 3000; CoCode correctly refused to authenticate that listener. That initial failure remains recorded separately.

After explicit permission to temporarily stop the older application, the same final development APK listed above was verified on ARM64:

- Device `base.apk` SHA-256 matched the tested artifact; the original first-install time and existing agent/data records were retained.
- Switching to OpenChamber stopped T3's listener, started OpenChamber on port 3000, and reported `/health` ready with OpenCode running and its binary managed by policy.
- The actual OpenChamber WebView loaded authenticated. Its separate password produced successful login/session probes; the OpenCode upgrade endpoint returned policy rejection HTTP 409.
- A synthetic OpenCode-config marker had identical host/guest hashes under the shared home and was removed after checking.
- Switching back stopped OpenChamber, restored T3 on port 3773 with its original environment identity, and opened the existing T3 threads without another interactive login.
- The same seven migration and two guest-bind storage tests passed on ARM64: nine tests, no failures or skips.
- The older application was reopened after the test and recovered its previous port and data. CoCode remained on T3. No real credentials were copied into public storage during acceptance testing; directory tests used synthetic disposable data.

The ARM64 container results do not represent a physical phone or a Pi400 deployment. Vendor background policies remain unverified.

## Recorded failure and replacement

An earlier implementation attempted `renameat2(RENAME_NOREPLACE)` publication through a JNI helper. Real Android 15 shared storage rejected absent-target publication with `errno=22`: two of six device tests failed. Existing-target protection and whole-directory save tests did not establish that publication worked.

That helper and its build changes were removed. The implementation now keeps complete app-private snapshots and durable recovery journals, creates public targets exclusively with `CREATE_NEW`, verifies SHA-256 after writing and refuses to accept incomplete or conflicting copies. It does **not** claim atomic visibility to other applications during the copy. The 30 passing tests above cover the replacement, not the failed JNI implementation.

## Reproduction

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```

Device migration and guest-bind tests require all-files access and an installed runtime. Missing prerequisites are failures, not skipped acceptance checks. They use synthetic data in disposable `.storage-test-*` directories, not real credentials. Connected-test tooling may uninstall the application after testing; use a disposable emulator or preserve required data before running it.
