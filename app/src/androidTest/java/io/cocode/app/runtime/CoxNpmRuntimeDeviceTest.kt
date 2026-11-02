package io.cocode.app.runtime

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.runtime.install.InstallReporter
import io.cocode.app.runtime.install.InstalledComponents
import io.cocode.app.runtime.install.PackageVersions
import io.cocode.app.runtime.install.RuntimeInstallerImpl
import io.cocode.app.runtime.install.VerifiedRuntimeDownloader
import io.cocode.app.runtime.fs.deleteTreeOrThrow
import io.cocode.app.runtime.manifest.RuntimeManifestReader
import io.cocode.app.runtime.network.AndroidSystemDnsProvider
import io.cocode.app.runtime.network.ResolvConf
import io.cocode.app.runtime.process.EmbeddedCommandSuite
import io.cocode.app.runtime.process.HealthProbe
import io.cocode.app.runtime.process.ProcessScanner
import io.cocode.app.runtime.process.ProotRuntimeServer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@LargeTest
@RunWith(AndroidJUnit4::class)
class CoxNpmRuntimeDeviceTest {
    @Test
    fun npmInstallStartRestartAndFailedUpdateKeepTheActiveEnvironment(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cocode.npmRuntimeProbe") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName != "io.cocode.app") { "Use an independent probe application id" }
        val manifest = RuntimeManifestReader {
            context.assets.open(RuntimeManifestReader.ASSET_NAME).bufferedReader().use { it.readText() }
        }.read()
        val abi = Build.SUPPORTED_ABIS.first { it in manifest.architectures }
        val paths = RuntimePaths(File(context.filesDir, "npm-runtime-device-probe"))
        val suite = { EmbeddedCommandSuite(File(context.applicationInfo.nativeLibraryDir), paths).resolve() }
        val dns = AndroidSystemDnsProvider(context)
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val password = UUID.randomUUID().toString()
        val ptyVerified = AtomicBoolean()
        val sdkVerified = AtomicBoolean()
        val report = object : InstallReporter {
            override fun step(step: InstallStep, progress: Float?, detail: String?, messageKey: MessageKey?) {
                Log.i(TAG, "STEP $step ${detail.orEmpty()}")
            }

            override fun log(line: String) {
                if (line.contains("PTYOK")) ptyVerified.set(true)
                if (line.contains("Cursor SDK prewarm ok")) sdkVerified.set(true)
                Log.i(TAG, line.replace(password, "[redacted]").replace(Regex("(token=)[^\\s]+"), "$1[redacted]"))
            }
        }
        val installer = RuntimeInstallerImpl(
            paths = paths,
            abi = abi,
            manifest = manifest,
            suite = suite,
            downloader = VerifiedRuntimeDownloader(client),
            shimAsset = { context.assets.open("android-shim.cjs").bufferedReader().use { it.readText() } },
            dnsProvider = dns,
        )
        val server = ProotRuntimeServer(
            paths = paths,
            suite = suite,
            health = HealthProbe.Loopback,
            scanner = ProcessScanner(myUid = Process.myUid(), myPid = Process.myPid().toLong()),
            readyTimeoutMillis = 120_000,
            refreshDns = { ResolvConf.write(it.rootfs, dns.dnsServers()) },
            cleanBeforeStart = { port, _ -> HealthProbe.isPortFree(port) },
        )
        try {
            check(HealthProbe.isPortFree(ServerKind.T3.port)) { "Probe port is already occupied" }
            val installed = installer.install(report, setOf("codex"), setOf(ServerKind.T3))
            val version = checkNotNull(installed.metadata.servers[ServerKind.T3.id])
            assertTrue(InstalledComponents.isCoxNpmInstall(installed.rootfs))
            assertFalse(File(installed.rootfs, "opt/t3").exists())
            assertTrue(ptyVerified.get())
            assertTrue(sdkVerified.get())
            assertEquals(version, installer.installedRuntime()?.metadata?.t3Version)
            val marker = File(paths.home, ".cox/npm-probe-marker").apply {
                parentFile?.mkdirs()
                writeText("USER_DATA_PRESERVED")
            }
            server.start(installed, password, report::log) { false }
            assertEquals(version, HealthProbe.Loopback.read(ServerKind.T3.port)?.serverVersion)
            verifyAuthAndUi(client, password)
            server.stop()
            assertFalse(server.isAlive())
            assertTrue(HealthProbe.isPortFree(ServerKind.T3.port))
            Log.i(TAG, "FRESH_INSTALL_AUTH_UI_AND_PTY_OK abi=$abi version=$version")

            exposeLegacyBundleLayout(installed.rootfs, version, abi)
            assertFalse(InstalledComponents.isCoxNpmInstall(installed.rootfs))
            assertTrue(InstalledComponents.isCoxServerBundle(installed.rootfs))
            server.start(installed, password, report::log) { false }
            verifyAuthAndUi(client, password)
            server.stop()
            Log.i(TAG, "LEGACY_BUNDLE_LAYOUT_START_OK")

            val before = paths.metadataFile.readText()
            val rejected = runCatching {
                installer.update(report, PackageVersions(t3 = "999999.0.0", agents = installed.metadata.agents))
            }.exceptionOrNull()
            assertNotNull("Unpublished target must fail", rejected)
            assertTrue("Expected a package-install failure: $rejected", rejected is UserFacingException)
            assertEquals(MessageKey.ServerInstallFailed, (rejected as UserFacingException).key)
            assertEquals(before, paths.metadataFile.readText())
            assertEquals("USER_DATA_PRESERVED", marker.readText())
            assertFalse(paths.staging.exists())
            assertEquals(version, installer.installedRuntime()?.metadata?.t3Version)
            server.start(installed, password, report::log) { false }
            verifyAuthAndUi(client, password)
            server.stop()
            Log.i(TAG, "FAILED_UPDATE_ROLLBACK_OK")

            val updated = installer.update(report, PackageVersions(t3 = version, agents = installed.metadata.agents))
            assertEquals(version, updated.metadata.t3Version)
            assertTrue(InstalledComponents.isCoxNpmInstall(updated.rootfs))
            assertFalse(File(updated.rootfs, "opt/t3").exists())
            assertEquals("USER_DATA_PRESERVED", marker.readText())
            assertFalse(paths.staging.exists())
            assertFalse(paths.rollback.exists())
            server.start(updated, password, report::log) { false }
            verifyAuthAndUi(client, password)
            Log.i(TAG, "SAME_VERSION_NPM_MIGRATION_RESTART_AUTH_OK abi=$abi version=$version")
        } finally {
            server.stop()
        }
    }

