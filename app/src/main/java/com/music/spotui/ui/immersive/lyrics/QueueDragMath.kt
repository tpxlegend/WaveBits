package com.music.spotui.ui.immersive.lyrics

/**
 * The index arithmetic of dragging a queue row, kept free of Compose so it can be tested.
 * Rows are one flat list (the queued rows, then the context's); "others" is that list without
 * the dragged row, and a slot is a gap in it: 0 before the first, others.size after the last.
 */
internal object QueueDragMath {
    /** The gap the dragged row's centre is in: how many of the other rows' midpoints lie above it. */
    fun slot(center: Float, otherMidpoints: List<Float>): Int = otherMidpoints.count { it < center }

    /**
     * How far the row at flat [index] steps aside (-1 up, 1 down, 0 stays) while the row at
     * [from] hovers over gap [slot] of the others.
     */
    fun step(index: Int, from: Int, slot: Int): Int = when {
        index == from -> 0
        index > from && index - 1 < slot -> -1
        index < from && index >= slot -> 1
        else -> 0
    }

    /** Where a gap lands: in the queued rows ([queueSize] others) or the context's, and at which index there. */
    data class Place(val toQueue: Boolean, val index: Int)

    /**
     * Gap [slot] as a place. The gap between the two sections belongs to the queue when the row's
     * centre is above the context heading's midpoint ([aboveBoundary]), else to the context.
     */
    fun place(slot: Int, queueSize: Int, aboveBoundary: Boolean): Place = when {
        slot < queueSize -> Place(true, slot)
        slot > queueSize -> Place(false, slot - queueSize)
        aboveBoundary -> Place(true, queueSize)
        else -> Place(false, 0)
    }

    /** [list] with the element at [from] taken out and put at [to] of the rest. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> =
        list.toMutableList().apply { add(to, removeAt(from)) }

    /** Pixels to scroll this frame for a finger at [y] in a viewport of [height]: 0 outside the edge zones, growing towards the edge. */
    fun edgeStep(y: Float, height: Float, zone: Float, maxStep: Float): Float {
        val up = ((zone - y) / zone).coerceIn(0f, 1f)
        val down = ((y - (height - zone)) / zone).coerceIn(0f, 1f)
        return (down - up) * maxStep
    }

    /** Which of the section's other rows an engine call names for the drop at [index]: its position and whether to go after it; null when the section has no other row. */
    fun target(size: Int, index: Int): Pair<Int, Boolean>? = when {
        size == 0 -> null
        index < size -> index to false
        else -> size - 1 to true
    }

    /** The two sections after [dragged] (taken out of both) is dropped at [place]. */
    fun <T> apply(queued: List<T>, next: List<T>, dragged: T, place: Place): Pair<List<T>, List<T>> {
        val q = queued.filter { it != dragged }
        val n = next.filter { it != dragged }
        fun <E> List<E>.with(i: Int, e: E) = toMutableList().apply { add(i.coerceIn(0, size), e) }
        return if (place.toQueue) q.with(place.index, dragged) to n else q to n.with(place.index, dragged)
    }

    /**
     * Songs of the Spotify queue move between its two parts only one way: out of "next from" into
     * the queue (a song added to it). A queued song cannot go back, so its drop is held in the queue.
     */
    fun held(place: Place, queueSize: Int, canLeaveQueue: Boolean, fromQueue: Boolean): Place =
        if (fromQueue && !canLeaveQueue && !place.toQueue) Place(true, queueSize) else place

    /**
     * The order just dropped, shown until the engine reports it. [queued] / [next] are the rows,
     * [queuedUris] / [nextUris] the songs' uris in that order, [current] what played then.
     */
    class Pending<T>(
        val queued: List<T>, val next: List<T>,
        private val queuedUris: List<String>, private val nextUris: List<String>,
        private val current: String?,
    ) {
        /**
         * The engine's report ends it when it shows the same order, or another song plays now. Reports
         * before that (the queue as it was, or half-way: a song out of one part, not yet in the other) do not.
         */
        fun over(reportedQueued: List<String>, reportedNext: List<String>, reportedCurrent: String?): Boolean =
            (reportedQueued == queuedUris && reportedNext == nextUris) || reportedCurrent != current
    }
}
