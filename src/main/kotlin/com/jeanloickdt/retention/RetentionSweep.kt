/*
 * InstantIoT Server — self-hosted IoT relay for makers.
 * Copyright (C) 2026 Djoufack Tsobeng Jean Loick (InstantIoT)
 * Author: Djoufack Tsobeng Jean Loick (@jeanloick_dt)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.jeanloickdt.retention

/**
 * One retention pass over one tier: a cutoff for everybody, plus the accounts
 * that keep their rows longer or shorter.
 *
 * ## Why it is shaped this way
 *
 * The obvious implementation of per-plan retention is one `DELETE` per
 * account. That scales with the number of **customers**, hourly, forever.
 *
 * But the number of distinct retention durations is the number of **plans** —
 * three, maybe five. And paying accounts are the minority, so the list is
 * inverted: one pass covers everyone at the fallback duration *except* the
 * exceptions, then one pass per exception group. Three or four statements per
 * tier, whatever the number of registered accounts. An `IN` clause holding ten
 * thousand identifiers never exists.
 *
 * ## `null` means keep forever
 *
 * A cutoff of `null` skips the pass for that group — it is the `-1` convention
 * of the entitlements file, carried down to SQL. It must be a distinct case:
 * a very old cutoff would still be a `DELETE`, and "keep everything" must not
 * depend on how far back a timestamp happens to reach.
 */
data class RetentionSweep(
    /** Applies to every account not listed in [overrides]. `null` = keep all. */
    val defaultCutoffMs: Long?,
    /** Accounts whose retention differs from the default. */
    val overrides: List<Override> = emptyList()
) {
    data class Override(
        /** `null` = these accounts keep everything. */
        val cutoffMs: Long?,
        val ownerIds: List<String>
    )

    /** Every account that is *not* on the default duration. */
    val exceptions: List<String> get() = overrides.flatMap { it.ownerIds }

    /** True when this pass would delete nothing anywhere — skip the transaction. */
    val isNoop: Boolean
        get() = defaultCutoffMs == null && overrides.all { it.cutoffMs == null }

    companion object {
        /** The pre-plan behaviour: one cutoff, everybody. */
        fun uniform(cutoffMs: Long?) = RetentionSweep(cutoffMs)
    }
}
