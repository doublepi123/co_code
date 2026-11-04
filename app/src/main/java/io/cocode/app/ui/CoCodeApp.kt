package io.cocode.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.cocode.app.R
import io.cocode.app.data.canonicalWebOrigin
import io.cocode.app.data.WebProfileRemovalResult
import io.cocode.app.runtime.FailurePhase
import io.cocode.app.runtime.LocalRuntimeController
import io.cocode.app.runtime.RemoteServerStore
import io.cocode.app.runtime.RuntimeState
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerTarget
import io.cocode.app.runtime.SharedStorageController
import io.cocode.app.runtime.WebSessionAuthenticator
import io.cocode.app.ui.home.HomeActions
import io.cocode.app.ui.home.HomeScreen
import io.cocode.app.ui.theme.CoCodeTheme
import io.cocode.app.ui.web.WebScreen
import io.cocode.app.ui.web.WebViewPool
import io.cocode.app.ui.web.rememberWebDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object Routes {
    const val HOME = "home"
    const val ARG_TARGET = "targetId"
    const val WEB = "web/{$ARG_TARGET}"

    fun web(targetId: String) = "web/${Uri.encode(targetId)}"
}

/**
 * Local runtime actions as the UI uses them. Asks for the notification permission on Set up and
 * Start (never blocking), and makes "Try again" repeat whatever failed: setup or start.
 */
private class LocalActions(
    private val runtime: LocalRuntimeController,
    private val askForNotifications: () -> Unit,
    private val pool: WebViewPool,
) {
    /** True while the most recent work was setup, so a failure there retries the setup. */
    private var inSetup = false

    /**
     * The state a start was requested from, until the runtime reports back. Screens show such a
     * start as Starting right away (see [shownLocalState]) instead of the old Stopped or failure.
     */
    var pendingStartFrom by mutableStateOf<RuntimeState?>(null)
        private set

    fun observe(state: RuntimeState) {
        when (state) {
            is RuntimeState.Installing -> inSetup = true
            is RuntimeState.Failed, RuntimeState.NotInstalled -> Unit
            // Setup finished (the controller starts the server right after it).
            else -> inSetup = false
        }
        val from = pendingStartFrom
        if (from != null && state != from) pendingStartFrom = null
    }

    fun clearPendingStart(from: RuntimeState) {
        if (pendingStartFrom == from) pendingStartFrom = null
    }

    fun install() {
        askForNotifications()
        inSetup = true
        runtime.install()
    }

    fun start() {
        askForNotifications()
        inSetup = false
        val from = runtime.state.value
        // A start already requested from this same state hasn't reached the runtime yet; a second
        // one would queue behind it and start the server twice.
        if (pendingStartFrom != null && from == pendingStartFrom) return
        runtime.start()
        // The request only reaches the runtime through the service, a moment later.
        if (from is RuntimeState.Stopped || from is RuntimeState.Failed) pendingStartFrom = from
    }

    /** Retry redoes exactly what failed: setup failures reinstall, server failures start again. */
    fun retry() {
        val failed = runtime.state.value as? RuntimeState.Failed
        if (failed?.phase == FailurePhase.Install) install() else start()
    }

    /** Start from Stopped, or retry after a failure. */
    fun startOrRetry() {
        if (runtime.state.value is RuntimeState.Failed) retry() else start()
    }

    fun stop() = runtime.stop()

    /**
     * Stop, then start, so new folder settings apply. One request, made right away while the app is
     * in the foreground; the controller runs stop and start as a single queued operation.
     */
    fun restart() {
        if (runtime.state.value !is RuntimeState.Running) return
        askForNotifications()
        runtime.restart()
    }

    fun repair() {
        askForNotifications()
        inSetup = true
        discardLocalPages()
        runtime.repair()
    }

    /** Both on-device servers share the pool's local slot; whichever page is there goes. */
    private fun discardLocalPages() = LOCAL_TARGET_IDS.forEach(pool::discard)

    /**
     * Makes [kind] the active local server. The runtime stops the current one and starts [kind];
     * its page is a different identity, so the pool rebinds the local slot to [kind]'s own profile.
     */
    fun selectServer(kind: ServerKind) {
        askForNotifications()
        runtime.selectServer(kind)
    }

    /** Saves the server pick before setup, or applies it afterwards. Empty sets never reach the runtime. */
    fun setServers(selected: Set<ServerKind>) {
        if (selected.isEmpty()) return
        if (runtime.state.value !is RuntimeState.NotInstalled) askForNotifications()
        runtime.setServers(selected)
    }

    /**
     * Updates T3 Code / OpenCode. The runtime stops the server, swaps in the new versions and
     * starts it again if it was running, so the local page is dropped like on Repair. A failed
     * update shows in [LocalRuntimeController.updates]; the old version keeps working.
     */
    fun update() {
        askForNotifications()
        discardLocalPages()
        runtime.update()
    }

    fun checkForUpdates() = runtime.checkForUpdates()

    fun dismissUpdateFailure() = runtime.dismissUpdateFailure()

    fun uninstall() {
        discardLocalPages()
        runtime.uninstall()
    }
}

