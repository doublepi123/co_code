package io.cocode.app.ui.home

import io.cocode.app.runtime.install.RuntimeEnvironmentInstaller

/** Fixed Cox routes, opened in the local server's authenticated WebView profile. */
internal enum class CoxSettingsPage(val path: String) {
    Models("/settings/model-services"),
    Agents("/settings/providers"),
}

/** Model services first shipped in Cox 0.1.21; legacy T3 bundles do not have that route. */
internal fun supportsCoxModelCenter(version: String?): Boolean =
    version != null && Regex("""^\d+\.\d+\.\d+$""").matches(version) &&
        RuntimeEnvironmentInstaller.compareVersions(version, "0.1.21") >= 0
