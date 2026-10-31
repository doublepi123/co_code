package io.cocode.app.ui.web

import io.cocode.app.ui.withRememberedPagePath

/**
 * Resolves the first page of an instance: [resumePath] when the instance has a usable remembered
 * page, else [startUrl]. The remembered page is already an absolute same-origin path and is
 * restored on the origin alone ([withRememberedPagePath]), so a base path such as `/t3` is not
 * repeated. Keeping [resumePath] unchanged means a gateway retry loads the same page again.
 */
internal fun startPageUrl(
    startUrl: String,
    baseUrl: String,
    resumePath: String?,
): String = resumePath?.let { withRememberedPagePath(baseUrl, it) } ?: startUrl
