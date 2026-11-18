package io.joryio.sdk.core

/**
 * resetTo(anonymousId): the rule a caller-supplied anonymous id must pass
 * (BACKLOG D366). The SAME rule on all five SDKs, byte-identical - the
 * backend parity suite greps the pattern.
 *
 *  - 8 to 255 characters (the backend stores at most 255), letters, digits
 *    and `.` `_` `:` `-` only;
 *  - not 24 lowercase hex characters: the shape of a Joryio internal profile
 *    id, which several server paths read as the profile itself;
 *  - not a user id the SDK holds (signed in, or kept for withdrawals).
 */
internal object ResetTo {
    val ANONYMOUS_ID_PATTERN = Regex("^[A-Za-z0-9._:-]{8,255}$")
    val INTERNAL_ID_PATTERN = Regex("^[0-9a-f]{24}$")

    /**
     * How many anonymous ids tied to a signed-in user (held when a reset()
     * or resetTo() signed them out) this device remembers, newest kept. The
     * same cap on all five SDKs; wipeData() forgets them with all other data.
     */
    const val SIGNED_IN_ANONYMOUS_IDS_MAX = 20

    /** Append [id] (moved to the end if present), keeping the newest [SIGNED_IN_ANONYMOUS_IDS_MAX]. */
    fun remember(list: List<String>, id: String): List<String> =
        (list.filter { it != id } + id).takeLast(SIGNED_IN_ANONYMOUS_IDS_MAX)

    /** Null when [anonymousId] may be adopted, otherwise why not. */
    fun refusal(
        anonymousId: String?,
        knownUserIds: List<String?>,
        signedInAnonymousId: String? = null,
        rememberedSignedInAnonymousIds: List<String> = emptyList(),
    ): String? {
        if (anonymousId == null || !ANONYMOUS_ID_PATTERN.matches(anonymousId)) {
            return "resetTo: the anonymous id must be 8 to 255 letters, digits, \".\", \"_\", \":\" or \"-\""
        }
        if (INTERNAL_ID_PATTERN.matches(anonymousId)) {
            return "resetTo: the anonymous id has the shape of a Joryio profile id"
        }
        if (knownUserIds.any { it != null && it == anonymousId }) {
            return "resetTo: the anonymous id is a user id"
        }
        // The anonymous id held while a user is signed in is tied to their
        // profile: the next person on a shared device would inherit it.
        // ...and so is one a signed-in user held before an earlier reset.
        if ((signedInAnonymousId != null && signedInAnonymousId == anonymousId) || anonymousId in rememberedSignedInAnonymousIds) {
            return "resetTo: the anonymous id belongs to the signed-in user"
        }
        return null
    }
}
