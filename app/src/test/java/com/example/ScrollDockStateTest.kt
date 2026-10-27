package com.example

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.example.ui.components.ScrollDockState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScrollDockStateTest {
    private val state = ScrollDockState(50f)
    private fun scroll(y: Float, x: Float = 0f, source: NestedScrollSource = NestedScrollSource.UserInput) =
        state.onPostScroll(Offset(x, y), Offset.Zero, source)

    @Test fun scrollDirectionNeedsThresholdAndDoesNotConsumeMotion() {
        assertEquals(Offset.Zero, scroll(-25f))
        assertFalse(state.isCompact)
        scroll(-25f)
        assertTrue(state.isCompact)
        scroll(49f)
        assertTrue(state.isCompact)
        scroll(1f)
        assertFalse(state.isCompact)
    }

    @Test fun smallDirectionReversalsDoNotChatter() {
        repeat(20) { scroll(-20f); scroll(20f) }
        assertFalse(state.isCompact)
        scroll(-50f)
        repeat(20) { scroll(20f); scroll(-20f) }
        assertTrue(state.isCompact)
    }

    @Test fun horizontalShelvesOverscrollAndProgrammaticRestoresCannotCollapseDock() {
        scroll(-300f, x = 400f)
        state.onPostScroll(Offset.Zero, Offset(0f, -500f), NestedScrollSource.UserInput)
        scroll(-500f, source = NestedScrollSource.SideEffect)
        assertFalse(state.isCompact)
    }

    @Test fun userFlingCompletesShortDragButLaterProgrammaticMovementDoesNot() = runBlocking {
        scroll(-20f)
        assertEquals(Velocity.Zero, state.onPreFling(Velocity(0f, -900f)))
        scroll(-40f, source = NestedScrollSource.SideEffect)
        assertTrue(state.isCompact)
        assertEquals(Velocity.Zero, state.onPostFling(Velocity(0f, -900f), Velocity.Zero))
        scroll(500f, source = NestedScrollSource.SideEffect)
        assertTrue(state.isCompact)
    }

    @Test fun newPageOrHiddenControlsResetAccumulationAndFling() = runBlocking {
        scroll(-50f)
        state.onPreFling(Velocity(0f, -900f))
        state.enabled = false
        assertFalse(state.isCompact)
        scroll(-500f)
        state.enabled = true
        scroll(-500f, source = NestedScrollSource.SideEffect)
        scroll(-49f)
        assertFalse(state.isCompact)
        scroll(-1f)
        assertTrue(state.isCompact)
        state.expand()
        assertFalse(state.isCompact)
    }
}
