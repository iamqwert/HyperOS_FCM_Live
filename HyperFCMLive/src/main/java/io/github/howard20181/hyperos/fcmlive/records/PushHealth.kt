package io.github.howard20181.hyperos.fcmlive.records

/**
 * The cross-check between the two answers to "which apps use FCM".
 *
 * They are not the same question, which is exactly why the comparison is worth
 * showing:
 *
 *  - **Declared** — what [io.github.howard20181.hyperos.fcmlive.FcmAppScan]
 *    reads out of the manifests: the app ships a push entry point
 *    (`MESSAGING_EVENT` service, `c2dm.intent.RECEIVE` receiver, instance-id
 *    receiver, or Google's own PushReceiver class).
 *  - **Registered** — what GMS lists under `Apps supporting client queue:`,
 *    which comes from its `fcm_package_info.ldb` cache and the `Lcnyi` record
 *    per app (`FCM_BEHAVIOR_EVIDENCE.md` §13). That is GMS's own view of who
 *    has a live push registration.
 *
 * So each disagreement means something specific:
 *
 * | difference | meaning |
 * |---|---|
 * | declared, not registered | the app carries the SDK but GMS has never logged it — freshly installed and not yet launched, a disabled component, or the `Lcnyi` cache is stale |
 * | registered, not declared | GMS still remembers a package whose manifest no longer answers (uninstalled residue), or one that reaches FCM through a shape the scan does not accept |
 *
 * The registered list is **not** a replacement for the scan: it only covers
 * apps that declare the client-queue capability, so an older FCM SDK that does
 * not would drop out of it. The scan stays the source of truth for membership;
 * this comparison is a diagnostic.
 *
 * GMS itself is absent from both sides by construction — `FcmAppScan` excludes
 * it deliberately and GMS does not list itself — so no special case is needed
 * here.
 */
object PushHealth {

    /**
     * @param declared apps the manifest scan accepted
     * @param registered apps GMS lists as supporting the client queue
     */
    data class Diff(
        /** In [declared] but not [registered], sorted — "carries the SDK, GMS has no record". */
        val declaredOnly: List<String>,
        /** In [registered] but not [declared], sorted — usually residue or a Google-shaped entry point. */
        val registeredOnly: List<String>,
        /** How many packages both agree on. */
        val agreed: Int
    ) {
        /** True when either side has something the other does not. */
        val hasDrift: Boolean get() = declaredOnly.isNotEmpty() || registeredOnly.isNotEmpty()
    }

    fun compare(declared: Set<String>, registered: Collection<String>): Diff {
        val declaredSet = declared
        val registeredSet = registered.toHashSet()
        return Diff(
            declaredOnly = (declaredSet - registeredSet).sorted(),
            registeredOnly = (registeredSet - declaredSet).sorted(),
            agreed = declaredSet.count { it in registeredSet }
        )
    }
}
