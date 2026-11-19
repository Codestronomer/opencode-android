package ai.opencode.core.ui

import android.os.Build
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * Predictive back on Android 12 (< 33), where the system sends apps no back-progress
 * events: while [enabled], the edge strips claim the gesture area
 * (`Modifier.systemGestureExclusion`, which the system clamps to its per-side budget) so
 * the app receives the raw swipe, and [BackSwipeRules] score it into
 * [onStarted]/[onProgressed]/[onCancelled]/[onInvoked] — the same callbacks the 33+
 * system events drive. Outside the claimed strips the system gesture still runs, landing
 * in the surface's dismiss-only back handler, so behaviour degrades to Android 13's
 * there. On 33+ this only hosts [content]; the system produces the events.
 *
 * The commit distance is a third of the screen's width, an approximation of the system's
 * threshold: a swipe commits past it or on a fling ([BackSwipeRules.goesBack]); otherwise
 * it cancels.
 */
@Composable
fun PredictiveBackSynthesizer(
    enabled: Boolean,
    onStarted: () -> Unit,
    onProgressed: (progress: Float, fromRightEdge: Boolean) -> Unit,
    onCancelled: () -> Unit,
    onInvoked: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        content()
        if (enabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(BackSwipeRules.edgeWidth)
                    .fillMaxHeight()
                    .systemGestureExclusion(),
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(BackSwipeRules.edgeWidth)
                    .fillMaxHeight()
                    .systemGestureExclusion(),
            )
        }
    }
    if (enabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        EdgeSwipes(
            onStarted = onStarted,
            onProgressed = onProgressed,
            onCancelled = onCancelled,
            onInvoked = onInvoked,
        )
    }
}

/** Scores inward horizontal swipes from the screen edges by [BackSwipeRules]. */
@Composable
private fun EdgeSwipes(
    onStarted: () -> Unit,
    onProgressed: (progress: Float, fromRightEdge: Boolean) -> Unit,
    onCancelled: () -> Unit,
    onInvoked: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val edgeWidth = BackSwipeRules.edgeWidth.toPx()
                val decisionDistance = BackSwipeRules.decisionDistance.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    val fromRightEdge = down.position.x > width - edgeWidth
                    val fromLeftEdge = down.position.x < edgeWidth
                    if (!fromLeftEdge && !fromRightEdge) return@awaitEachGesture
                    // The score is always rightward-inward: mirror the right edge.
                    val inwardSign = if (fromRightEdge) -1f else 1f
                    var started = false
                    val tracker = VelocityTracker()
                    var offset = Offset.Zero
                    tracker.addPosition(down.uptimeMillis, down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.lifted()) {
                            if (started) {
                                val velocity = tracker.calculateVelocity().x * inwardSign
                                if (BackSwipeRules.goesBack(offset.x * inwardSign, width, velocity)) onInvoked()
                                else onCancelled()
                            }
                            break
                        }
                        val decision = BackSwipeRules.decision(
                            dx = offset.x * inwardSign,
                            dy = offset.y,
                            decisionDistancePx = decisionDistance,
                            atEdge = true,
                            claimed = true,
                            heldForSeconds = 0.0,
                            holdBegan = false,
                        )
                        if (decision == BackSwipeRules.Decision.Begin && !started) {
                            started = true
                            onStarted()
                        }
                        if (started) {
                            onProgressed(
                                (offset.x * inwardSign / (width / 3f)).coerceIn(0f, 1f),
                                fromRightEdge,
                            )
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                    }
                }
            },
    )
}

private fun PointerInputChange.lifted() = !pressed && previousPressed
