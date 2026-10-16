package calino.malinov.ski.ui.components

import androidx.compose.ui.geometry.Rect
import calino.malinov.ski.data.model.CalTask
import calino.malinov.ski.state.TaskReorderPlan
import calino.malinov.ski.state.isReorderable
import calino.malinov.ski.state.planReorder
import calino.malinov.ski.state.sortOrderOwner

// Drag-to-reorder for task lists that show a day's tasks as a column (the
// agenda, the sidebar): while a task is held, the others slide apart to open a
// gap where it would land, and the release drops it into that gap.

/** A held task's live preview: where the other rows slide, and what a release would write. */
internal class RowReorderPreview(
    /** Vertical offset per row id, opening the gap where the task would land. */
    val shifts: Map<String, Float>,
    /** The card is still over its day's tasks, so a release never changes the date. */
    val inDay: Boolean,
    /** The writes for the new position, or null where it would land where it was. */
    val plan: TaskReorderPlan?,
)

/**
 * The gap preview for [dragged]: its group is the day's open, untimed tasks
 * under the same parent. It lands before every group member whose centre is
 * below the dragged card's centre. Members between its old and new place
 * slide by one [slot] -- together with their subtasks, which travel with them.
 */
internal fun rowReorderPreview(
    rowTasks: List<CalTask>,
    rowDepth: Map<String, Int>,
    rowBounds: Map<String, Rect>,
    dragged: CalTask,
    draggedCenter: Float,
    slot: Float,
    snap: Float,
): RowReorderPreview {
    val group = rowTasks.filter {
        it.id == dragged.id || (it.isReorderable() && it.due == dragged.due && it.parentTaskId == dragged.parentTaskId)
    }.distinctBy { it.sortOrderOwner() }
    val others = group.filter { it.id != dragged.id }
    val from = group.indexOfFirst { it.id == dragged.id }
    val centre = { task: CalTask -> rowBounds[task.id]?.center?.y }
    val to = others.count { other -> centre(other)?.let { it < draggedCenter } == true }
    // The rows a member carries with it: itself and the subtasks listed under it.
    fun block(member: CalTask): List<String> {
        val start = rowTasks.indexOfFirst { it.id == member.id }
        val depth = rowDepth[member.id] ?: 0
        return listOf(member.id) + rowTasks.drop(start + 1)
            .takeWhile { (rowDepth[it.id] ?: 0) > depth }
            .map { it.id }
    }
    val shifts = HashMap<String, Float>()
    others.forEachIndexed { index, member ->
        val offset = when {
            index < from && index >= to -> slot
            index >= from && index < to -> -slot
            else -> 0f
        }
        if (offset != 0f) block(member).forEach { shifts[it] = offset }
    }
    val tops = group.mapNotNull { rowBounds[it.id]?.top }
    val bottoms = group.mapNotNull { rowBounds[it.id]?.bottom }
    val inDay = tops.isNotEmpty() &&
        draggedCenter >= tops.min() - snap && draggedCenter <= bottoms.max() + snap
    val plan = if (inDay && to != from) {
        planReorder(group, dragged, to).takeIf { it.isNotEmpty() }?.let { TaskReorderPlan(it) }
    } else {
        null
    }
    return RowReorderPreview(shifts, inDay, plan)
}
