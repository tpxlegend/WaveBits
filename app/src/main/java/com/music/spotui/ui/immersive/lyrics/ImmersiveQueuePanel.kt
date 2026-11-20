/*
 * WaveBits Immersive Queue Panel: sections (now playing, next in queue, next from the context),
 * placement-only item motion and fading edges over WaveBits's SongPlayer.engineQueue.
 */
package com.music.spotui.ui.immersive.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.wavebits.engine.R
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.Graph
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.screens.ExplicitBadge

/**
 * How a queue row moves when the running order changes under it.
 *
 * Placement only — the fades are off deliberately. A row Compose treats as removed or
 * inserted goes on being drawn at the slot it used to hold for as long as it takes to fade,
 * and a queue row's background is transparent: the fading copy and whichever row is sliding
 * through that slot both draw their title and artist into the same few pixels. Without the
 * fades a row that leaves is gone the moment it leaves, so movement is the only thing left
 * to see. Bounded rather than the default spring so a track change settles at once.
 */
private val QUEUE_ROW_MOTION = tween<IntOffset>(durationMillis = 200, easing = FastOutSlowInEasing)

/** Placement-only item motion; see [QUEUE_ROW_MOTION]. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyItemScope.queueMotion(): Modifier =
    Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = QUEUE_ROW_MOTION)

/**
 * The queue of the player, inside the player: what is playing, then "Next in queue" and
 * "Next from <context>". Tap a row to play it, swipe it away to remove it; shuffle and repeat
 * are in the header.
 *
 * The list brings its own side gutter; [contentPadding] is the extra room it keeps clear of
 * whatever the player draws over it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImmersiveQueuePanel(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    DisposableEffect(Unit) {
        SongPlayer.watchQueue(true)
        onDispose { SongPlayer.watchQueue(false) }
    }
    val queue by SongPlayer.engineQueue
    val song = Graph.currentSongState
    val shuffle = song.shuffle.value
    val repeatMode = SongPlayer.repeatMode.value
    val contextName = SongPlayer.nowPlaying.value.contextName

    // Rows swiped away, hidden at once: the queue takes a moment to report the removal.
    val removed = remember { mutableStateListOf<String>() }
    LaunchedEffect(queue) { removed.clear() }
    // Rows just dragged, shown in their new order until the queue reports exactly that order (or
    // PENDING_ORDER_MS passes): the reports in between are of the queue before the move.
    var pending by remember { mutableStateOf<QueueDragMath.Pending<QRow>?>(null) }
    LaunchedEffect(queue, pending) {
        val p = pending ?: return@LaunchedEffect
        val upcoming = (queue.nextUp + (if (queue.autoplayEnabled) queue.recommendations else emptyList())).distinctBy { it.uid }
        if (p.over(queue.queued.map { it.uri }, upcoming.map { it.uri }, queue.current?.url)) pending = null
    }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(PENDING_ORDER_MS)
        if (pending === p) pending = null
    }
    val rows = shownRows(queue, pending, removed)
    val queued = rows.filter { it.queued }
    val nextUp = rows.filter { !it.queued }
    val drag = remember { QueueDrag() }

    val listState = rememberLazyListState()
    val keepScroll = remember(listState) { keepScrollInList(listState) }
    val direction = LocalLayoutDirection.current
    val current = queue.current
    val hasCurrent = song.title.value.isNotBlank() || current != null

    val rowsNow by rememberUpdatedState(rows)
    val hover by remember { derivedStateOf { hoverOf(listState, rowsNow, drag) } }
    val hoverNow by rememberUpdatedState(hover)
    val onDrop: (QRow) -> Unit = drop@{ row ->
        val (queuedNew, nextNew, call) = dropPlan(rowsNow, row, hoverNow) ?: return@drop
        pending = QueueDragMath.Pending(
            queuedNew, nextNew, queuedNew.map { it.item.uri }, nextNew.map { it.item.uri }, queue.current?.url,
        )
        call()
    }

    // The finger near the top or bottom edge scrolls the list, the row staying under it.
    val edge = with(LocalDensity.current) { 88.dp.toPx() }
    val maxStep = with(LocalDensity.current) { 14.dp.toPx() }
    val dragging = drag.key != null
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == drag.key } ?: continue
            val y = row.offset + row.size / 2f + drag.offset
            val step = QueueDragMath.edgeStep(y, info.viewportSize.height.toFloat(), edge, maxStep)
            if (step != 0f) drag.offset += listState.scrollBy(step)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .nestedScroll(keepScroll)
            .fadingEdges(),
        contentPadding = PaddingValues(
            start = Immersive.Gutter + contentPadding.calculateStartPadding(direction),
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = Immersive.Gutter + contentPadding.calculateEndPadding(direction),
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
    ) {
        item(key = "header") {
            QueueHeader(
                shuffle = shuffle,
                repeatMode = repeatMode,
                onShuffle = {
                    SongPlayer.setShuffle(!shuffle)
                    song.shuffle.value = !shuffle
                },
                onRepeat = { SongPlayer.cycleRepeat() },
            )
        }
        if (hasCurrent) {
            item(key = "heading-now") {
                QueueHeading(
                    title = stringResource(R.string.immersive_queue_now_playing),
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp).then(queueMotion()),
                )
            }
            item(key = "now-playing") {
                QueueRow(
                    title = song.title.value.ifBlank { current?.title.orEmpty() },
                    artist = song.singer.value.ifBlank { current?.singer.orEmpty() },
                    cover = song.coverUri.value.ifBlank { current?.coverUri.orEmpty() },
                    isCurrent = true,
                    explicit = current?.explicit == true,
                    onClick = {},
                    modifier = queueMotion(),
                )
            }
        }
        if (queued.isNotEmpty()) {
            item(key = "heading-queued") {
                QueueHeading(
                    title = stringResource(R.string.next_in_queue),
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).then(queueMotion()),
                ) {
                    Text(
                        text = stringResource(R.string.clear_queue),
                        style = Immersive.Subhead,
                        color = Immersive.TextSecondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { SongPlayer.clearQueue() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            queueSection(queued, rowsNow, removed, drag, { hoverNow }, onDrop)
        }
        if (nextUp.isNotEmpty()) {
            item(key = HEADING_NEXT) {
                QueueHeading(
                    title = if (contextName.isNotBlank()) {
                        stringResource(R.string.next_from, contextName)
                    } else {
                        stringResource(R.string.next_up)
                    },
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp).then(queueMotion()),
                )
            }
            queueSection(nextUp, rowsNow, removed, drag, { hoverNow }, onDrop)
        }
        if (!hasCurrent && queued.isEmpty() && nextUp.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.immersive_queue_empty),
                    style = Immersive.Body,
                    color = Immersive.TextSecondary,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }
    }
}

/** One queue row: [index] in the flat list (queued rows, then the context's), [key] stable across moves. */
private data class QRow(val key: String, val item: SongPlayer.QueueItem, val queued: Boolean, val index: Int)

