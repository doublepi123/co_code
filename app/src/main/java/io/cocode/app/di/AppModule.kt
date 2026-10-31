package io.cocode.app.di

import android.content.Context
import android.os.Build
import android.util.Log
import io.cocode.app.data.AndroidHttpDownloadStarter
import io.cocode.app.data.DefaultWebSessionAuthenticator
import io.cocode.app.data.EncryptedRemoteServerStore
import io.cocode.app.data.HttpDownloadStarter
import io.cocode.app.data.LocalReadiness
import io.cocode.app.data.WebSessionProfiles
import io.cocode.app.R
import io.cocode.app.notify.RuntimeAlertNotifier
import io.cocode.app.notify.RuntimeErrorWatch
import io.cocode.app.notify.NotificationObserver
import io.cocode.app.runtime.DefaultLocalRuntimeController
import android.system.Os
import io.cocode.app.runtime.ForegroundStarter
import io.cocode.app.runtime.PrefsAgentSelectionStore
import io.cocode.app.runtime.PrefsServerSelectionStore
import io.cocode.app.runtime.process.ProcessScanner
import io.cocode.app.runtime.process.RuntimeServer
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.process.killOwnedRuntime
import io.cocode.app.runtime.SharedStorageController
import io.cocode.app.runtime.storage.PrefsSharedStoragePrefs
import io.cocode.app.runtime.storage.SharedStorageAccess
import io.cocode.app.runtime.storage.SharedStorageControllerImpl
import io.cocode.app.runtime.storage.SharedStoragePermission
import io.cocode.app.runtime.LocalRuntimeController
import io.cocode.app.runtime.RemoteServerStore
import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.RuntimeSecrets
import io.cocode.app.runtime.WebSessionAuthenticator
import io.cocode.app.runtime.install.DefaultPackageVersionSource
import io.cocode.app.runtime.install.GithubT3ReleaseSource
import io.cocode.app.runtime.install.NpmPackageVersionSource
import io.cocode.app.runtime.install.RuntimeInstallerImpl
import io.cocode.app.runtime.network.AndroidNetworkEvents
import io.cocode.app.runtime.network.AndroidSystemDnsProvider
import io.cocode.app.runtime.network.DnsRefresher
import io.cocode.app.runtime.network.DnsRefresherMonitor
import io.cocode.app.runtime.network.RuntimeDnsReporter
import io.cocode.app.runtime.network.ResolvConf
import io.cocode.app.runtime.install.VerifiedRuntimeDownloader
import io.cocode.app.runtime.manifest.RuntimeManifestReader
import io.cocode.app.runtime.process.EmbeddedCommandSuite
import io.cocode.app.runtime.process.HealthProbe
import io.cocode.app.runtime.process.ProotRuntimeServer
import io.cocode.app.runtime.process.ProotStaleServerCleaner
import io.cocode.app.ui.ScreenAwakeController
import io.cocode.app.ui.UiPrefs
import io.cocode.app.service.RuntimeService
import okhttp3.OkHttpClient
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit

