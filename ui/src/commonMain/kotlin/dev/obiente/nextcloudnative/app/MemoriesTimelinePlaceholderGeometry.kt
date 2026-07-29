package dev.obiente.nextcloudnative.app

private const val MEMORIES_TIMELINE_SECONDS_PER_DAY = 86_400L

data class MemoriesTimelinePlaceholderDay(
    val dayId: Long,
    val advertisedItemCount: Int,
    val month: PhotoTimelineMonth,
    val firstGridItemIndex: Int,
) {
    init {
        require(dayId in 1..Long.MAX_VALUE / MEMORIES_TIMELINE_SECONDS_PER_DAY) {
            "The Memories placeholder day ID is invalid."
        }
        require(advertisedItemCount > 0) {
            "A Memories placeholder day must advertise at least one item."
        }
        require(firstGridItemIndex >= 0) {
            "The Memories placeholder grid index is invalid."
        }
        require(
            firstGridItemIndex <= Int.MAX_VALUE - advertisedItemCount + 1,
        ) {
            "The Memories placeholder day exceeds the grid index range."
        }
    }

    val lastGridItemIndex: Int
        get() = firstGridItemIndex + advertisedItemCount - 1
}

data class MemoriesTimelinePlaceholderMonth(
    val month: PhotoTimelineMonth,
    val monthHeaderGridItemIndex: Int,
    val firstDayIndex: Int,
    val dayCount: Int,
    val firstAdvertisedItemOffset: Long,
    val advertisedItemCount: Long,
) {
    init {
        require(monthHeaderGridItemIndex >= 0)
        require(firstDayIndex >= 0)
        require(dayCount > 0)
        require(firstAdvertisedItemOffset >= 0L)
        require(advertisedItemCount > 0L)
    }
}

data class MemoriesTimelinePlaceholderGeometry(
    val days: List<MemoriesTimelinePlaceholderDay>,
    val months: List<MemoriesTimelinePlaceholderMonth>,
    val totalAdvertisedItemCount: Long,
    val totalGridItemCount: Int,
) {
    init {
        require(totalAdvertisedItemCount >= 0L)
        require(totalGridItemCount >= 0)
        require(days.size <= MAX_MEMORIES_DAYS)
        require(days.map(MemoriesTimelinePlaceholderDay::dayId).distinct().size == days.size)
        require(days.zipWithNext().all { (newer, older) -> newer.dayId > older.dayId })
        require(months.map(MemoriesTimelinePlaceholderMonth::month).distinct().size == months.size)
        require(months.zipWithNext().all { (newer, older) -> newer.month > older.month })
        require(months.sumOf(MemoriesTimelinePlaceholderMonth::dayCount) == days.size)
        require(
            days.sumOf { day -> day.advertisedItemCount.toLong() } ==
                totalAdvertisedItemCount,
        )
        require(
            months.fold(0L) { total, month -> total + month.advertisedItemCount } ==
                totalAdvertisedItemCount,
        )
        require(
            totalGridItemCount.toLong() ==
                totalAdvertisedItemCount + months.size.toLong(),
        )
    }

    fun monthIndexAtFraction(fraction: Float): Int? {
        if (
            months.isEmpty() ||
            totalAdvertisedItemCount == 0L ||
            !fraction.isFinite()
        ) {
            return null
        }
        val clamped = fraction.coerceIn(0f, 1f)
        return months.indexOfLast { month ->
            val monthStartFraction = (
                month.firstAdvertisedItemOffset.toDouble() /
                    totalAdvertisedItemCount.toDouble()
                ).toFloat()
            clamped >= monthStartFraction
        }.takeIf { index -> index >= 0 }
    }

    fun monthAtFraction(fraction: Float): MemoriesTimelinePlaceholderMonth? =
        monthIndexAtFraction(fraction)?.let(months::get)

    fun dayAtFraction(fraction: Float): MemoriesTimelinePlaceholderDay? {
        if (
            days.isEmpty() ||
            totalAdvertisedItemCount == 0L ||
            !fraction.isFinite()
        ) {
            return null
        }
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped == 1f) return days.last()
        var consumed = 0L
        return days.firstOrNull { day ->
            consumed += day.advertisedItemCount.toLong()
            val dayEndFraction = (
                consumed.toDouble() /
                    totalAdvertisedItemCount.toDouble()
                ).toFloat()
            clamped < dayEndFraction
        } ?: days.last()
    }

    fun fractionFor(month: PhotoTimelineMonth): Float? {
        val section = months.firstOrNull { section -> section.month == month } ?: return null
        if (totalAdvertisedItemCount == 0L) return 0f
        return (
            section.firstAdvertisedItemOffset.toDouble() /
                totalAdvertisedItemCount.toDouble()
            ).toFloat()
    }

    fun fractionForDay(dayId: Long): Float? {
        if (totalAdvertisedItemCount == 0L) return null
        var advertisedOffset = 0L
        days.forEach { day ->
            if (day.dayId == dayId) {
                return (
                    advertisedOffset.toDouble() /
                        totalAdvertisedItemCount.toDouble()
                    ).toFloat()
            }
            advertisedOffset += day.advertisedItemCount.toLong()
        }
        return null
    }

    fun dayIdsIntersectingGridItems(
        firstGridItemIndex: Int,
        lastGridItemIndex: Int,
    ): List<Long> {
        if (
            firstGridItemIndex < 0 ||
            lastGridItemIndex < firstGridItemIndex ||
            firstGridItemIndex >= totalGridItemCount
        ) {
            return emptyList()
        }
        val boundedLast = lastGridItemIndex.coerceAtMost(totalGridItemCount - 1)
        return days.asSequence()
            .filter { day ->
                day.firstGridItemIndex <= boundedLast &&
                    day.lastGridItemIndex >= firstGridItemIndex
            }
            .map(MemoriesTimelinePlaceholderDay::dayId)
            .toList()
    }

    fun firstDayIdFor(month: PhotoTimelineMonth): Long? {
        val section = months.firstOrNull { section -> section.month == month } ?: return null
        return days.getOrNull(section.firstDayIndex)?.dayId
    }
}

