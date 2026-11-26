package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Performs one real request. Skipped unless -Decho.live=1 so CI and offline builds stay
 * deterministic; run with ./gradlew :ext:test -Decho.live=1 to verify the upstream.
 */
class LiveApiTest {

    @Test
    fun fetchesLyricsForAKnownTrack() = runBlocking {
        assumeTrue(System.getProperty("echo.live") == "1")
        val track = Track(
            id = "live-probe",
            title = "Never Gonna Give You Up",
            artists = listOf(Artist("1", "Rick Astley")),
            album = Album("1", "Whenever You Need Somebody"),
            duration = 213000L,
        )
        val source = RmmSource()
        val candidates = source.search(track, null)
        assertTrue("no candidates returned", candidates.isNotEmpty())
        val loaded = source.load(candidates.first(), null)
        assertNotNull(loaded.lyrics)
        val lyric = loaded.lyrics
        if (lyric is Lyrics.Timed) assertTrue(lyric.list.isNotEmpty())
        if (lyric is Lyrics.WordByWord) assertTrue(lyric.list.isNotEmpty())
        if (lyric is Lyrics.Simple) assertTrue(lyric.text.isNotBlank())
        println("live: ${loaded.title} -> ${lyric?.javaClass?.simpleName}")
    }
}
