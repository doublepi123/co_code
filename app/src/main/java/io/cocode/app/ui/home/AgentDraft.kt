package io.cocode.app.ui.home

import io.cocode.app.runtime.ServerKind

/**
 * A picker's local draft (coding agents or servers) and the decisions that follow from it. Pure, so
 * the rules are unit tested without Compose.
 *
 * - Before the environment exists ([beforeInstall]) a toggle is saved right away, so Set up uses it.
 *   An empty draft is never saved (the controller ignores it); it only stays on screen and blocks
 *   Set up until something is picked again.
 * - Afterwards toggles are staged; Apply sends the whole draft. The baseline is what is actually
 *   installed (not the saved selection, which can be ahead of it after a failed change), so adding
 *   keeps every installed item checked, and only an item the user unchecks is removed.
 * - [locked] items are needed by something else (OpenCode while OpenChamber is installed or chosen).
 *   They show checked, can't be unchecked and are never a removal. They are sent to the controller
 *   only when the user asked for them too ([requested]), so a dependency doesn't turn into a choice
 *   of the user's; the one exception is a selection that would otherwise be empty.
 */
internal data class SelectionDraft<T>(
    /** What the checkboxes show. */
    val checked: Set<T>,
    /** What the draft is compared against: installed items, or the saved selection before setup. */
    val baseline: Set<T>,
    val beforeInstall: Boolean,
    /** Items that must stay checked. */
    val locked: Set<T> = emptySet(),
    /** The user's saved selection, as the controller reports it. */
    val requested: Set<T> = baseline,
) {
    val isEmpty: Boolean get() = checked.isEmpty()

    /** Installed items the user unchecked; Apply removes them (after a confirmation). */
    val removals: Set<T> get() = if (beforeInstall) emptySet() else baseline - checked - locked

    /** Checked items that are not installed yet; Apply installs them. */
    val additions: Set<T> get() = if (beforeInstall) emptySet() else checked - baseline

    /** Installed items that stay checked; Apply leaves them as they are. */
    val kept: Set<T> get() = if (beforeInstall) emptySet() else baseline intersect checked

    /** Apply would uninstall something, so it must be confirmed first. Pure additions never ask. */
    val needsRemovalConfirm: Boolean get() = canApply && removals.isNotEmpty()

    /** Set up (or retrying a first setup) needs at least one item. */
    val canSetUp: Boolean get() = !isEmpty

    /** Apply is offered once the draft differs from what is installed, and never for an empty draft. */
    val canApply: Boolean get() = !beforeInstall && !isEmpty && checked != baseline

    /** The flipped draft, and the selection to save right away (null: nothing to save). */
    fun toggle(id: T): Pair<SelectionDraft<T>, Set<T>?> {
        if (id in locked) return this to null
        val next = copy(checked = if (id in checked) checked - id else checked + id)
        val save = next.outgoing().takeIf { beforeInstall && it.isNotEmpty() }
        return next to save
    }

    /** The selection Apply sends, or null when Apply must not send anything. */
    fun applySelection(): Set<T>? = if (canApply) outgoing() else null

    /** [checked] without dependencies the user never asked for; never empty while [checked] isn't. */
    private fun outgoing(): Set<T> = (checked - (locked - requested)).ifEmpty { checked }

    companion object {
        /**
         * Starts a draft. With items installed they are the baseline; before the first setup (or when
         * nothing is recorded as installed) the saved selection is. [locked] items show checked.
         */
        fun <T> of(
            saved: Set<T>,
            installed: Set<T>,
            beforeInstall: Boolean,
            locked: Set<T> = emptySet(),
        ): SelectionDraft<T> {
            val fresh = beforeInstall || installed.isEmpty()
            val baseline = if (fresh) saved else installed
            // After setup a requirement that isn't installed yet is the controller's to add; showing it
            // as a pending change would offer an Apply that does nothing new.
            val lockedNow = if (fresh) locked else locked intersect installed
            return SelectionDraft(
                checked = baseline + lockedNow,
                baseline = if (fresh) baseline + lockedNow else baseline,
                beforeInstall = beforeInstall,
                locked = lockedNow,
                requested = saved,
            )
        }
    }
}

/** The coding-agent picker's draft, keyed by agent id. */
internal typealias AgentDraft = SelectionDraft<String>

/** The server picker's draft (T3 Code / OpenChamber). */
internal typealias ServerDraft = SelectionDraft<ServerKind>
