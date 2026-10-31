package io.cocode.app.i18n

import android.content.Context
import androidx.annotation.StringRes
import io.cocode.app.R
import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.MessageKey
import io.cocode.app.runtime.RuntimeState

/**
 * The one place that turns runtime keys into localised text. The UI and the notifications both go
 * through here, so a headline reads the same everywhere.
 */
object UserMessages {
    @StringRes
    fun headline(key: MessageKey): Int =
        when (key) {
            MessageKey.MirrorsUnreachable -> R.string.msg_mirrors_unreachable
            MessageKey.PackagesFailed -> R.string.msg_packages_failed
            MessageKey.ServerInstallFailed -> R.string.msg_server_install_failed
            MessageKey.AgentInstallFailed -> R.string.msg_agent_install_failed
            MessageKey.AgentsRequired -> R.string.msg_agents_required
            MessageKey.PtySmokeFailed -> R.string.msg_pty_smoke_failed
            MessageKey.PortInUse -> R.string.msg_port_in_use
            MessageKey.ServerExitedDuringStart -> R.string.msg_server_exited_during_start
            MessageKey.ServerNotListeningInTime -> R.string.msg_server_not_listening_in_time
            MessageKey.ServerNotReadyInTime -> R.string.msg_server_not_ready_in_time
            MessageKey.ServerKeptStopping -> R.string.msg_server_kept_stopping
            MessageKey.ServerStoppedUnexpectedly -> R.string.msg_server_stopped_unexpectedly
            MessageKey.ServerRestartFailed -> R.string.msg_server_restart_failed
            MessageKey.EmbeddedProotMissing -> R.string.msg_embedded_proot_missing
            MessageKey.Generic -> R.string.msg_generic
            MessageKey.ProgressUnpacking -> R.string.msg_progress_unpacking
            MessageKey.ProgressInstallingPackages -> R.string.msg_progress_installing_packages
            MessageKey.ProgressInstallingServer -> R.string.msg_progress_installing_server
            MessageKey.ProgressInstallingAgents -> R.string.msg_progress_installing_agents
            MessageKey.ProgressVerifying -> R.string.msg_progress_verifying
            MessageKey.ProgressActivating -> R.string.msg_progress_activating
            MessageKey.ProgressDone -> R.string.msg_progress_done
            MessageKey.ProgressCopyingEnvironment -> R.string.msg_progress_copying_environment
            MessageKey.UpdateNotEnoughSpace -> R.string.msg_update_not_enough_space
        }

    /**
     * The localised headline for a failed state: the key's text when there is one, otherwise the
     * English technical reason (never empty, so the user always sees something).
     */
    fun failureText(
        context: Context,
        failed: RuntimeState.Failed,
    ): String =
        failed.messageKey?.let { context.getString(headline(it)) }
            ?: failed.reason.ifBlank { context.getString(R.string.msg_generic) }

    /** Localised text for an install step's label. */
    @StringRes
    fun stepLabel(step: InstallStep): Int =
        when (step) {
            InstallStep.DownloadRootfs -> R.string.step_download_rootfs
            InstallStep.ExtractRootfs -> R.string.step_extract_rootfs
            InstallStep.InstallPackages -> R.string.step_install_packages
            InstallStep.CopyEnvironment -> R.string.step_copy_environment
            InstallStep.InstallServer -> R.string.step_install_server
            InstallStep.InstallAgents -> R.string.step_install_agents
            InstallStep.Verify -> R.string.step_verify
        }
}