    private fun exposeLegacyBundleLayout(rootfs: File, version: String, abi: String) {
        val module = File(rootfs, InstalledComponents.COX_MODULE.removePrefix("/"))
        val legacy = File(rootfs, "opt/t3")
        deleteTreeOrThrow(legacy)
        legacy.mkdirs()
        val targets = mapOf("bin.mjs" to "dist/bin.mjs", "client" to "dist/client", "node_modules" to "node_modules")
        for ((name, target) in targets) {
            Files.createSymbolicLink(File(legacy, name).toPath(), legacy.toPath().relativize(File(module, target).toPath()))
        }
        File(legacy, "ANDROID-BUNDLE.json").writeText(
            JSONObject().put("server", "cox").put("version", version)
                .put("arch", if (abi == "x86_64") "x64" else "arm64").put("libc", "musl").toString(),
        )
        val packageFile = File(module, "package.json")
        packageFile.writeText(JSONObject(packageFile.readText()).put("name", "cox-legacy-probe").toString())
    }

    private fun verifyAuthAndUi(client: OkHttpClient, password: String) {
        fun login(credential: String) = client.newCall(
            Request.Builder().url("$BASE/api/auth/browser-session")
                .post(JSONObject().put("credential", credential).toString().toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()
        login("incorrect-password").use { assertEquals(401, it.code) }
        val cookie = login(password).use { response ->
            assertEquals(200, response.code)
            assertTrue(JSONObject(checkNotNull(response.body).string()).getBoolean("authenticated"))
            checkNotNull(response.header("Set-Cookie")).substringBefore(';')
        }
        client.newCall(Request.Builder().url(BASE).header("Cookie", cookie).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertTrue(checkNotNull(response.body).string().contains("<html", ignoreCase = true))
        }
    }

    private companion object {
        const val TAG = "CoxNpmDeviceProbe"
        const val BASE = "http://127.0.0.1:3773"
    }
}