private const val PENDING_ORDER_MS = 3_000L

/**
 * The rows to draw: the order just dropped while the engine has not reported it (its reports in
 * between show the queue as it was, or a song out of one part and not yet in the other), else the
 * queue as reported. Keyed by song and occurrence, not by the row id Spotify gives: that changes
 * with a move, and a changed key makes the row vanish and reappear.
 */
private fun shownRows(queue: SongPlayer.EngineQueue, pending: QueueDragMath.Pending<QRow>?, removed: List<String>): List<QRow> {
    if (pending != null) {
        return (pending.queued + pending.next).filter { it.key !in removed }.mapIndexed { i, r -> r.copy(index = i) }
    }
    val seen = HashMap<String, Int>()
    fun key(item: SongPlayer.QueueItem): String {
        val count = seen.merge(item.uri, 1, Int::plus)!!
        return if (count == 1) item.uri else "${item.uri}~$count"
    }
    val upcoming = (queue.nextUp + (if (queue.autoplayEnabled) queue.recommendations else emptyList())).distinctBy { it.uid }
    val all = queue.queued.map { Triple(key(it), it, true) } + upcoming.map { Triple(key(it), it, false) }
    return all.filter { it.first !in removed }.mapIndexed { i, (k, item, queued) -> QRow(k, item, queued, i) }
}

