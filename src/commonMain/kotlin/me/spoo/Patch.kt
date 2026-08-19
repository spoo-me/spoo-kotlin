package me.spoo

/**
 * The three things a PATCH field can say: leave the stored value alone,
 * clear it, or replace it.
 *
 * The update endpoint gives `null` a per-field meaning (clear the password,
 * remove the expiry, move back to the default domain), so "send null" and
 * "send nothing" must stay distinguishable; a nullable type cannot carry
 * that distinction. [Keep] fields are omitted from the wire entirely.
 * [UpdateLinkRequest] uses this behind paired ergonomics
 * (`password = ...` / `removePassword()`), so most callers never name it.
 */
public sealed interface Patch<out T> {
    /** Do not send the field: the stored value stays as it is. */
    public data object Keep : Patch<Nothing>

    /** Send an explicit `null`: clear or reset the field. */
    public data object Null : Patch<Nothing>

    /** Send a new value. */
    public data class Set<T>(public val value: T) : Patch<T>
}
