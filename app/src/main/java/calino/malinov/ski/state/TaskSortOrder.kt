package calino.malinov.ski.state

import calino.malinov.ski.data.model.CalTask
import calino.malinov.ski.data.model.NewTask

/**
 * Manual task order, stored as `X-APPLE-SORT-ORDER` on the VTODO -- the
 * property Apple Reminders, Nextcloud Tasks and tasks.org already use. Lower
 * comes first. It only breaks ties inside a day, a group or a set of siblings
 * and never moves a task to another day; the web app applies the same rule.
 */

/** Gap left between consecutive values Calino writes. */
const val SortOrderStep = 1000L

/**
 * Lower sort order first, tasks without one after every task that has one.
 * Two unordered tasks compare equal, so the comparators this is chained into
 * keep deciding their order exactly as before.
 */
val TaskManualOrder: Comparator<CalTask> = compareBy(nullsLast()) { it.sortOrder }

/**
 * The value a new subtask gets so it lands after its ordered siblings, or null
 * when none of them has one (the subtask then stays unordered too).
 */
fun nextSubtaskSortOrder(siblings: Iterable<CalTask>): Long? =
    siblings.mapNotNull { it.sortOrder }.maxOrNull()?.let { it + SortOrderStep }

/** A new subtask without an order of its own goes after its siblings. */
fun NewTask.withNewSubtaskOrder(tasks: List<CalTask>): NewTask {
    val parent = parentTaskId ?: return this
    if (sortOrder != null) return this
    // Occurrences of a repeating sibling share their master's value, so they
    // cannot raise the maximum beyond what the series itself holds.
    return copy(sortOrder = nextSubtaskSortOrder(tasks.filter { it.parentTaskId == parent }))
}

// ── Reordering ──────────────────────────────────────────────────────────────
// The same rules as the web app's `planReorder` (src/lib/taskReorder.ts), so a
// move made in either app writes the same values.

/** A sort order Calino has to write for a reorder, on [task] (a row standing in for its series). */
data class SortOrderWrite(val task: CalTask, val sortOrder: Long)

/** What a reorder writes: new sort orders, and possibly a new parent for the moved task. */
data class TaskReorderPlan(
    val writes: List<SortOrderWrite>,
    /** The moved task and its new parent, when a drop between rows also re-parents it. */
    val reparent: Pair<CalTask, String?>? = null,
)

/** Where a dragged task lands relative to the row under it. */
enum class TaskInsertEdge { Before, After }

/**
 * The key a row's order is stored under. Every occurrence of a repeating task
 * is one series with one value, written on the master, so they share a key.
 */
fun CalTask.sortOrderOwner(): String = if (isRecurringTask()) "series:${uid ?: id}" else "task:$id"

/** Can this row take a manual position at all? Timed and done tasks sort by other rules. */
fun CalTask.isReorderable(): Boolean =
    !done && dueTime == null && !status.equals("CANCELLED", ignoreCase = true)

/**
 * The writes that place [moved] at [index] within [group].
 *
 * [group] is what the person sees, in order, and may include [moved]; [index]
 * is the position in the list *without* it. Ordered tasks come first in every
 * view, so the members with a value are an ascending prefix of the group.
 *
 * The moved task normally takes the midpoint of its new neighbours, so a move
 * writes one task. Without an integer gap the ordered part of the group is
 * renumbered with a step of [SortOrderStep]; if the task above has no value,
 * the group is first given values, in its current order, down to the drop
 * point. Only values that actually change are returned.
 */
