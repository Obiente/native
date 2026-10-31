package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

/** How a phone, email, address or website is labelled, from its vCard TYPE parameter. */
@Serializable
internal enum class ContactValueLabel(val title: String, val vcardType: String?) {
    Home("Home", "HOME"),
    Work("Work", "WORK"),
    Mobile("Mobile", "CELL"),
    Other("Other", null),
    ;

    companion object {
        fun fromTypes(types: List<String>, allowMobile: Boolean): ContactValueLabel = when {
            allowMobile && "CELL" in types -> Mobile
            "WORK" in types -> Work
            "HOME" in types -> Home
            else -> Other
        }
    }
}

@Serializable
internal data class ContactValue(val label: ContactValueLabel, val value: String)

@Serializable
internal data class ContactAddress(
    val label: ContactValueLabel,
    val street: String = "",
    val locality: String = "",
    val region: String = "",
    val postalCode: String = "",
    val country: String = "",
) {
    val isBlank: Boolean get() = listOf(street, locality, region, postalCode, country).all(String::isBlank)
    val singleLine: String get() = listOf(street, postalCode, locality, region, country).filter(String::isNotBlank).joinToString(", ")
}

/**
 * The parts of a contact the editor works with. Everything else in the vCard, such as photos,
 * custom fields and properties written by other apps, is kept exactly as stored.
 */
@Serializable
internal data class ContactCard(
    val name: String,
    val organization: String = "",
    val jobTitle: String = "",
    val notes: String = "",
    /** `YYYY-MM-DD`, `--MM-DD` without a year, or empty. */
    val birthday: String = "",
    val emails: List<ContactValue> = emptyList(),
    val phones: List<ContactValue> = emptyList(),
    val addresses: List<ContactAddress> = emptyList(),
    val websites: List<ContactValue> = emptyList(),
    val groups: List<String> = emptyList(),
) {
    /** The card as it would read back from the server: trimmed, without empty rows. */
    fun normalized(): ContactCard = copy(
        name = name.trim().normalizeGroupwareTextLineEndings(),
        organization = organization.trim().normalizeGroupwareTextLineEndings(),
        jobTitle = jobTitle.trim().normalizeGroupwareTextLineEndings(),
        notes = notes.trim().normalizeGroupwareTextLineEndings(),
        birthday = birthday.trim(),
        emails = emails.map { it.copy(value = it.value.trim()) }.filter { it.value.isNotEmpty() },
        phones = phones.map { it.copy(value = it.value.trim()) }.filter { it.value.isNotEmpty() },
        addresses = addresses.map {
            it.copy(street = it.street.trim(), locality = it.locality.trim(), region = it.region.trim(),
                postalCode = it.postalCode.trim(), country = it.country.trim())
        }.filterNot(ContactAddress::isBlank),
        websites = websites.map { it.copy(value = it.value.trim()) }.filter { it.value.isNotEmpty() },
        groups = groups.map(String::trim).filter(String::isNotEmpty).distinct(),
    )

    /** Why this card cannot be saved, or null when it can. */
    fun validationMessage(): String? {
        val card = normalized()
        return when {
            card.name.isBlank() -> "Enter a name."
            listOf(card.emails, card.phones, card.websites).any { it.size > MAX_CONTACT_VALUES } ||
                card.addresses.size > MAX_CONTACT_VALUES || card.groups.size > MAX_CONTACT_VALUES -> "This contact has too many entries."
            card.emails.any { !it.value.isContactEmail() } -> "Check the email addresses."
            card.websites.any { !it.value.isContactWebsite() } -> "Websites must start with https:// or http://."
            card.birthday.isNotEmpty() && card.birthday.contactBirthdayCompact() == null -> "Enter the birthday as YYYY-MM-DD."
            listOf(card.name, card.organization, card.jobTitle, card.notes).any { it.length > MAX_CONTACT_TEXT } -> "A field is too long."
            else -> null
        }
    }
}

internal fun GroupwareContact.card(): ContactCard {
    val properties = rawVCard.unfoldCalendarLines().mapNotNull(::parseICalendarContentLine)
    fun named(name: String) = properties.filter { it.name.substringAfterLast('.').equals(name, ignoreCase = true) }
    fun first(name: String) = named(name).firstOrNull()
    return ContactCard(
        name = displayName,
        organization = organization.orEmpty(),
        jobTitle = first("TITLE")?.value?.decodeCalendarText()?.trim().orEmpty(),
        notes = notes.orEmpty(),
        birthday = first("BDAY")?.value?.trim()?.contactBirthdayIso().orEmpty(),
        emails = named("EMAIL").mapNotNull(::contactEmail),
        phones = named("TEL").mapNotNull(::contactPhone),
        addresses = named("ADR").mapNotNull(::contactAddress),
        websites = named("URL").mapNotNull(::contactWebsite),
        groups = named("CATEGORIES").flatMap(::contactGroups).distinct(),
    )
}