val appModule =
    module {
        single<OkHttpClient> {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        // Per-target WebView profiles. Replaces the old global CookieManager; there is no global fallback.
        single { WebSessionProfiles() }

        // One application-scoped starter, so downloads outlive the page that started them.
        single<HttpDownloadStarter> { AndroidHttpDownloadStarter(get<Context>()) }

        single<RemoteServerStore> { EncryptedRemoteServerStore.create(get<Context>()) }

        single { RuntimeAlertNotifier(get<Context>()) }

        // "Keep screen on while the local server runs": one instance, read by MainActivity and Home.
        // The file is the one UiPrefs uses; Android hands out one SharedPreferences per file.
        single {
            ScreenAwakeController(get<Context>().getSharedPreferences(UiPrefs.FILE, Context.MODE_PRIVATE))
        }

        single {
            val context = get<Context>()
            NotificationObserver(
                runtime = get(),
                notifier = get(),
                watch =
                    RuntimeErrorWatch(
                        RuntimeErrorWatch.Titles(
                            setupFailed = context.getString(R.string.alert_setup_failed),
                            startFailed = context.getString(R.string.alert_start_failed),
                            stopped = context.getString(R.string.alert_stopped),
                        ),
                    ),
            )
        }

        single<SharedStorageControllerImpl> {
            val context = get<Context>()
            SharedStorageControllerImpl(
                paths = RuntimePaths.under(context.filesDir),
                hostRoot = SharedStorageAccess.defaultHostRoot(),
                prefs = PrefsSharedStoragePrefs.open(context),
                permission = SharedStoragePermission { SharedStorageAccess.granted(context) },
            )
        }

        single<SharedStorageController> { get<SharedStorageControllerImpl>() }

        // Shared environment, distinct process identities. The controller runs only one at a time.
        single<Map<ServerKind, RuntimeServer>> {
            val context = get<Context>()
            val paths = RuntimePaths.under(context.filesDir)
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val suite = { EmbeddedCommandSuite(nativeDir, paths).resolve() }
            val scanner = ProcessScanner(myUid = Os.getuid(), myPid = Os.getpid().toLong())
            val storage = get<SharedStorageControllerImpl>()
            ServerKind.entries.associateWith { kind ->
                ProotRuntimeServer(
                    paths = paths,
                    kind = kind,
                    suite = suite,
                    health = HealthProbe.loopback(kind),
                    scanner = scanner,
                    signal = { pid -> android.os.Process.killProcess(pid.toInt()) },
                    refreshDns = { runtime -> ResolvConf.write(runtime.rootfs, AndroidSystemDnsProvider(context).dnsServers()) },
                    dnsMonitor =
                        DnsRefresherMonitor(
                            DnsRefresher(
                                dnsProvider = AndroidSystemDnsProvider(context),
                                events = AndroidNetworkEvents(context),
                                reporter = RuntimeDnsReporter { message -> Log.w("CoCodeDns", message) },
                            ),
                        ),
                    cleanBeforeStart = { port, proot ->
                        ProotStaleServerCleaner(
                            paths = paths,
                            scanner = scanner,
                            signal = { pid -> android.os.Process.killProcess(pid.toInt()) },
                            isPortFree = HealthProbe.Companion::isPortFree,
                        ).cleanBeforeStart(port, proot)
                    },
                    bindsForStart = { storage.prepareForStart() },
                    onStarted = { binds -> storage.recordStarted(binds) },
                    onStopped = { storage.recordStopped() },
                )
            }
        }

        single<LocalRuntimeController> {
            val context = get<Context>()
            val paths = RuntimePaths.under(context.filesDir)
            val abi = primaryAbi()
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val suite = { EmbeddedCommandSuite(nativeDir, paths).resolve() }
            val manifest =
                RuntimeManifestReader {
                    context.assets.open(RuntimeManifestReader.ASSET_NAME).bufferedReader().use { it.readText() }
                }.read()
            val dnsProvider = AndroidSystemDnsProvider(context)
            val installer =
                RuntimeInstallerImpl(
                    paths = paths,
                    abi = abi,
                    manifest = manifest,
                    suite = suite,
                    downloader = VerifiedRuntimeDownloader(get<OkHttpClient>()),
                    releaseSource = GithubT3ReleaseSource(get<OkHttpClient>(), manifest.t3.repository),
                    shimAsset = {
                        context.assets.open("android-shim.cjs").bufferedReader().use { it.readText() }
                    },
                    dnsProvider = dnsProvider,
                )
            val agentSelection =
                PrefsAgentSelectionStore.open(
                    context.getSharedPreferences(PrefsAgentSelectionStore.FILE, Context.MODE_PRIVATE),
                )
            val scanner = ProcessScanner(myUid = Os.getuid(), myPid = Os.getpid().toLong())
            val prootPath = { suite().proot.absolutePath }
            DefaultLocalRuntimeController(
                paths = paths,
                abi = abi,
                installer = installer,
                serverFor = { kind -> get<Map<ServerKind, RuntimeServer>>().getValue(kind) },
                healthFor = { kind -> HealthProbe.loopback(kind) },
                uiPassword = RuntimeSecrets::uiPassword,
                manifestAgents = manifest.agents,
                selectionStore = agentSelection,
                serverSelectionStore = PrefsServerSelectionStore.open(
                    context.getSharedPreferences(PrefsServerSelectionStore.FILE, Context.MODE_PRIVATE),
                ),
                pinnedT3Version = manifest.t3.version,
                foregroundStarter = ForegroundStarter { op -> RuntimeService.request(context, op) },
                killOwned = {
                    killOwnedRuntime(scanner, prootPath(), paths.rootfs.absolutePath) { pid ->
                        android.os.Process.killProcess(pid.toInt())
                    }
                },
                versionSource =
                    DefaultPackageVersionSource(
                        npm = NpmPackageVersionSource(get<OkHttpClient>()),
                        t3 = GithubT3ReleaseSource(get<OkHttpClient>(), manifest.t3.repository),
                        agents = manifest.agents,
                        fallbackT3Version = manifest.t3.version,
                        openChamberPackage = manifest.openchamber.npmPackage,
                    ),
            ).also { it.initialize() }
        }

        single<WebSessionAuthenticator> {
            val paths = RuntimePaths.under(get<Context>().filesDir)
            DefaultWebSessionAuthenticator(
                client = get(),
                localPassword = { kind -> RuntimeSecrets.existingUiPassword(paths, kind) },
                localReady = LocalReadiness { kind ->
                    if (get<LocalRuntimeController>().servers.value.active == kind) {
                        get<Map<ServerKind, RuntimeServer>>().getValue(kind).readyLaunchId()
                    } else {
                        null
                    }
                },
            )
        }
    }

private fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