/**
 * Root of the UI: a control-room home screen and a full-screen T3 Code web screen per server.
 *
 * Once the local server is set up, a launch goes straight to it (see [openLocalOnLaunch]); Home
 * is where setup, problems and the other servers live.
 *
 * Runtime and server flows are collected inside each destination rather than here, so the
 * navigation graph isn't rebuilt every time a log line arrives.
 */
@Composable
internal fun CoCodeApp(
    runtime: LocalRuntimeController,
    remotes: RemoteServerStore,
    auth: WebSessionAuthenticator,
    storage: SharedStorageController,
) {
    CoCodeTheme {
        val nav = rememberNavController()
        // Per-server WebView profiles and the app-wide downloader (both from di/AppModule.kt).
        val webDeps = rememberWebDependencies()
        val pool = remember(webDeps) { WebViewPool(webDeps.downloads) }
        DisposableEffect(pool) { onDispose { pool.destroyAll() } }

        // A server being removed is frozen for open/edit/remove until its own profile data is cleared.
        // Only a successful clear commits the removal; failure leaves the saved server in place.
        val pendingRemovalIds = remember { mutableStateMapOf<String, Unit>() }
        val removalScope = rememberCoroutineScope()
        val context = LocalContext.current

        val askForNotifications = rememberNotificationPermissionRequest()
        val local = remember(runtime, askForNotifications, pool) { LocalActions(runtime, askForNotifications, pool) }
        LaunchedEffect(local) { runtime.state.collect(local::observe) }
        // Last resort, in case the runtime never reports back on a start. Read in a flow, not in
        // composition, so the root doesn't recompose for it.
        LaunchedEffect(local) {
            snapshotFlow { local.pendingStartFrom }.collectLatest { from ->
                if (from != null) {
                    delay(PENDING_START_TIMEOUT_MS)
                    local.clearPendingStart(from)
                }
            }
        }

        // Shared-storage calls touch the disk: run them in order, off the main thread.
        val storageJobs = remember { Channel<() -> Unit>(Channel.UNLIMITED) }
        LaunchedEffect(storageJobs) {
            withContext(Dispatchers.IO) {
                for (job in storageJobs) runCatching { job() }
            }
        }
        val refreshStorage: () -> Unit =
            remember(storage) {
                { storageJobs.trySend(storage::refreshPermission) }
            }
        // Picks up access granted (or revoked) in system settings when the user comes back.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshStorage() }
        val requestStorageAccess = rememberStorageAccessRequest(onResult = refreshStorage)

        // Until the runtime has looked at the disk it reports Detecting; no "Set up" offer before then.
        val runtimeState = runtime.state.collectAsState().value
        val checking = runtimeState == RuntimeState.Detecting

        // --- Launch: decide once per launch whether to open the last used T3 Code directly. ---
        val uiPrefs = rememberUiPrefs()
        val inspection = LocalInspectionMode.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        // A launch opens the local server only when the user asked for it at startup.
        val wantsOpen = {
            !inspection && uiPrefs.autoOpenLocal
        }
        // The instance the user last had open, read once on the first frame. A remembered remote
        // needs no runtime detection, so its resume is decided already.
        val rememberedId = remember { uiPrefs.lastTargetId }
        val rememberedRemoteId =
            remember {
                val remoteIds = remotes.servers.value.mapTo(HashSet()) { it.id }
                (resumeTarget(rememberedId, remoteIds) as? ResumeTarget.Remote)?.targetId
            }
        // Every instance keeps its own last page; a server removed elsewhere loses its page.
        LaunchedEffect(inspection) {
            if (inspection) return@LaunchedEffect
            remotes.servers.collect { servers ->
                uiPrefs.retainPages(servers.mapTo(HashSet()) { it.id } + LOCAL_TARGET_IDS)
            }
        }
        // Saved, so recreation never runs the decision again; Back to Home then stays on Home.
        var launchPending by rememberSaveable { mutableStateOf(true) }
        var stage by remember {
            mutableStateOf(
                initialLaunchStage(launchPending, wantsOpen(), runtime.state.value, remoteResume = rememberedRemoteId != null),
            )
        }
        // A tap on the app icon while the app is open on Home or the native phone page counts as a
        // new launch: no chat is in front there, so the launch goes to the remembered remote, or to
        // the local instance when Home/the phone page was the last place (Home is forgotten).
        LauncherIntentEffect {
            val route = nav.currentBackStackEntry?.destination?.route
            if (route != Routes.WEB && stage == LaunchStage.Done) launchPending = true
        }
        LaunchedEffect(launchPending) {
            if (!launchPending) return@LaunchedEffect
            if (wantsOpen()) {
                try {
                    // Re-read at launch time: the frame-one values may have been overtaken by the
                    // watcher below (e.g. the user opened a server and went Home).
                    when (val choice = resumeTarget(uiPrefs.lastTargetId, remotes.servers.value.mapTo(HashSet()) { it.id })) {
                        is ResumeTarget.Remote ->
                            openRemoteOnLaunch(
                                nav = nav,
                                lifecycle = lifecycle,
                                targetId = choice.targetId,
                                covered = stage != LaunchStage.Done,
                                onOpening = { stage = LaunchStage.Opening },
                            )
                        is ResumeTarget.Local ->
                            openLocalOnLaunch(
                                remembered = choice.kind,
                                runtime = runtime,
                                nav = nav,
                                lifecycle = lifecycle,
                                local = local,
                                covered = stage != LaunchStage.Done,
                                onOpening = { stage = LaunchStage.Opening },
                                onReveal = { stage = LaunchStage.Done },
                            )
                    }
                } catch (error: CancellationException) {
                    // Left composition mid-launch: stays pending, so a recreated activity decides again.
                    throw error
                } catch (_: Exception) {
                    // Whatever went wrong, the user lands somewhere usable: Home, without the cover.
                }
            }
            stage = LaunchStage.Done
            launchPending = false
        }

        // Remember the instance being viewed, so the next launch can reopen it. Going Home (Back
        // from a remote or an overlay, the edge handle's Home) forgets which one it was — Home
        // is never "last used" — but every instance keeps its own last page. Back from the local
        // page leaves the app instead, so the record survives.
        LaunchedEffect(inspection) {
            if (inspection) return@LaunchedEffect
            // The launch owns the record while the cover is up; once settled, every navigation
            // updates it (the entry flow replays the current entry when this re-subscribes).
            snapshotFlow { stage }.collectLatest { settled ->
                if (settled != LaunchStage.Done) return@collectLatest
                nav.currentBackStackEntryFlow.collect { entry ->
                    when (entry.destination.route) {
                        Routes.WEB -> {
                            val id = entry.arguments?.getString(Routes.ARG_TARGET) ?: return@collect
                            uiPrefs.setLastTargetId(id)
                        }
                        else -> uiPrefs.clearLastTarget()
                    }
                }
            }
        }

        var webBarColor by remember { mutableStateOf<Color?>(null) }
        val background = MaterialTheme.colorScheme.background
        val currentEntry by nav.currentBackStackEntryAsState()
        val onWeb = currentEntry?.destination?.route == Routes.WEB
        WindowBackgroundEffect(background)
        SystemBarsEffect(darkIcons = ((if (onWeb) webBarColor else null) ?: background).luminance() > 0.5f)

        Box(modifier = Modifier.fillMaxSize().background(background)) {
            // Nothing but the cover until the launch is decided, so Home never flashes by. Under the
            // cover, screen changes are instant; the cover's fade is the only transition.
            if (stage != LaunchStage.Waiting) {
                NavHost(
                    navController = nav,
                    startDestination = Routes.HOME,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        if (stage != LaunchStage.Done) {
                            EnterTransition.None
                        } else {
                            fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.985f)
                        }
                    },
                    exitTransition = { if (stage != LaunchStage.Done) ExitTransition.None else fadeOut(tween(160)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.985f) },
                ) {
                    composable(Routes.HOME) { entry ->
                        val state by runtime.state.collectAsStateWithLifecycle()
                        val info by runtime.info.collectAsStateWithLifecycle()
                        val logLines by runtime.logTail.collectAsStateWithLifecycle()
                        val servers by remotes.servers.collectAsStateWithLifecycle()
                        val storageState by storage.state.collectAsStateWithLifecycle()
                        val updates by runtime.updates.collectAsStateWithLifecycle()
                        val agents by runtime.agents.collectAsStateWithLifecycle()
                        val localServers by runtime.servers.collectAsStateWithLifecycle()
                        val actions =
                            remember(local, remotes, pool, nav, entry, storage, refreshStorage, requestStorageAccess, uiPrefs, webDeps) {
                                HomeActions(
                                    onSetUp = local::install,
                                    onStart = local::start,
                                    onRetry = local::retry,
                                    onStop = local::stop,
                                    onRepair = local::repair,
                                    onUninstall = local::uninstall,
                                    onOpenLocal = {
                                        // The active server's own page (its own profile and last page).
                                        runtime.servers.value.openableKind()?.let { kind ->
                                            nav.openIfResumed(entry, Routes.web(kind.localTargetId))
                                        }
                                    },
                                    onOpenRemote = { server ->
                                        // A server being removed has no profile to open any more.
                                        if (server.id !in pendingRemovalIds) nav.openIfResumed(entry, Routes.web(server.id))
                                    },
                                    onSaveRemote = { server ->
                                        if (server.id !in pendingRemovalIds) remotes.upsert(server)
                                    },
                                    onMoveRemote = { server, toIndex ->
                                        if (server.id !in pendingRemovalIds) remotes.move(server.id, toIndex)
                                    },
                                    onRemoveRemote = { server ->
                                        if (server.id !in pendingRemovalIds) {
                                            pendingRemovalIds[server.id] = Unit
                                            removalScope.launch {
                                                try {
                                                    val profiles = webDeps.profiles
                                                    val result =
                                                        profiles?.removeProfile(server) { pool.discard(server.id) }
                                                            ?: WebProfileRemovalResult.NothingStored
                                                    when (result) {
                                                        WebProfileRemovalResult.Removed,
                                                        WebProfileRemovalResult.NothingStored -> {
                                                            // Committing is not a suspension point: once the
                                                            // profile is cleared, the saved server is removed
                                                            // even if the composition is being torn down.
                                                            withContext(NonCancellable) {
                                                                uiPrefs.forgetPage(server.id)
                                                                remotes.remove(server.id)
                                                            }
                                                        }
                                                        is WebProfileRemovalResult.Failed -> {
                                                            Toast.makeText(
                                                                context,
                                                                R.string.remote_remove_clear_failed,
                                                                Toast.LENGTH_SHORT,
                                                            ).show()
                                                        }
                                                    }
                                                } finally {
                                                    pendingRemovalIds.remove(server.id)
                                                }
                                            }
                                        }
                                    },
                                    onSharedStorageChange = { on ->
                                        storageJobs.trySend {
                                            if (on) storage.setEnabledWithFullDirectoryConsent(true)
                                            else storage.setEnabled(false)
                                        }
                                    },
                                    onAllowStorageAccess = requestStorageAccess,
                                    onCheckStorageAgain = refreshStorage,
                                    onRestartLocal = local::restart,
                                    onAutoOpenChange = uiPrefs::updateAutoOpenLocal,
                                    onUpdate = local::update,
                                    onCheckForUpdates = local::checkForUpdates,
                                    onDismissUpdateFailure = local::dismissUpdateFailure,
                                    onAgentsChange = runtime::setAgents,
                                    onOpenCoxSettings = { page ->
                                        if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                            val kind = ServerKind.T3
                                            val id = kind.localTargetId
                                            val origin = canonicalWebOrigin("http://127.0.0.1:${kind.port}")!!
                                            uiPrefs.setPage(id, page.path, origin)
                                            // A shortcut must replace a pooled chat page too.
                                            pool.discard(id)
                                            if (runtime.state.value !is RuntimeState.Running && runtime.state.value !is RuntimeState.Starting) {
                                                local.start()
                                            }
                                            nav.openIfResumed(entry, Routes.web(id))
                                        }
                                    },
                                )
                            }
                        HomeScreen(
                            state = shownLocalState(state, local.pendingStartFrom),
                            checking = checking,
                            info = info,
                            logLines = logLines,
                            servers = servers,
                            actions = actions,
                            storage = storageState,
                            autoOpenLocal = uiPrefs.autoOpenLocal,
                            updates = updates,
                            agents = agents,
                            localServers = localServers,
                        )
                    }

                    composable(
                        route = Routes.WEB,
                        arguments = listOf(navArgument(Routes.ARG_TARGET) { type = NavType.StringType }),
                    ) { entry ->
                        val state by runtime.state.collectAsStateWithLifecycle()
                        val servers by remotes.servers.collectAsStateWithLifecycle()
                        val localServers by runtime.servers.collectAsStateWithLifecycle()
                        val targetId = entry.arguments?.getString(Routes.ARG_TARGET).orEmpty()
                        // Each on-device server has its own id; the runtime state belongs to the active one.
                        val pageKind = localKindOf(targetId)
                        val isLocal = pageKind != null
                        val pageIsActive = pageKind != null && pageKind == localServers.active
                        val running = (state as? RuntimeState.Running)?.takeIf { pageIsActive }
                        val target: ServerTarget? =
                            if (isLocal) {
                                running?.let { ServerTarget.Local(it.url, kind = pageKind!!) }
                            } else {
                                servers.firstOrNull { it.id == targetId }
                            }
                        // A new key means a new server session: sign in again and reload the page.
                        val sessionKey =
                            when (target) {
                                is ServerTarget.Local -> "local|${target.kind.id}|${running?.sinceEpochMs}|${target.baseUrl}"
                                is ServerTarget.Remote ->
                                    "remote|${target.id}|${target.baseUrl}|${target.uiPassword.orEmpty().hashCode()}"
                                null -> null
                            }
                        WebScreen(
                            targetId = targetId,
                            target = target,
                            // Another server's page while this one is active shows that server as stopped.
                            localState =
                                when {
                                    !isLocal -> null
                                    pageIsActive -> shownLocalState(state, local.pendingStartFrom)
                                    else -> RuntimeState.Stopped
                                },
                            checking = checking,
                            sessionKey = sessionKey,
                            auth = auth,
                            profiles = webDeps.profiles,
                            pool = pool,
                            onExit = {
                                nav.closeIfResumed(entry)
                            },
                            // Start on another server's page switches to it (the runtime stops the current one).
                            onStartLocal = {
                                if (pageKind == null || pageIsActive) local.startOrRetry() else local.selectServer(pageKind)
                            },
                            onSystemBarColor = { webBarColor = it },
                            // This instance's own last page, applied when its page is loaded fresh
                            // (switching instances or a cold launch), and only if the server still
                            // answers at the origin it was remembered on (a re-pointed remote drops it).
                            resumePath =
                                uiPrefs.pageFor(targetId)?.let { page ->
                                    val targetOrigin = target?.baseUrl?.let(::canonicalWebOrigin)
                                    page.path.takeIf { targetOrigin != null && targetOrigin == page.origin }
                                },
                            onPageLocation = { url ->
                                val base = target?.baseUrl ?: return@WebScreen
                                val origin = canonicalWebOrigin(base) ?: return@WebScreen
                                sanitizedPagePath(url, base)?.let { uiPrefs.setPage(targetId, it, origin) }
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = stage != LaunchStage.Done,
                enter = EnterTransition.None,
                exit = fadeOut(tween(LAUNCH_COVER_FADE_MS)),
            ) {
                LaunchCover()
            }
        }

        // CoCode's own GitHub release check, apart from the T3 Code runtime updates. It never waits
        // on the launch; its dialog appears over Home or a server's page once the cover is gone.
        AppUpdatePrompt(ready = stage == LaunchStage.Done)
    }
}

/**
 * The launch decision for an installed local server: open it, starting it first if it is stopped.
 * Returns without navigating when Home is the right place (setup, problems, unsupported device)
 * or when the user isn't on Home anymore.
 *
 * [covered]: the launch cover is up, so screens switch instantly underneath it and this waits for
 * the web screen's first frame before the cover may fade. [onOpening] composes the screens under
 * the cover; [onReveal] drops the cover early, when detection is slow.
 */
private suspend fun openLocalOnLaunch(
    /** The local server the user last had open, if any; only reopened while it is installed. */
    remembered: ServerKind?,
    runtime: LocalRuntimeController,
    nav: NavHostController,
    lifecycle: Lifecycle,
    local: LocalActions,
    covered: Boolean,
    onOpening: () -> Unit,
    onReveal: () -> Unit,
) {
    var underCover = covered
    val detected =
        withTimeoutOrNull(LAUNCH_DETECT_TIMEOUT_MS) { runtime.state.first { it != RuntimeState.Detecting } }
            ?: run {
                // Slow (e.g. finishing an interrupted repair): show Home with its "Checking" card
                // rather than a cover with no way out, and keep waiting for the answer.
                if (underCover) onReveal()
                underCover = false
                runtime.state.first { it != RuntimeState.Detecting }
            }
    if (launchTarget(detected) == LaunchTarget.Home) return
    if (underCover) onOpening()
    // The navigation graph exists once the screens have been composed.
    withTimeoutOrNull(NAV_READY_TIMEOUT_MS) { nav.currentBackStackEntryFlow.first() } ?: return
    // Starting the foreground service needs the app in the foreground. Everything from here on runs
    // without suspending, so the app is still resumed when the start is requested.
    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
    // A launch starts from Home; any chat page already in front is left alone.
    val from = nav.currentBackStackEntry?.destination?.route
    if (from != Routes.HOME) return
    // The remembered server only when it is installed; otherwise the active one. Not-installed never.
    val servers = runtime.servers.value
    val kind = localKindToOpen(remembered, servers) ?: return
    // Decide on the state as it is now; it may have moved on while the screens came up.
    when (launchTarget(runtime.state.value)) {
        LaunchTarget.StartAndOpenLocal, LaunchTarget.OpenLocal ->
            if (kind != servers.active) {
                // The other installed server: the runtime switches (stops the current one, starts this).
                if (runCatching { local.selectServer(kind) }.isFailure) return
            } else if (launchTarget(runtime.state.value) == LaunchTarget.StartAndOpenLocal) {
                // If the start can't even be requested, Home shows the server as stopped, with Start.
                if (runCatching { local.start() }.isFailure) return
            }
        LaunchTarget.Home, LaunchTarget.Wait -> return
    }
    nav.navigate(Routes.web(kind.localTargetId)) {
        launchSingleTop = true
    }
    if (underCover) {
        withTimeoutOrNull(NAV_READY_TIMEOUT_MS) {
            nav.currentBackStackEntryFlow.first { it.destination.route == Routes.WEB }
        }
        // Two frames: the web screen is composed, then drawn, before the cover fades.
        withFrameNanos { }
        withFrameNanos { }
    }
}

/**
 * A remembered remote server on launch: open it right away (it needs no runtime), still from Home
 * only. Its web screen shows the Connecting or Unreachable state itself. Under the launch cover the
 * switch is instant and the cover fades once the web screen has drawn a frame.
 */
private suspend fun openRemoteOnLaunch(
    nav: NavHostController,
    lifecycle: Lifecycle,
    targetId: String,
    covered: Boolean,
    onOpening: () -> Unit,
) {
    if (covered) onOpening()
    withTimeoutOrNull(NAV_READY_TIMEOUT_MS) { nav.currentBackStackEntryFlow.first() } ?: return
    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
    // Home only; never another page.
    val from = nav.currentBackStackEntry?.destination?.route
    if (from != Routes.HOME) return
    nav.navigate(Routes.web(targetId)) {
        launchSingleTop = true
    }
    if (covered) {
        withTimeoutOrNull(NAV_READY_TIMEOUT_MS) {
            nav.currentBackStackEntryFlow.first { it.destination.route == Routes.WEB }
        }
        withFrameNanos { }
        withFrameNanos { }
    }
}

/**
 * Calls [onLauncherTap] when the app icon is tapped while this activity is alive. Android only
 * delivers that tap to an existing activity when it is declared `launchMode="singleTop"`.
 */
@Composable
private fun LauncherIntentEffect(onLauncherTap: () -> Unit) {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return
    val current by rememberUpdatedState(onLauncherTap)
    DisposableEffect(activity) {
        val listener = Consumer<Intent> { intent -> if (intent.isLauncherLaunch()) current() }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
}

/** A start request the runtime hasn't acknowledged after this long shows its real state again. */
private const val PENDING_START_TIMEOUT_MS = 8_000L

/** Upper bound for the navigation graph and the web screen to come up under the launch cover. */
private const val NAV_READY_TIMEOUT_MS = 2_000L

/** Ignores repeated taps while a transition is already running. */
private fun NavHostController.openIfResumed(
    from: NavBackStackEntry,
    route: String,
) {
    if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        navigate(route) { launchSingleTop = true }
    }
}

private fun NavHostController.closeIfResumed(entry: NavBackStackEntry) {
    if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    showHome()
}

private fun NavHostController.showHome() {
    if (!popBackStack(Routes.HOME, inclusive = false)) {
        navigate(Routes.HOME) {
            popUpTo(0)
            launchSingleTop = true
        }
    }
}
