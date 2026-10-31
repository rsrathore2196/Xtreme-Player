package com.example

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.example.ui.components.ElasticBoundaryState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ElasticBoundaryRefreshTest {
    @Test fun deliberatePullTriggersExactlyOnceOnRelease() = runTest {
        var refreshes = 0
        val state = ElasticBoundaryState(backgroundScope, 135f, 80f, 80f, 60f) { refreshes++ }
        state.connection.onPostScroll(Offset.Zero, Offset(0f, 500f), NestedScrollSource.UserInput)
        assertEquals(0, refreshes)
        assertTrue(state.offset >= 80f)
        state.connection.onPreFling(Velocity.Zero)
        state.connection.onPreFling(Velocity.Zero)
        assertEquals(1, refreshes)
        state.updateRefreshing(true)
        state.connection.onPostScroll(Offset.Zero, Offset(0f, 500f), NestedScrollSource.UserInput)
        state.connection.onPreFling(Velocity.Zero)
        assertEquals(1, refreshes)
    }
    @Test fun smallPullsAndProgrammaticScrollingCannotTriggerRefresh() = runTest {
        var refreshes = 0
        val state = ElasticBoundaryState(backgroundScope, 135f, 80f, 80f, 60f) { refreshes++ }
        state.connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
        state.connection.onPreFling(Velocity.Zero)
        state.connection.onPostScroll(Offset.Zero, Offset(0f, 500f), NestedScrollSource.SideEffect)
        state.connection.onPreFling(Velocity.Zero)
        assertEquals(0, refreshes)
    }
}