private fun contactEmail(line: ICalendarContentLine): ContactValue? =
    line.value.trim().takeIf(String::isNotEmpty)?.let { ContactValue(ContactValueLabel.fromTypes(line.contactTypes(), false), it) }

private fun contactPhone(line: ICalendarContentLine): ContactValue? =
    line.value.trim().removePrefix("tel:").decodeCalendarText().takeIf(String::isNotEmpty)
        ?.let { ContactValue(ContactValueLabel.fromTypes(line.contactTypes(), true), it) }

private fun contactWebsite(line: ICalendarContentLine): ContactValue? =
    line.value.trim().takeIf(String::isNotEmpty)?.let { ContactValue(ContactValueLabel.fromTypes(line.contactTypes(), false), it) }

private fun contactAddress(line: ICalendarContentLine): ContactAddress? {
    val parts = line.value.splitContactComponents(';').map { it.decodeCalendarText().trim() }
    return ContactAddress(
        label = ContactValueLabel.fromTypes(line.contactTypes(), false),
        street = listOfNotNull(parts.getOrNull(0), parts.getOrNull(1), parts.getOrNull(2)).filter(String::isNotEmpty).joinToString(", "),
        locality = parts.getOrNull(3).orEmpty(),
        region = parts.getOrNull(4).orEmpty(),
        postalCode = parts.getOrNull(5).orEmpty(),
        country = parts.getOrNull(6).orEmpty(),
    ).takeUnless(ContactAddress::isBlank)
}

private fun contactGroups(line: ICalendarContentLine): List<String> =
    line.value.splitContactComponents(',').map { it.decodeCalendarText().trim() }.filter(String::isNotEmpty)

internal fun createGroupwareContactCardContent(uid: String, card: ContactCard): String {
    require(uid.isNotBlank() && uid.none(Char::isISOControl)) { "The contact id is invalid." }
    return applyContactCard(
        listOf("BEGIN:VCARD", "VERSION:3.0", "PRODID:-//Obiente//Nextcloud Native//EN", "UID:${uid.escapeCalendarText()}", "END:VCARD"),
        original = null,
        card = card,
    )
}

/** Writes [card] into the contact's vCard, keeping every property the card does not describe. */
internal fun updateGroupwareContactCardContent(contact: GroupwareContact, card: ContactCard): String =
    applyContactCard(contact.rawVCard.unfoldCalendarLines(), contact.card(), card)

private fun applyContactCard(source: List<String>, original: ContactCard?, card: ContactCard): String {
    require(card.validationMessage() == null) { "The contact is invalid." }
    val wanted = card.normalized()
    val before = original?.normalized()
    val lines = source.toMutableList()
    val version4 = lines.any { it.equals("VERSION:4.0", ignoreCase = true) }

    fun indexes(name: String) = lines.indices.filter { index ->
        parseICalendarContentLine(lines[index])?.name?.substringAfterLast('.')?.equals(name, ignoreCase = true) == true
    }
    fun replaceAll(name: String, replacement: List<String>) {
        val existing = indexes(name)
        val end = lines.indexOfLast { it.equals("END:VCARD", ignoreCase = true) }.takeIf { it >= 0 } ?: lines.size
        val insertAt = existing.firstOrNull() ?: end
        existing.reversed().forEach(lines::removeAt)
        lines.addAll(insertAt.coerceAtMost(lines.size), replacement)
    }
    fun <T> sync(name: String, old: List<T>?, new: List<T>, parse: (ICalendarContentLine) -> T?, format: (T) -> String) {
        if (old == new) return
        // An unchanged entry keeps its own original line, with parameters such as PREF.
        val originals = indexes(name).mapNotNull { index ->
            parseICalendarContentLine(lines[index])?.let(parse)?.let { entry -> entry to lines[index] }
        }.toMutableList()
        replaceAll(name, new.map { entry ->
            originals.firstOrNull { it.first == entry }?.also(originals::remove)?.second ?: format(entry)
        })
    }
    fun single(name: String, old: String?, new: String, format: (String) -> String) {
        if (old == new) return
        replaceAll(name, listOfNotNull(new.takeIf(String::isNotEmpty)?.let(format)))
    }

    single("FN", before?.name, wanted.name) { "FN:${it.escapeCalendarText()}" }
    if (before?.name != wanted.name) replaceAll("N", listOf("N:${wanted.name.escapeCalendarText()};;;;"))
    single("ORG", before?.organization, wanted.organization) { "ORG:${it.escapeCalendarText()}" }
    single("TITLE", before?.jobTitle, wanted.jobTitle) { "TITLE:${it.escapeCalendarText()}" }
    single("NOTE", before?.notes, wanted.notes) { "NOTE:${it.escapeCalendarText()}" }
    single("BDAY", before?.birthday, wanted.birthday) { iso ->
        "BDAY:" + requireNotNull(iso.contactBirthdayCompact()) { "The birthday is invalid." }
    }
    sync("EMAIL", before?.emails, wanted.emails, ::contactEmail) {
        "EMAIL${it.label.typeParameter(version4, "INTERNET")}:${it.value}"
    }
    sync("TEL", before?.phones, wanted.phones, ::contactPhone) {
        "TEL${it.label.typeParameter(version4, "VOICE")}:${it.value.escapeCalendarText()}"
    }
    sync("ADR", before?.addresses, wanted.addresses, ::contactAddress) { address ->
        val parts = listOf("", "", address.street, address.locality, address.region, address.postalCode, address.country)
        "ADR${address.label.typeParameter(version4, null)}:" + parts.joinToString(";") { it.escapeCalendarText() }
    }
    sync("URL", before?.websites, wanted.websites, ::contactWebsite) { "URL${it.label.typeParameter(version4, null)}:${it.value}" }
    if (before?.groups != wanted.groups) {
        replaceAll("CATEGORIES", listOfNotNull(wanted.groups.takeIf(List<String>::isNotEmpty)?.let { groups ->
            "CATEGORIES:" + groups.joinToString(",") { it.escapeCalendarText() }
        }))
    }
    return lines.joinICalendarLines()
}

