package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ContactDraft(
    val name: String,
    val email: String,
    val phone: String,
    val organization: String,
    val address: String,
    val notes: String,
    /** The full card the editor saved; null in records written before typed fields existed. */
    val card: ContactCard? = null,
) {
    fun normalizedForDav(): ContactDraft = copy(
        name = name.normalizeGroupwareTextLineEndings(),
        email = email.trim(),
        phone = phone.trim().normalizeGroupwareTextLineEndings(),
        organization = organization.trim().normalizeGroupwareTextLineEndings(),
        address = address.trim().normalizeGroupwareTextLineEndings(),
        notes = notes.trim().normalizeGroupwareTextLineEndings(),
    )
}

@Serializable
internal sealed interface ContactMutationPostcondition {
    val href: String
    fun isSatisfiedBy(response: NextcloudApiResponse): Boolean

    @Serializable
    data class Upsert(
        override val href: String,
        val addressBookHref: String,
        val expectedUid: String,
        val previousEtag: String?,
        val draft: ContactDraft,
        val expectedPrimaryEmail: String = draft.email.trim(),
        val expectedPrimaryPhone: String = draft.phone.trim().normalizeGroupwareTextLineEndings(),
        /** Every typed field as it reads back from the content that was sent. */
        val expectedCard: ContactCard? = null,
    ) : ContactMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean {
            if (response.status !in 200..299) return false
            val expected = draft.normalizedForDav()
            val contact = parseGroupwareContact(
                addressBookHref = addressBookHref,
                href = href,
                etag = response.etag,
                content = response.body.decodeToString(),
            ) ?: return false
            if (expectedCard != null) {
                return contact.href == href && contact.uid == expectedUid && contact.card().normalized() == expectedCard.normalized()
            }
            return contact.href == href &&
                contact.uid == expectedUid &&
                contact.displayName == expected.name &&
                contact.emails.firstOrNull().orEmpty() == expectedPrimaryEmail &&
                contact.phones.firstOrNull().orEmpty() == expectedPrimaryPhone &&
                contact.organization.orEmpty() == expected.organization &&
                contact.address.orEmpty() == expected.address &&
                contact.notes.orEmpty() == expected.notes
        }
    }

    @Serializable
    data class Delete(override val href: String) : ContactMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean =
            groupwareDeleteResponseProvesAbsence(response.status)
    }
}

@Serializable
internal data class ContactMutationRecoveryState(
    val accountScope: String,
    val postcondition: ContactMutationPostcondition,
) {
    init {
        require(accountScope.isCanonicalGroupwareMutationAccountScope())
    }
}

private val contactMutationRecoveryJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun ContactMutationRecoveryState.encodeForSavedState(): String =
    contactMutationRecoveryJson.encodeToString(this)

internal fun contactUpdatePostcondition(
    contact: GroupwareContact,
    draft: ContactDraft,
    updatedContent: String,
): ContactMutationPostcondition.Upsert? {
    val expected = parseGroupwareContact(
        addressBookHref = contact.addressBookHref,
        href = contact.href,
        etag = contact.etag,
        content = updatedContent,
    ) ?: return null
    return ContactMutationPostcondition.Upsert(
        href = contact.href,
        addressBookHref = contact.addressBookHref,
        expectedUid = contact.uid,
        previousEtag = contact.etag,
        draft = draft,
        expectedPrimaryEmail = expected.emails.firstOrNull().orEmpty(),
        expectedPrimaryPhone = expected.phones.firstOrNull().orEmpty(),
        expectedCard = expected.card().takeIf { draft.card != null },
    )
}

/** What a newly created contact must read back as, from the content that is sent. */
internal fun contactCreatePostcondition(
    href: String,
    addressBookHref: String,
    uid: String,
    draft: ContactDraft,
    content: String,
): ContactMutationPostcondition.Upsert? {
    val expected = parseGroupwareContact(addressBookHref, href, null, content) ?: return null
    return ContactMutationPostcondition.Upsert(
        href = href,
        addressBookHref = addressBookHref,
        expectedUid = uid,
        previousEtag = null,
        draft = draft,
        expectedPrimaryEmail = expected.emails.firstOrNull().orEmpty(),
        expectedPrimaryPhone = expected.phones.firstOrNull().orEmpty(),
        expectedCard = expected.card().takeIf { draft.card != null },
    )
}

/** The editor draft for [card], with the legacy single-value fields filled from its first entries. */
internal fun ContactCard.toContactDraft(): ContactDraft = ContactDraft(
    name = name,
    email = emails.firstOrNull()?.value.orEmpty(),
    phone = phones.firstOrNull()?.value.orEmpty(),
    organization = organization,
    address = addresses.firstOrNull()?.singleLine.orEmpty(),
    notes = notes,
    card = this,
)

internal fun decodeContactMutationRecoveryState(
    encoded: String,
    expectedAccountScope: String,
): ContactMutationPostcondition? = runCatching {
    contactMutationRecoveryJson.decodeFromString<ContactMutationRecoveryState>(encoded)
}.getOrNull()?.takeIf { recovery -> recovery.accountScope == expectedAccountScope }?.postcondition

/** A recovery decision uses one record snapshot, never a later live record with an earlier decode. */
internal data class ContactRecoverySnapshot(
    val encoded: String?,
    val postcondition: ContactMutationPostcondition?,
) {
    fun readyToVerify(loaded: Boolean, requestRunning: Boolean): Boolean = loaded && !requestRunning
    val hasRecord: Boolean get() = encoded != null
    val unreadable: Boolean get() = hasRecord && postcondition == null
}

internal fun contactRecoverySnapshot(encoded: String?, accountScope: String): ContactRecoverySnapshot =
    ContactRecoverySnapshot(encoded, encoded?.let { decodeContactMutationRecoveryState(it, accountScope) })

internal fun contactDraftIsDirty(
    initial: ContactDraft,
    current: ContactDraft,
    initialAddressBookHref: String?,
    currentAddressBookHref: String?,
): Boolean = initial != current || initialAddressBookHref != currentAddressBookHref

internal fun contactDraftHasDavChanges(
    initial: ContactDraft,
    current: ContactDraft,
    initialAddressBookHref: String?,
    currentAddressBookHref: String?,
): Boolean = initial.normalizedForDav() != current.normalizedForDav() ||
    initialAddressBookHref != currentAddressBookHref


internal const val CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE =
    "The contact change could not be verified. " +
        "Refresh to verify it before trying another change."

/** Successful DELETE still requires authoritative absence before closing the detail owner. */
internal suspend fun verifyContactDeletion(
    href: String,
    execute: suspend (GroupwareDavRequest) -> NextcloudApiResponse,
): Boolean = runCatchingPreservingCancellation {
    ContactMutationPostcondition.Delete(href).isSatisfiedBy(execute(groupwareDavDetailRequest(href)))
}.getOrDefault(false)
