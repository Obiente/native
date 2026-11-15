package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareContactCardTest {
    private val book = "/remote.php/dav/addressbooks/users/alice/contacts/"
    private val href = "${book}jane.vcf"

    private val stored = listOf(
        "BEGIN:VCARD",
        "VERSION:3.0",
        "UID:jane",
        "FN:Jane Example",
        "N:Example;Jane;;;",
        "ORG:Example Co",
        "TITLE:Engineer",
        "EMAIL;TYPE=INTERNET,HOME;TYPE=pref:jane@home.example.test",
        "EMAIL;TYPE=WORK:jane@work.example.test",
        "TEL;TYPE=CELL:+1 555 0100",
        "TEL;TYPE=WORK,VOICE:+1 555 0101",
        "ADR;TYPE=HOME:;;1 Main St;Springfield;IL;62701;USA",
        "URL:https://jane.example.test",
        "CATEGORIES:Friends,Book club",
        "BDAY:1990-01-15",
        "PHOTO;ENCODING=b;TYPE=JPEG:AAAA",
        "X-CUSTOM:kept",
        "END:VCARD",
    ).joinToString("\r\n", postfix = "\r\n")

    @Test
    fun cardsReadEveryLabelledValueGroupAndBirthday() {
        val card = contact(stored).card()
        assertEquals("Jane Example", card.name)
        assertEquals("Engineer", card.jobTitle)
        assertEquals(
            listOf(ContactValue(ContactValueLabel.Home, "jane@home.example.test"), ContactValue(ContactValueLabel.Work, "jane@work.example.test")),
            card.emails,
        )
        assertEquals(listOf(ContactValueLabel.Mobile, ContactValueLabel.Work), card.phones.map { it.label })
        assertEquals(ContactAddress(ContactValueLabel.Home, "1 Main St", "Springfield", "IL", "62701", "USA"), card.addresses.single())
        assertEquals("https://jane.example.test", card.websites.single().value)
        assertEquals(listOf("Friends", "Book club"), card.groups)
        assertEquals("1990-01-15", card.birthday)
    }

    @Test
    fun anUnchangedCardRewritesNothing() {
        val contact = contact(stored)
        assertEquals(stored, updateGroupwareContactCardContent(contact, contact.card()))
    }

    @Test
    fun changesKeepUntouchedLinesAndUnknownProperties() {
        val contact = contact(stored)
        val card = contact.card()
        val changed = card.copy(
            emails = card.emails + ContactValue(ContactValueLabel.Other, "jane@new.example.test"),
            phones = card.phones.drop(1),
            groups = listOf("Friends", "Climbing"),
            birthday = "--03-04",
        )
        val content = updateGroupwareContactCardContent(contact, changed)
        assertTrue("EMAIL;TYPE=INTERNET,HOME;TYPE=pref:jane@home.example.test" in content, "An unchanged entry keeps its parameters.")
        assertTrue("EMAIL;TYPE=INTERNET:jane@new.example.test" in content)
        assertFalse("+1 555 0100" in content)
        assertTrue("TEL;TYPE=WORK,VOICE:+1 555 0101" in content)
        assertTrue("CATEGORIES:Friends,Climbing" in content)
        assertTrue("BDAY:--0304" in content)
        assertTrue("PHOTO;ENCODING=b;TYPE=JPEG:AAAA" in content)
        assertTrue("X-CUSTOM:kept" in content)
        assertTrue("N:Example;Jane;;;" in content, "A structured name stays while the name is unchanged.")
        assertEquals(changed.normalized(), parse(content).card().normalized())
    }

    @Test
    fun aNewContactIsWrittenAsVCardThreeWithTypes() {
        val card = ContactCard(
            name = "Sam; O'Neil",
            emails = listOf(ContactValue(ContactValueLabel.Work, "sam@example.test")),
            phones = listOf(ContactValue(ContactValueLabel.Mobile, "+1 555, 0102")),
            addresses = listOf(ContactAddress(ContactValueLabel.Work, street = "2 Side Rd", locality = "Shelbyville")),
            websites = listOf(ContactValue(ContactValueLabel.Other, "https://sam.example.test")),
            groups = listOf("Work, colleagues"),
            birthday = "1985-12-31",
        )
        val content = createGroupwareContactCardContent("sam", card)
        assertTrue("VERSION:3.0" in content)
        assertTrue("FN:Sam\\; O'Neil" in content)
        assertTrue("EMAIL;TYPE=WORK:sam@example.test" in content)
        assertTrue("TEL;TYPE=CELL:+1 555\\, 0102" in content)
        assertTrue("ADR;TYPE=WORK:;;2 Side Rd;Shelbyville;;;" in content)
        assertTrue("CATEGORIES:Work\\, colleagues" in content)
        assertTrue("BDAY:19851231" in content)
        assertEquals(card.normalized(), parse(content).card().normalized())
    }

    @Test
    fun vCardFourUsesLowercaseTypesWithoutDefaults() {
        val v4 = stored.replace("VERSION:3.0", "VERSION:4.0")
        val contact = contact(v4)
        val content = updateGroupwareContactCardContent(contact, contact.card().copy(
            phones = listOf(ContactValue(ContactValueLabel.Other, "+1 555 0199")),
        ))
        assertTrue("TEL:+1 555 0199" in content)
        val withMobile = updateGroupwareContactCardContent(contact, contact.card().copy(
            phones = listOf(ContactValue(ContactValueLabel.Mobile, "+1 555 0199")),
        ))
        assertTrue("TEL;TYPE=cell:+1 555 0199" in withMobile)
    }

    @Test
    fun invalidCardsAreRejectedBeforeAnyWrite() {
        val contact = contact(stored)
        assertEquals("Enter a name.", ContactCard(name = " ").validationMessage())
        assertNotNull(ContactCard("A", emails = listOf(ContactValue(ContactValueLabel.Home, "no-at-sign"))).validationMessage())
        assertNotNull(ContactCard("A", websites = listOf(ContactValue(ContactValueLabel.Home, "javascript:alert(1)"))).validationMessage())
        assertNotNull(ContactCard("A", birthday = "1990-02-30").validationMessage())
        assertNotNull(ContactCard("A", emails = List(21) { ContactValue(ContactValueLabel.Home, "a$it@example.test") }).validationMessage())
        assertNull(ContactCard("A", emails = listOf(ContactValue(ContactValueLabel.Home, " "))).validationMessage(), "Empty rows are dropped.")
        assertFailsWith<IllegalArgumentException> { updateGroupwareContactCardContent(contact, ContactCard(name = "")) }
    }

    @Test
    fun birthdaysConvertBetweenStoredAndEditorForms() {
        assertEquals("1990-01-15", "19900115".contactBirthdayIso())
        assertEquals("1990-01-15", "1990-01-15T00:00:00Z".contactBirthdayIso())
        assertEquals("--01-15", "--0115".contactBirthdayIso())
        assertNull("1990-13-01".contactBirthdayIso())
        assertEquals("19900115", "1990-01-15".contactBirthdayCompact())
        assertEquals("--0229", "--02-29".contactBirthdayCompact())
        assertNull("2023-02-29".contactBirthdayCompact())
        assertEquals("15 January 1990", "1990-01-15".displayContactBirthday())
        assertEquals("15 January", "--01-15".displayContactBirthday())
    }

    @Test
    fun cardPostconditionsCompareEveryTypedField() {
        val contact = contact(stored)
        val draft = contact.card().copy(groups = listOf("Friends")).toContactDraft()
        val content = updateGroupwareContactCardContent(contact, requireNotNull(draft.card))
        val postcondition = assertNotNull(contactUpdatePostcondition(contact, draft, content))
        assertNotNull(postcondition.expectedCard)
        assertTrue(postcondition.isSatisfiedBy(NextcloudApiResponse(200, content.encodeToByteArray(), "text/vcard", "\"2\"")))
        assertFalse(postcondition.isSatisfiedBy(NextcloudApiResponse(200, stored.encodeToByteArray(), "text/vcard", "\"1\"")))

        val created = createGroupwareContactCardContent("new", ContactCard("New Person"))
        val createCondition = assertNotNull(contactCreatePostcondition("${book}new.vcf", book, "new", ContactCard("New Person").toContactDraft(), created))
        assertTrue(createCondition.isSatisfiedBy(NextcloudApiResponse(201, created.encodeToByteArray(), "text/vcard", "\"1\"")))
    }

    @Test
    fun legacyRecoveryRecordsStillVerifyWithoutACard() {
        val legacy = ContactMutationPostcondition.Upsert(
            href = href, addressBookHref = book, expectedUid = "jane", previousEtag = "\"1\"",
            draft = ContactDraft("Jane Example", "jane@home.example.test", "+1 555 0100", "Example Co", "1 Main St, Springfield, IL, 62701, USA", ""),
        )
        assertNull(legacy.expectedCard)
        assertTrue(legacy.isSatisfiedBy(NextcloudApiResponse(200, stored.encodeToByteArray(), "text/vcard", "\"1\"")))
    }

    @Test
    fun groupSuggestionsAreDistinctAndSorted() {
        val other = contact(stored.replace("CATEGORIES:Friends,Book club", "CATEGORIES:friends,Ascent"))
        assertEquals(listOf("Ascent", "Book club", "Friends"), contactGroupNames(listOf(contact(stored), other)))
    }

    private fun contact(content: String): GroupwareContact = parse(content)

    private fun parse(content: String): GroupwareContact = assertNotNull(parseGroupwareContact(book, href, "\"1\"", content))
}