private fun ContactValueLabel.typeParameter(version4: Boolean, defaultType: String?): String {
    val type = vcardType ?: if (version4) null else defaultType
    return type?.let { ";TYPE=${if (version4) it.lowercase() else it}" }.orEmpty()
}

private fun ICalendarContentLine.contactTypes(): List<String> = parameters
    .filter { (key) -> key.equals("TYPE", ignoreCase = true) }
    .flatMap { (_, value) -> value.split(',') }
    .map { it.trim().uppercase() }

/** Splits a structured value at unescaped [delimiter]s. */
private fun String.splitContactComponents(delimiter: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (character in this) {
        when {
            escaped -> { current.append('\\').append(character); escaped = false }
            character == '\\' -> escaped = true
            character == delimiter -> { parts += current.toString(); current.clear() }
            else -> current.append(character)
        }
    }
    if (escaped) current.append('\\')
    parts += current.toString()
    return parts
}

/** A stored BDAY as `YYYY-MM-DD` or `--MM-DD`, or null when it is not a date this editor understands. */
internal fun String.contactBirthdayIso(): String? {
    val value = substringBefore('T').trim()
    val match = Regex("^(\\d{4}|--)-?(\\d{2})-?(\\d{2})$").matchEntire(value) ?: return null
    val (year, month, day) = match.destructured
    val iso = "$year-$month-$day".replace("---", "--")
    return iso.takeIf { it.contactBirthdayCompact() != null }
}

/** The BDAY value to store for an editor birthday, or null when it is invalid. */
internal fun String.contactBirthdayCompact(): String? {
    val match = Regex("^(\\d{4}|-)-(\\d{2})-(\\d{2})$").matchEntire(trim()) ?: return null
    val (year, month, day) = match.destructured
    val monthNumber = month.toInt()
    val dayNumber = day.toInt()
    val yearNumber = year.toIntOrNull()
    if (monthNumber !in 1..12 || dayNumber !in 1..groupwareCalendarDaysInMonth(yearNumber ?: 2000, monthNumber)) return null
    if (yearNumber != null && yearNumber !in 1..9999) return null
    // A birthday without a year is written --MMDD, as vCard 4 defines and Nextcloud reads.
    return if (yearNumber != null) "$year$month$day" else "--$month$day"
}

private fun String.isContactEmail(): Boolean =
    length <= 254 && none { it.isISOControl() || it == ';' || it == ',' || it.isWhitespace() } && '@' in this

private fun String.isContactWebsite(): Boolean =
    length <= 2_000 && (startsWith("https://", true) || startsWith("http://", true)) && none { it.isISOControl() || it.isWhitespace() }

internal const val MAX_CONTACT_VALUES = 20
private const val MAX_CONTACT_TEXT = 10_000