fun planReorder(group: List<CalTask>, moved: CalTask, index: Int): List<SortOrderWrite> {
    val movedKey = moved.sortOrderOwner()
    val others = group.filter { it.sortOrderOwner() != movedKey }
    val at = index.coerceIn(0, others.size)
    // The moved task's own value no longer counts where it lands.
    val order: List<Pair<CalTask, Long?>> =
        others.take(at).map { it to it.sortOrder } + (moved to null) + others.drop(at).map { it to it.sortOrder }
    val prev = order.getOrNull(at - 1)?.second
    val next = order.getOrNull(at + 1)?.second

    val assigned = LinkedHashMap<String, Pair<CalTask, Long>>()
    fun assign(task: CalTask, value: Long) { assigned[task.sortOrderOwner()] = task to value }
    when {
        at > 0 && prev == null -> {
            var last: Long? = null
            for ((task, value) in order.take(at + 1)) {
                if (task.sortOrderOwner() != movedKey && value != null) { last = value; continue }
                last = last?.plus(SortOrderStep) ?: SortOrderStep
                assign(task, last)
            }
        }
        prev == null -> assign(moved, next?.minus(SortOrderStep) ?: SortOrderStep)
        next == null -> assign(moved, prev + SortOrderStep)
        next - prev >= 2 -> assign(moved, Math.floorDiv(prev + next, 2L))
        else -> {
            // `prev` is set, so the first member is too.
            val ordered = order.filter { (task, value) -> task.sortOrderOwner() == movedKey || value != null }
            val start = order.first().second ?: SortOrderStep
            ordered.forEachIndexed { position, (task, _) -> assign(task, start + position * SortOrderStep) }
        }
    }
    val current = group.associate { it.sortOrderOwner() to it.sortOrder }
    return assigned.values
        .filter { (task, value) -> current[task.sortOrderOwner()] != value }
        .map { (task, value) -> SortOrderWrite(task, value) }
}

/** The index [planReorder] needs to move [moved] one step up (-1) or down (+1), or null at that end. */
fun stepIndex(group: List<CalTask>, moved: CalTask, direction: Int): Int? {
    val from = group.indexOfFirst { it.sortOrderOwner() == moved.sortOrderOwner() }
    if (from < 0) return null
    val to = from + direction
    if (to < 0 || to >= group.size) return null
    return if (direction < 0) to else from + 1
}

/**
 * The tasks Move up / Move down step through: open, untimed tasks due the same
 * day (or all undated ones) under the same parent, in the order the day lists
 * show them, one row per series.
 */
fun dayReorderGroup(tasks: List<CalTask>, task: CalTask): List<CalTask> {
    if (!task.isReorderable()) return emptyList()
    val candidates = tasks.filter {
        it.due == task.due && it.isReorderable() && it.parentTaskId == task.parentTaskId
    }
    val ordered = if (task.due == null) candidates.sortedWith(TaskManualOrder) else tasksDueOn(candidates, task.due)
    return ordered.distinctBy { it.sortOrderOwner() }
}

/** What a menu step would write, or null when the task cannot move that way. */
fun planStep(tasks: List<CalTask>, task: CalTask, direction: Int): TaskReorderPlan? {
    val group = dayReorderGroup(tasks, task)
    val index = stepIndex(group, task, direction) ?: return null
    return TaskReorderPlan(planReorder(group, task, index)).takeIf { it.writes.isNotEmpty() }
}

/**
 * What dropping [dragged] on the [edge] of [targetId] would write, or null
 * when that is not a reorder: another day, a timed or done task, a move that
 * would nest a task inside itself, or a repeating task changing parent. [rows]
 * are the tasks as the screen lists them; [tree] checks the whole hierarchy.
 */
fun planDropReorder(
    rows: List<CalTask>,
    dragged: CalTask,
    targetId: String,
    edge: TaskInsertEdge,
    tree: TaskTree,
): TaskReorderPlan? {
    if (dragged.id == targetId || !dragged.isReorderable()) return null
    var anchor = rows.firstOrNull { it.id == targetId } ?: return null
    var position = edge
    // Below an expanded parent the next row is its first subtask, so the gap
    // there belongs to the subtasks: the task becomes the first of them.
    val below = rows.drop(rows.indexOf(anchor) + 1).firstOrNull { it.id != dragged.id }
    if (position == TaskInsertEdge.After && below?.parentTaskId == anchor.id) {
        anchor = below
        position = TaskInsertEdge.Before
    }
    if (!anchor.isReorderable() || anchor.due != dragged.due) return null
    val parent = anchor.parentTaskId
    val reparenting = dragged.parentTaskId != parent
    if (reparenting && (dragged.isRecurringTask() || !tree.canAdopt(dragged.id, parent))) return null

    val group = rows.filter {
        it.id != dragged.id && it.isReorderable() && it.due == dragged.due && it.parentTaskId == parent
    }.distinctBy { it.sortOrderOwner() }
    val at = group.indexOf(anchor).takeIf { it >= 0 } ?: return null
    val writes = planReorder(
        group.take(at) + dragged + group.drop(at),
        dragged,
        if (position == TaskInsertEdge.Before) at else at + 1,
    )
    val reparent = if (reparenting) dragged to parent else null
    if (writes.isEmpty() && reparent == null) return null
    return TaskReorderPlan(writes, reparent)
}