fun buildMemoriesTimelinePlaceholderGeometry(
    index: MemoriesMainTimelineDayIndex,
    monthResolver: PhotoTimelineMonthResolver = UtcPhotoTimelineMonthResolver,
): MemoriesTimelinePlaceholderGeometry? {
    val positiveDays = index.days.filter { day -> day.itemCount > 0 }
    if (positiveDays.isEmpty()) {
        return MemoriesTimelinePlaceholderGeometry(
            days = emptyList(),
            months = emptyList(),
            totalAdvertisedItemCount = 0L,
            totalGridItemCount = 0,
        )
    }

    val totalAdvertisedItems = positiveDays.fold(0L) { total, day ->
        if (total > Long.MAX_VALUE - day.itemCount.toLong()) return null
        total + day.itemCount
    }
    if (totalAdvertisedItems + positiveDays.size.toLong() > Int.MAX_VALUE.toLong()) {
        return null
    }

    val days = mutableListOf<MemoriesTimelinePlaceholderDay>()
    val months = mutableListOf<MemoriesTimelinePlaceholderMonth>()
    var advertisedOffset = 0L
    positiveDays.forEach { day ->
        val month = monthResolver.resolve(day.id * MEMORIES_TIMELINE_SECONDS_PER_DAY)
        val currentMonth = months.lastOrNull()
        val sectionIndex = if (currentMonth?.month == month) {
            months.lastIndex
        } else {
            months.size
        }
        val firstGridItemIndex = advertisedOffset + sectionIndex.toLong() + 1L
        val monthHeaderGridItemIndex = advertisedOffset + sectionIndex.toLong()
        days += MemoriesTimelinePlaceholderDay(
            dayId = day.id,
            advertisedItemCount = day.itemCount,
            month = month,
            firstGridItemIndex = firstGridItemIndex.toInt(),
        )
        if (currentMonth?.month != month) {
            months += MemoriesTimelinePlaceholderMonth(
                month = month,
                monthHeaderGridItemIndex = monthHeaderGridItemIndex.toInt(),
                firstDayIndex = days.lastIndex,
                dayCount = 1,
                firstAdvertisedItemOffset = advertisedOffset,
                advertisedItemCount = day.itemCount.toLong(),
            )
        } else {
            months[sectionIndex] = currentMonth.copy(
                dayCount = currentMonth.dayCount + 1,
                advertisedItemCount = currentMonth.advertisedItemCount + day.itemCount.toLong(),
            )
        }
        advertisedOffset += day.itemCount.toLong()
    }

    return MemoriesTimelinePlaceholderGeometry(
        days = days,
        months = months,
        totalAdvertisedItemCount = totalAdvertisedItems,
        totalGridItemCount = (totalAdvertisedItems + months.size.toLong()).toInt(),
    )
}
