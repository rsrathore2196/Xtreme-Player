package com.example

import android.app.Application
import com.example.data.repository.*
import com.example.ui.viewmodel.CatalogBrowserState
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogBackStackTest {
    @Test fun albumBackReturnsToTheArtistAndItsScrollInsteadOfHome() = runTest {
        val browser = CatalogBrowserState(CatalogRepository(), backgroundScope,
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        val artist = CatalogDestination.Artist(CatalogArtist("UC_artist", "Artist"))
        val album = CatalogDestination.Album(CatalogAlbum("MPRE_album", "Album", listOf(artist.artist), "", ""))
        browser.open(artist)
        browser.rememberScroll(6, 42)
        browser.open(album)
        assertEquals(album, browser.detail.value?.destination)
        browser.back()
        assertEquals(artist, browser.detail.value?.destination)
        assertEquals(6, browser.detail.value?.scrollIndex)
        assertEquals(42, browser.detail.value?.scrollOffset)
        browser.back()
        assertNull(browser.detail.value)
    }

    @Test fun leavingTheCatalogClearsItsOldParents() = runTest {
        val browser = CatalogBrowserState(CatalogRepository(), backgroundScope,
            preparationDispatcher = StandardTestDispatcher(testScheduler))
        browser.open(CatalogDestination.Artist(CatalogArtist("one", "One")))
        val second = CatalogDestination.Artist(CatalogArtist("two", "Two"))
        browser.open(second)
        browser.close()
        browser.open(second)
        browser.back()
        assertNull(browser.detail.value)
    }
}
