package dev.obiente.nextcloudnative.app

enum class PeopleNameFilter {
    All,
    Named,
    Unnamed,
}

data class PeopleGalleryPresentation(
    val people: List<NextcloudPerson>,
    val totalCount: Int,
    val namedCount: Int,
    val unnamedCount: Int,
)

/**
 * Builds the native people gallery without exposing cluster IDs as user-facing search material.
 * Named people always lead the unfiltered result, while explicit filters make the recognition
 * inbox practical when a server contains hundreds of unnamed clusters.
 */
fun buildPeopleGalleryPresentation(
    people: List<NextcloudPerson>,
    backend: NextcloudPeopleBackend,
    query: String,
    nameFilter: PeopleNameFilter,
): PeopleGalleryPresentation {
    val backendPeople = people.filter { person ->
        NextcloudPeopleBackend.fromApiValue(person.backend) == backend
    }
    val namedCount = backendPeople.count(NextcloudPerson::hasAssignedPersonName)
    val unnamedCount = backendPeople.size - namedCount
    val needle = query.trim()
    val visible = backendPeople
        .asSequence()
        .filter { person ->
            when (nameFilter) {
                PeopleNameFilter.All -> true
                PeopleNameFilter.Named -> person.hasAssignedPersonName()
                PeopleNameFilter.Unnamed -> !person.hasAssignedPersonName()
            }
        }
        .filter { person ->
            needle.isEmpty() || person.name.contains(needle, ignoreCase = true)
        }
        .toList()
        .let(::sortNextcloudPeopleForDisplay)
    return PeopleGalleryPresentation(
        people = visible,
        totalCount = backendPeople.size,
        namedCount = namedCount,
        unnamedCount = unnamedCount,
    )
}
