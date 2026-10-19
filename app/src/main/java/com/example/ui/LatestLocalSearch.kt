package com.example.ui

import androidx.annotation.MainThread
import com.example.data.ItemWithCollections
import com.example.model.DateBasis
import com.example.model.DateFilter
import com.example.model.DateGrouping
import com.example.model.ItemKind
import com.example.model.SortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** One immutable snapshot is used for both flat results and grouped results. */
internal data class LocalSearchContext(
    val query: String = "",
    val sourceItems: List<ItemWithCollections> = emptyList(),
    val kind: ItemKind? = null,
    val collectionId: String? = null,
    val dateFilter: DateFilter = DateFilter.ALL_TIME,
    val basis: DateBasis = DateBasis.SAVED_DATE,
    val sort: SortOrder = SortOrder.SAVED_NEWEST,
    val customStart: Long? = null,
    val customEnd: Long? = null,
    val grouping: DateGrouping = DateGrouping.NONE
)

internal data class LocalSearchResult(
    val items: List<ItemWithCollections>,
    val sections: List<GroupedLibrarySection>
)

/** Cancellation reduces wasted work; the generation check also rejects non-cooperative work. */
internal class LatestLocalSearch(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> List<ItemWithCollections>,
    private val onInvalidated: () -> Unit,
    private val onResult: (LocalSearchResult) -> Unit,
    private val onError: () -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val publicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    private val generation = AtomicLong()
    private var job: Job? = null
    val currentGeneration: Long get() = generation.get()

    // Called by the ViewModel's Main-thread setters and Room collectors. Publication uses the
    // same dispatcher, so checking the generation and changing visible state cannot interleave.
    @MainThread
    fun refresh(context: LocalSearchContext, debounceQuery: Boolean = false) {
        val capturedGeneration = generation.incrementAndGet()
        // Invalidate synchronously, before the debounce or any repository/file-system suspension.
        onInvalidated()
        job?.cancel()
        job = scope.launch(workerDispatcher) {
            try {
                if (debounceQuery && context.query.isNotBlank()) delay(150)
                val rows = if (context.query.isBlank()) context.sourceItems else search(context.query)
                val result = projectLocalSearch(rows, context)
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) onResult(result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) onError()
                }
            }
        }
    }
}

/** Also applies the visible UI constraints to additive AI candidates. It never calls a provider. */
internal fun projectLocalSearch(rows: List<ItemWithCollections>, context: LocalSearchContext): LocalSearchResult {
    val calendar = Calendar.getInstance()
    val currentYear = calendar.get(Calendar.YEAR)
    val currentMonth = calendar.get(Calendar.MONTH)
    val filtered = rows.asSequence()
        .filter { context.kind == null || it.item.kind == context.kind.name }
        .filter { row ->
            when (context.collectionId) {
                null -> true
                "computed_favorites" -> row.item.isFavorite
                "computed_unfiled" -> row.collections.isEmpty()
                "computed_due" -> row.item.reminderTime != null && !row.item.isReminderDone
                else -> row.collections.any { it.id == context.collectionId }
            }
        }
        .filter { row ->
            val timestamp = if (context.basis == DateBasis.SOURCE_DATE) row.item.sourceDate else row.item.createdAt
            when (context.dateFilter) {
                DateFilter.ALL_TIME -> true
                DateFilter.THIS_MONTH -> timestamp != null && run {
                    calendar.timeInMillis = timestamp
                    calendar.get(Calendar.YEAR) == currentYear && calendar.get(Calendar.MONTH) == currentMonth
                }
                DateFilter.THIS_YEAR -> timestamp != null && run {
                    calendar.timeInMillis = timestamp
                    calendar.get(Calendar.YEAR) == currentYear
                }
                DateFilter.CUSTOM -> timestamp != null && context.customStart != null && context.customEnd != null &&
                    timestamp >= context.customStart && timestamp <= context.customEnd
            }
        }
        .sortedWith { a, b ->
            when (context.sort) {
                SortOrder.SAVED_NEWEST -> b.item.createdAt.compareTo(a.item.createdAt)
                SortOrder.SAVED_OLDEST -> a.item.createdAt.compareTo(b.item.createdAt)
                SortOrder.SOURCE_NEWEST -> (b.item.sourceDate ?: Long.MIN_VALUE).compareTo(a.item.sourceDate ?: Long.MIN_VALUE)
                SortOrder.SOURCE_OLDEST -> (a.item.sourceDate ?: Long.MAX_VALUE).compareTo(b.item.sourceDate ?: Long.MAX_VALUE)
                SortOrder.TITLE_AZ -> a.item.title.compareTo(b.item.title, true)
            }
        }.toList()
    val sections = if (context.grouping == DateGrouping.NONE) {
        listOf(GroupedLibrarySection("", filtered))
    } else {
        val formatter = SimpleDateFormat(when (context.grouping) {
            DateGrouping.DAY -> "EEEE, MMMM d, yyyy"
            DateGrouping.MONTH -> "MMMM yyyy"
            DateGrouping.YEAR -> "yyyy"
            DateGrouping.NONE -> ""
        }, Locale.getDefault())
        filtered.groupBy { row ->
            val ts = if (context.basis == DateBasis.SOURCE_DATE) row.item.sourceDate else row.item.createdAt
            if (ts == null) "No ${context.basis.label}" else formatter.format(Date(ts))
        }.map { (title, items) -> GroupedLibrarySection(title, items) }
    }
    return LocalSearchResult(filtered, sections)
}
