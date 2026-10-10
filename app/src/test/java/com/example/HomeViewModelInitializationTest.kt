package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelInitializationTest {

    private val testDispatcher = StandardTestDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testHomeViewModelInitializationDoesNotThrowAndRefreshErrorMessageIsInitiallyNull() {
        val app = ApplicationProvider.getApplicationContext<XtremeMusicApp>()

        // Construct HomeViewModel with controlled dependencies and dispatchers
        val viewModel = HomeViewModel(
            repository = app.repository,
            playbackManager = app.playbackManager,
            application = app
        )

        // Verify initialization does not throw and refreshErrorMessage is accessible and initially null
        assertNotNull(viewModel)
        assertNull("refreshErrorMessage must be accessible and initially null", viewModel.refreshErrorMessage.value)
    }
}
