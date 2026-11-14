package de.arjmandi.nanobot.companion.activity

/** One row inside a turn's activity cluster (A4), as Kotlin's own values: the core reduced the
 * frames into `ActivityItem`; the bridge maps each case here and nothing else branches on it. */
data class ActivityItemView(val id: String, val kind: ActivityKind)

/** What nanobot did on the way to the answer, one case per thing the design draws. */
sealed interface ActivityKind {

    /** The model's own words before it answers, folded when the reasoning ends. */
    data class Reasoning(val text: String, val done: Boolean) : ActivityKind

    data class Tool(
        val name: String,
        val summary: String?,
        val status: ToolRunStatus,
        /** The gateway's own reason on a failure ("exit 4"), never something the app invented. */
        val detail: String?,
    ) : ActivityKind

    /** A write or edit, drawn as a path with a unified diff. */
    data class FileEdit(
        val path: String,
        val status: ToolRunStatus,
        val added: Int,
        val deleted: Int,
        val operation: String?,
        val diff: String,
    ) : ActivityKind {

        /**
         * A diff's first two lines are the file headers and `@@` is a position marker rather than
         * a change: neither is a row the design draws.
         */
        val lines: List<DiffLine>
            get() = diff.split("\n").mapNotNull { line ->
                when {
                    line.startsWith("--- ") || line.startsWith("+++ ") || line.isEmpty() -> null
                    line.startsWith("@@") -> DiffLine(DiffKind.Hunk, line)
                    line.startsWith("+") -> DiffLine(DiffKind.Added, line)
                    line.startsWith("-") -> DiffLine(DiffKind.Removed, line)
                    else -> DiffLine(DiffKind.Context, line)
                }
            }
    }

    data class Search(val query: String, val sources: List<String>) : ActivityKind
}

enum class ToolRunStatus { Running, Done, Failed }

enum class DiffKind { Context, Added, Removed, Hunk }

/** One diff row; [text] keeps the marker (a monospaced row shows it) and [body] drops it. */
data class DiffLine(val kind: DiffKind, val text: String) {
    val body: String get() = if (kind == DiffKind.Added || kind == DiffKind.Removed) text.drop(1) else text
}

/** A web-search hit, as the chip says it: the host and the tail of the path. */
data class SourceChip(val id: String, val label: String) {

    companion object {
        fun of(url: String): SourceChip {
            val withoutScheme = url.substringAfter("://", url)
            val host = withoutScheme.substringBefore('/').takeIf { it.isNotEmpty() }
                ?: return SourceChip(url, url)
            val tail = withoutScheme.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
            return SourceChip(url, if (tail.isEmpty()) host else "$host · $tail")
        }
    }
}

/** The design's three words for a cluster's state. */
enum class ActivityStatus { Running, Done, Failed }

/**
 * The head of a cluster. The sentence is the platform's string resources; the model only says
 * which clause goes where, so `Thought for 8s · 3 steps · 1 file changed` stays testable off a
 * device and no copy is typed into a view model.
 */
sealed interface ActivityHeadline {
    data object Thinking : ActivityHeadline
    data class Finished(val seconds: Int, val steps: Int, val files: Int) : ActivityHeadline
}

/** One turn's folded activity, as the row draws it. */
data class ActivityCluster(
    val turnId: String,
    val status: ActivityStatus,
    val headline: ActivityHeadline,
    val items: List<ActivityItemView>,
    val isOpen: Boolean,
    val hasFailure: Boolean,
    val steps: Int,
) {
    /** Every web-search hit in the cluster, in arrival order. */
    val sourceChips: List<SourceChip>
        get() = items.flatMap { item ->
            (item.kind as? ActivityKind.Search)?.sources?.map(SourceChip::of) ?: emptyList()
        }

    fun opening(open: Boolean): ActivityCluster = copy(isOpen = open)
}
