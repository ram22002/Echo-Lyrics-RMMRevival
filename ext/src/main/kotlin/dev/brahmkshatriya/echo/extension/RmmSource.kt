package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.JsonObject

/**
 * RMM Revival (lyrics.rmmreviv.al). Candidates come from the keyless iTunes Search API;
 * lyrics are fetched by Apple track id and can be TTML, per-line JSON, LRC or plain text.
 */
internal class RmmSource : LyricsSource {

    private val throttle = Throttle(400)

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank() || query.artist.isBlank()) return emptyList()
        throttle.await()
        val term = Http.encode(track.title + " " + query.artist)
        val body = runCatching {
            Http.get("https://itunes.apple.com/search?term=$term&entity=song&limit=5")
        }.getOrNull() ?: return emptyList()
        val results = runCatching {
            Http.json(body).obj()?.arr("results").objects()
        }.getOrNull() ?: return emptyList()

        return results.mapNotNull { item ->
            val trackId = item.long("trackId") ?: return@mapNotNull null
            val title = item.str("trackName") ?: return@mapNotNull null
            val artist = item.str("artistName").orEmpty()
            val album = item.str("collectionName").orEmpty()
            val duration = (item.long("trackTimeMillis") ?: 0L) / 1000
            Triple(trackId, Candidate(title, artist, album, duration), item)
        }.filter { Match.accept(query, it.second) }
            .sortedByDescending { Match.score(query, it.second) }
            .take(5)
            .map { (trackId, candidate, _) ->
                Lyrics(
                    id = "rmm:$trackId",
                    title = candidate.title,
                    subtitle = candidate.artist + " • RMM Revival",
                    lyrics = null,
                    extras = mapOf("id" to trackId.toString()),
                )
            }
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val id = lyrics.extras["id"] ?: throw LyricsException("RMM candidate is missing its Apple track id")
        val base = settings?.getString(KEY_ENDPOINT)?.trim().orEmpty().ifEmpty { BASE_URL }
        throttle.await()
        val body = try {
            Http.get(base + "?id=" + Http.encode(id))
        } catch (error: HttpException) {
            if (error.code == 404) throw LyricsException("RMM Revival has no lyrics for this track")
            throw error
        }
        val root = Http.json(body).obj() ?: throw LyricsException("Empty RMM response")
        if (root.bool("hasLyrics") != true) throw LyricsException("RMM Revival reports no lyrics for this track")
        val payload = payload(root) ?: throw LyricsException("RMM Revival returned no usable lyrics")
        return lyrics.copy(lyrics = payload)
    }

    private fun payload(root: JsonObject): Lyrics.Lyric? {
        val ttml = root.str("ttml")
        if (!ttml.isNullOrBlank()) {
            val lines = Ttml.parse(ttml)
            if (lines.isNotEmpty()) return timed(lines, lines.any { it.words.size > 1 })
        }
        val lineLyrics = root.arr("lineLyrics").objects()
        if (lineLyrics.isNotEmpty()) {
            val lines = lineLyrics.mapNotNull { item ->
                val text = item.str("text") ?: return@mapNotNull null
                val seconds = item.dbl("time") ?: return@mapNotNull null
                Line((seconds * 1000).toLong(), (seconds * 1000).toLong(), text)
            }
            val finalLines = Lrc.withEnds(lines.sortedBy { it.startMs }, 0L)
            if (finalLines.isNotEmpty()) return timed(finalLines, false)
        }
        val synced = root.str("syncedLyrics")
        if (!synced.isNullOrBlank()) {
            val lines = Lrc.parse(synced)
            if (lines.isNotEmpty()) return timed(lines, lines.any { it.words.size > 1 })
        }
        val plainText = root.str("lyrics")
        if (!plainText.isNullOrBlank()) {
            val lines = Lrc.parsePlain(plainText)
            if (lines.isNotEmpty()) return plain(lines.joinToString("\n"))
        }
        return null
    }

    companion object {
        const val KEY_ENDPOINT = "endpoint"
        private const val BASE_URL = "https://lyrics.rmmreviv.al/lyrics"

        fun settings(): List<Setting> = listOf(
            SettingTextInput(
                title = "API endpoint",
                key = KEY_ENDPOINT,
                summary = "Leave blank for https://lyrics.rmmreviv.al/lyrics",
                defaultValue = "",
            ),
        )
    }
}
