package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudSession

/** Verified identity of the account lease already held by synchronous SAF recovery. */
internal data class AndroidProviderRecoveryIdentity(
    val session: NextcloudSession,
    val incarnation: NextcloudDocumentIncarnation,
    val legacyAliases: Set<String>,
) {
    fun requireReference(documentId: String): NextcloudDocumentReference =
        NextcloudDocumentIds.requireForSession(documentId, session, incarnation, legacyAliases)
}

internal fun androidProviderRecoveryIdentity(context: Context, session: NextcloudSession): AndroidProviderRecoveryIdentity {
    return androidProviderRecoveryIdentity(session, AndroidDocumentProviderIncarnationStore(context), AndroidDocumentLegacyAliases(context))
}

internal fun androidProviderRecoveryIdentity(session: NextcloudSession, incarnations: AndroidDocumentProviderIncarnationStore, aliases: AndroidDocumentLegacyAliases): AndroidProviderRecoveryIdentity {
    val identity = session.documentProviderIncarnationAccountIdentity()
    val retired = incarnations.retiredIncarnation(identity)
    val incarnation = retired ?: incarnations.activeIncarnation(identity)
    return AndroidProviderRecoveryIdentity(session, incarnation,
        aliases.readVerified(identity, incarnation))
}

internal fun androidRootBoundProviderRecoveryIdentity(root: String, identity: AndroidProviderRecoveryIdentity?): AndroidProviderRecoveryIdentity? {
    if (identity == null) return null
    val reference = NextcloudDocumentIds.parse(root)
    if (reference.incarnation != identity.incarnation) return null
    return identity.takeIf {
        reference.accountKey in setOf(NextcloudDocumentIds.documentAccountKey(identity.session),
            NextcloudDocumentIds.accountKey(identity.session)) + identity.legacyAliases
    }
}