/** The row being dragged by its handle: which one, and how far it is from its slot. */
private class QueueDrag {
    var key by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
}

/** Where the dragged row hovers: its flat index, the gap among the other rows, and which side of the context heading. */
private data class Hover(val from: Int, val slot: Int, val aboveBoundary: Boolean)

private const val HEADING_NEXT = "heading-next"

private fun hoverOf(listState: LazyListState, rows: List<QRow>, drag: QueueDrag): Hover? {
    val key = drag.key ?: return null
    val from = rows.indexOfFirst { it.key == key }
    if (from < 0) return null
    val visible = listState.layoutInfo.visibleItemsInfo.associateBy { it.key }
    val me = visible[key] ?: return null
    val center = me.offset + me.size / 2f + drag.offset
    // A row out of sight is above the dragged one's centre if it comes before it in the list.
    val mids = rows.filter { it.index != from }.map { r ->
        visible[r.key]?.let { it.offset + it.size / 2f } ?: if (r.index < from) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
    }
    val heading = visible[HEADING_NEXT]
    val firstNext = rows.firstOrNull { !it.queued }
    val queuedOthers = rows.count { it.queued && it.index != from }
    // With no queued row the heading is the queue's drop target: anywhere down to its foot counts.
    val boundary = heading?.let { if (queuedOthers == 0) (it.offset + it.size).toFloat() else it.offset + it.size / 2f }
        ?: firstNext?.let { visible[it.key]?.offset?.toFloat() }
    var slot = QueueDragMath.slot(center, mids)
    val above = boundary == null || center < boundary
    val dragged = rows[from]
    // A Spotify queued song cannot go back into "next from": it stays at the end of the queue.
    if (dragged.queued && dragged.item.provider != SongPlayer.LOCAL) slot = minOf(slot, queuedOthers)
    return Hover(from, slot, above || (dragged.queued && dragged.item.provider != SongPlayer.LOCAL))
}

/** What dropping [row] at [hover] does: the new queued and next rows, and the engine call that makes it so. */
private fun dropPlan(rows: List<QRow>, row: QRow, hover: Hover?): Triple<List<QRow>, List<QRow>, () -> Unit>? {
    hover ?: return null
    val oq = rows.filter { it.queued && it.key != row.key }
    val on = rows.filter { !it.queued && it.key != row.key }
    val local = row.item.provider == SongPlayer.LOCAL
    val place = QueueDragMath.held(QueueDragMath.place(hover.slot, oq.size, hover.aboveBoundary), oq.size, local, row.queued)
    val (q, n) = QueueDragMath.apply(oq, on, row.copy(queued = place.toQueue), place)
    if (q.map { it.key } == rows.filter { it.queued }.map { it.key } && n.map { it.key } == rows.filter { !it.queued }.map { it.key }) return null
    val into = if (place.toQueue) oq else on
    val (ti, after) = QueueDragMath.target(into.size, place.index) ?: (null to false)
    val target = ti?.let { into[it].item }
    val item = row.item
    val call: () -> Unit = when {
        place.toQueue && !row.queued ->
            if (target == null) ({ SongPlayer.moveToQueueFront(item) }) else ({ SongPlayer.moveIntoQueue(item, target, after) })
        target == null -> return null
        else -> ({ SongPlayer.reorder(item, target, after) })
    }
    return Triple(q, n, call)
}

