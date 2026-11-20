package com.music.spotui.ui.immersive.lyrics

import com.music.spotui.di.LocalQueueOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDragMathTest {
    @Test fun slotCountsMidpointsAbove() {
        val mids = listOf(30f, 90f, 150f)
        assertEquals(0, QueueDragMath.slot(10f, mids))
        assertEquals(2, QueueDragMath.slot(100f, mids))
        assertEquals(3, QueueDragMath.slot(500f, mids))
    }

    @Test fun rowsStepAsideBetweenFromAndSlot() {
        // row 1 of 5 dragged down over gap 3 of the others: rows 2 and 3 step up
        assertEquals(listOf(0, 0, -1, -1, 0), (0..4).map { QueueDragMath.step(it, 1, 3) })
        // row 3 dragged up over gap 1: rows 1 and 2 step down
        assertEquals(listOf(0, 1, 1, 0, 0), (0..4).map { QueueDragMath.step(it, 3, 1) })
        assertEquals(listOf(0, 0, 0, 0, 0), (0..4).map { QueueDragMath.step(it, 2, 2) })
    }

    @Test fun placeSplitsAtTheSections() {
        assertEquals(QueueDragMath.Place(true, 1), QueueDragMath.place(1, 3, false))
        assertEquals(QueueDragMath.Place(false, 2), QueueDragMath.place(5, 3, true))
        assertEquals(QueueDragMath.Place(true, 3), QueueDragMath.place(3, 3, true))
        assertEquals(QueueDragMath.Place(false, 0), QueueDragMath.place(3, 3, false))
    }

    @Test fun movedKeepsTheDroppedOrder() {
        assertEquals(listOf("b", "c", "a"), QueueDragMath.moved(listOf("a", "b", "c"), 0, 2))
        assertEquals(listOf("c", "a", "b"), QueueDragMath.moved(listOf("a", "b", "c"), 2, 0))
    }

    @Test fun edgeStepGrowsTowardsTheEdge() {
        assertEquals(0f, QueueDragMath.edgeStep(500f, 1000f, 100f, 20f), 0f)
        assertEquals(-10f, QueueDragMath.edgeStep(50f, 1000f, 100f, 20f), 0.001f)
        assertEquals(20f, QueueDragMath.edgeStep(1000f, 1000f, 100f, 20f), 0.001f)
        assertTrue(QueueDragMath.edgeStep(20f, 1000f, 100f, 20f) < QueueDragMath.edgeStep(80f, 1000f, 100f, 20f))
    }

    // ── The panel's expected order against what the local player's move logic ends with ──

    /** cur, then [nq] queued songs q0.., then [nn] list songs n0.. */
    private fun local(nq: Int, nn: Int): Triple<List<String>, Set<String>, Int> {
        val queued = (0 until nq).map { "q$it" }
        return Triple(listOf("cur") + queued + (0 until nn).map { "n$it" }, queued.toSet(), 0)
    }

    @Test fun everyDropEndsInThePanelsOrderOnTheLocalPlayer() {
        var checked = 0
        for (nq in 0..3) for (nn in 0..3) {
            val (order, queuedSet, cur) = local(nq, nn)
            val rows = order.drop(1)
            for (dragged in rows) for (above in listOf(true, false)) {
                val oq = rows.filter { it in queuedSet && it != dragged }
                val on = rows.filter { it !in queuedSet && it != dragged }
                for (slot in 0..(oq.size + on.size)) {
                    val place = QueueDragMath.place(slot, oq.size, above)
                    val (eq, en) = QueueDragMath.apply(oq, on, dragged, place)
                    if (eq == rows.filter { it in queuedSet } && en == rows.filter { it !in queuedSet }) continue
                    val into = if (place.toQueue) oq else on
                    val target = QueueDragMath.target(into.size, place.index)
                    val from = order.indexOf(dragged)
                    val wasQueued = dragged in queuedSet
                    val result: Pair<List<String>, Boolean>? = when {
                        place.toQueue && !wasQueued && target == null ->
                            LocalQueueOrder.moveToFront(order, cur, from)?.let { it to true }
                        target == null -> continue
                        else -> LocalQueueOrder.moveBeside(order, cur, from, order.indexOf(into[target.first]), target.second) { it in queuedSet }
                    }
                    val (after, toQueue) = result!!
                    val nowQueued = { x: String -> if (x == dragged) toQueue else x in queuedSet }
                    val tail = after.drop(1)
                    assertEquals("cur stays", "cur", after[0])
                    assertEquals("queued part $dragged slot $slot nq=$nq nn=$nn above=$above", eq, tail.filter(nowQueued))
                    assertEquals("list part $dragged slot $slot nq=$nq nn=$nn above=$above", en, tail.filterNot(nowQueued))
                    assertEquals("queued songs come first", tail.filter(nowQueued), tail.take(eq.size))
                    checked++
                }
            }
        }
        assertTrue(checked > 100)
    }

    @Test fun targetNamesTheNeighbourToDropBesideOrNothing() {
        assertEquals(null, QueueDragMath.target(0, 0))
        assertEquals(0 to false, QueueDragMath.target(3, 0))
        assertEquals(1 to false, QueueDragMath.target(3, 1))
        assertEquals(2 to true, QueueDragMath.target(3, 3))
    }

    @Test fun aQueuedSpotifySongStaysInTheQueue() {
        val into = QueueDragMath.Place(false, 2)
        assertEquals(QueueDragMath.Place(true, 3), QueueDragMath.held(into, 3, canLeaveQueue = false, fromQueue = true))
        assertEquals(into, QueueDragMath.held(into, 3, canLeaveQueue = true, fromQueue = true))
        assertEquals(into, QueueDragMath.held(into, 3, canLeaveQueue = false, fromQueue = false))
    }

    // ── The Spotify queue's reports while a move is applied: stale, half-way, final ──

    private fun pending(q: List<String>, n: List<String>) = QueueDragMath.Pending(q, n, q, n, "cur")

    @Test fun noReportBeforeTheFinalOneEndsThePendingOrder() {
        // "n1" dragged from the list into the queue, between q0 and q1.
        val q0 = listOf("q0", "q1"); val n0 = listOf("n0", "n1", "n2")
        val (eq, en) = QueueDragMath.apply(q0, n0, "n1", QueueDragMath.Place(true, 1))
        val p = pending(eq, en)
        assertEquals(listOf("q0", "n1", "q1"), eq)
        assertEquals(listOf("n0", "n2"), en)
        val reports = listOf(
            q0 to n0,                                  // stale: before the edit
            q0 to listOf("n0", "n2"),                  // removed from the list, not yet queued
            q0 + "n1" to listOf("n0", "n2"),           // queued at the end
        )
        for ((rq, rn) in reports) assertFalse("$rq $rn", p.over(rq, rn, "cur"))
        assertTrue(p.over(eq, en, "cur"))              // final
        assertEquals(eq, p.queued); assertEquals(en, p.next) // what is drawn all along
    }

    @Test fun aSameSectionMoveEndsOnItsFinalReportOnly() {
        val n0 = listOf("n0", "n1", "n2", "n3")
        val (eq, en) = QueueDragMath.apply(emptyList(), n0, "n0", QueueDragMath.Place(false, 3))
        val p = pending(eq, en)
        assertEquals(listOf("n1", "n2", "n3", "n0"), en)
        assertFalse(p.over(emptyList(), n0, "cur"))
        assertTrue(p.over(eq, en, "cur"))
    }

    @Test fun anotherSongPlayingEndsThePendingOrder() {
        assertTrue(pending(listOf("a"), listOf("b")).over(listOf("x"), listOf("y"), "other"))
    }
}