/** One section's rows, each swiped away to remove it or dragged by its handle to move it. */
@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.queueSection(
    section: List<QRow>,
    all: List<QRow>,
    removed: SnapshotStateList<String>,
    drag: QueueDrag,
    hover: () -> Hover?,
    onDrop: (QRow) -> Unit,
) {
    items(section.size, key = { section[it].key }) { i ->
        val row = section[i]
        val key = row.key
        val item = row.item
        val rowPx = with(LocalDensity.current) { Immersive.RowHeight.toPx() }
        val dragged = key == drag.key
        // Rows the dragged one passes step aside by one slot.
        val shift = hover()?.let { h -> if (dragged) 0 else QueueDragMath.step(row.index, h.from, h.slot) } ?: 0
        val dropNow by rememberUpdatedState(onDrop)
        val rowNow by rememberUpdatedState(row)
        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                removed += key
                SongPlayer.removeFromQueue(item)
                true
            } else {
                false
            }
        })
        SwipeToDismissBox(
            state = dismiss,
            enableDismissFromStartToEnd = false,
            modifier = (if (dragged) Modifier else queueMotion())
                .zIndex(if (dragged) 1f else 0f)
                .graphicsLayer { translationY = if (dragged) drag.offset else shift * rowPx },
            backgroundContent = {
                // Drawn only while swiping: the row is transparent, so at rest this × showed
                // through behind the drag handle.
                if (dismiss.dismissDirection != SwipeToDismissBoxValue.Settled) {
                    Box(
                        Modifier.fillMaxSize().padding(end = 4.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.immersive_queue_remove),
                            tint = Immersive.TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            },
        ) {
            QueueRow(
                title = item.song.title,
                artist = item.song.singer,
                cover = item.song.coverUri,
                isCurrent = false,
                explicit = item.song.explicit,
                onClick = { SongPlayer.skipTo(item) },
                handle = {
                    Icon(
                        Icons.Rounded.DragHandle,
                        contentDescription = stringResource(R.string.immersive_queue_reorder),
                        tint = Immersive.TextSecondary,
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                            .pointerInput(key) {
                                detectDragGestures(
                                    onDragStart = { drag.key = key; drag.offset = 0f },
                                    onDragEnd = {
                                        // Worked out while the row is still where it was dropped.
                                        if (drag.key == key) dropNow(rowNow)
                                        drag.key = null
                                        drag.offset = 0f
                                    },
                                    onDragCancel = { drag.key = null; drag.offset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag.offset += amount.y
                                    },
                                )
                            },
                    )
                },
            )
        }
    }
}

@Composable
private fun QueueHeader(
    shuffle: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.immersive_queue_title),
            style = Immersive.Title,
            modifier = Modifier.weight(1f),
        )
        QueueToggle(Icons.Rounded.Shuffle, stringResource(R.string.immersive_queue_shuffle), shuffle, onShuffle)
        Spacer(Modifier.width(8.dp))
        QueueToggle(
            if (repeatMode == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            stringResource(R.string.immersive_queue_repeat),
            repeatMode != 0,
            onRepeat,
        )
    }
}

/** A round toggle: lit white when on, a quiet disc when off. */
@Composable
private fun QueueToggle(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Icon(icon, contentDescription = description, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun QueueHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = Immersive.BodyStrong,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun QueueRow(
    title: String,
    artist: String,
    cover: String,
    isCurrent: Boolean,
    explicit: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    handle: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Immersive.RowHeight)
            .clip(Immersive.CoverMedium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isCurrent,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlideImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(Immersive.CoverSmall)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = title,
                style = Immersive.Body,
                color = if (isCurrent) Immersive.Accent else Immersive.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (explicit) {
                    ExplicitBadge()
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = artist,
                    style = Immersive.Subhead,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (handle != null) handle()
        if (isCurrent) {
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = stringResource(R.string.immersive_queue_now_playing),
                tint = Immersive.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
