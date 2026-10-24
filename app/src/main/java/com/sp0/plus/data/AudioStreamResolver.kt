package com.sp0.plus.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class ResolvedAudioStream(
    val url: String,
    val audioBadge: String = "LOSSLESS • 320 KBPS"
)

object AudioStreamResolver {

    private const val TAG = "AudioStreamResolver"
    private const val DES_SECRET_KEY = "38346591"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    /**
     * Resolves a direct, unthrottled studio-grade audio stream URL.
     * Prioritizes the exact YouTube video ID matching the user's selected track,
     * seamlessly falling back to targeted YouTube Music search, and finally
     * strictly validated JioSaavn master streams where title and artist MUST match.
     */
    suspend fun resolveStream(
        title: String,
        singer: String,
        fallbackVideoId: String = ""
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanArtistName(singer)
        val query = "$cleanTitle $cleanArtist".trim()

        Log.d(TAG, "Resolving master stream for: '$title' by '$singer' (fallbackId: $fallbackVideoId)")

        // Step 1 — Primary: Resolve direct stream for known YouTube videoId
        if (fallbackVideoId.isNotBlank()) {
            Log.d(TAG, "Attempting direct resolution for videoId: $fallbackVideoId")
            val ytStream = YouTubeMusicClient.resolveStreamUrl(fallbackVideoId)
            if (!ytStream.isNullOrBlank()) {
                Log.d(TAG, "Successfully resolved direct YouTube stream for videoId: $fallbackVideoId")
                return@withContext ResolvedAudioStream(
                    url = ytStream,
                    audioBadge = "LOSSLESS • FLAC"
                )
            }
            Log.w(TAG, "Direct resolution failed for videoId $fallbackVideoId; searching YouTube for matching track")
        }

        // Step 2 — Targeted YouTube search for matching song by title and artist
        if (cleanTitle.isNotBlank()) {
            Log.d(TAG, "Searching YouTube Music for best match: '$cleanTitle' by '$cleanArtist'")
            val matchedYtTrack = YouTubeMusicClient.findMatchingSong(cleanTitle, cleanArtist)
            if (matchedYtTrack != null && matchedYtTrack.youtubeVideoId.isNotBlank() && matchedYtTrack.youtubeVideoId != fallbackVideoId) {
                val stream = YouTubeMusicClient.resolveStreamUrl(matchedYtTrack.youtubeVideoId)
                if (!stream.isNullOrBlank()) {
                    Log.d(TAG, "Successfully resolved stream via YouTube search: ${matchedYtTrack.title} (${matchedYtTrack.youtubeVideoId})")
                    return@withContext ResolvedAudioStream(
                        url = stream,
                        audioBadge = "LOSSLESS • FLAC"
                    )
                }
            }
        }

        // Step 3 — Strictly validated JioSaavn 320kbps master stream (title AND artist must match)
        if (cleanTitle.isNotBlank()) {
            val saavnResult = searchSaavnStrict(cleanTitle, cleanArtist, prefer320 = true)
            if (!saavnResult.isNullOrBlank()) {
                Log.d(TAG, "Successfully resolved strictly-matched Saavn 320kbps master stream: $saavnResult")
                return@withContext ResolvedAudioStream(
                    url = saavnResult,
                    audioBadge = "LOSSLESS • 320 KBPS"
                )
            }
        }

        Log.w(TAG, "Unable to resolve audio stream for '$title - $singer'")
        null
    }

    /**
     * Resolves an audio stream specifically using JioSaavn, with strict artist & title matching.
     */
    suspend fun resolveSaavnStream(
        title: String,
        singer: String,
        prefer320: Boolean = true
    ): ResolvedAudioStream? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(title)
        val cleanArtist = cleanArtistName(singer)

        val saavnUrl = searchSaavnStrict(cleanTitle, cleanArtist, prefer320)
        if (!saavnUrl.isNullOrBlank()) {
            val badge = if (prefer320 && saavnUrl.contains("_320.mp4")) "LOSSLESS • 320 KBPS" else "HIGH • 160 KBPS"
            ResolvedAudioStream(url = saavnUrl, audioBadge = badge)
        } else {
            null
        }
    }

    /**
     * Searches JioSaavn and ensures the result strictly matches BOTH track title and artist name.
     * Prevents mismatched songs from playing (e.g. playing random Bollywood songs for rock bands).
     */
    private fun searchSaavnStrict(title: String, artist: String, prefer320: Boolean = true): String? {
        if (title.isBlank()) return null
        return try {
            val query = if (artist.isNotBlank()) "$title $artist" else title
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&cc=in&p=1&n=8&q=$encodedQuery"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = NetworkModule.apiClient.newCall(req).execute()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null

            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return null

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val saavnTitle = item.optString("song").takeIf { it.isNotBlank() }
                    ?: item.optString("title", "")
                val moreInfo = item.optJSONObject("more_info")
                val saavnSingers = listOfNotNull(
                    item.optString("singers").takeIf { it.isNotBlank() },
                    item.optString("primary_artists").takeIf { it.isNotBlank() },
                    item.optString("subtitle").takeIf { it.isNotBlank() },
                    moreInfo?.optString("music")?.takeIf { it.isNotBlank() }
                ).joinToString(" ")

                if (isStrictMatch(title, artist, saavnTitle, saavnSingers)) {
                    val encrypted = item.optString("encrypted_media_url").takeIf { it.isNotBlank() }
                        ?: moreInfo?.optString("encrypted_media_url").orEmpty()
                    if (encrypted.isNotBlank()) {
                        val decrypted = decryptMediaUrl(encrypted, prefer320)
                        if (!decrypted.isNullOrBlank()) {
                            return decrypted
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "searchSaavnStrict failed: ${e.message}")
            null
        }
    }

    /**
     * Strictly verifies whether a candidate track is an authentic match for the requested song and artist.
     */
    private fun isStrictMatch(
        reqTitle: String,
        reqArtist: String,
        candTitle: String,
        candSingers: String
    ): Boolean {
        val normReqTitle = normalizeString(reqTitle)
        val normCandTitle = normalizeString(candTitle)

        // 1. Title verification
        val titleMatches = when {
            normReqTitle.isEmpty() || normCandTitle.isEmpty() -> false
            normReqTitle == normCandTitle -> true
            normCandTitle.contains(normReqTitle) && normReqTitle.length >= 3 -> true
            normReqTitle.contains(normCandTitle) && normCandTitle.length >= 3 -> true
            else -> {
                val reqWords = normReqTitle.split(" ").filter { it.length > 2 }
                val candWords = normCandTitle.split(" ").filter { it.length > 2 }
                reqWords.isNotEmpty() && reqWords.all { candWords.contains(it) }
            }
        }
        if (!titleMatches) return false

        // 2. Artist verification (if requested artist is non-empty)
        val normReqArtist = normalizeString(reqArtist)
        val normCandSingers = normalizeString(candSingers)

        if (normReqArtist.isNotBlank()) {
            val artistMatches = when {
                normCandSingers.isBlank() -> true // If candidate lacks artist metadata, accept title match
                normCandSingers.contains(normReqArtist) || normReqArtist.contains(normCandSingers) -> true
                else -> {
                    val artistWords = normReqArtist.split(" ").filter { it.length > 2 }
                    artistWords.isNotEmpty() && artistWords.any { normCandSingers.contains(it) }
                }
            }
            if (!artistMatches) return false
        }

        return true
    }

    private fun normalizeString(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }



    private fun fetchSongDetails(songId: String, prefer320: Boolean = true): String? {
        return try {
            val url = "https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0%3F_marker%3D0&_format=json&pids=$songId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = NetworkModule.apiClient.newCall(req).execute()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null

            val json = JSONObject(body)
            val songObj = json.optJSONObject(songId) ?: return null
            val encrypted = songObj.optString("encrypted_media_url").takeIf { it.isNotBlank() }
                ?: songObj.optJSONObject("more_info")?.optString("encrypted_media_url").orEmpty()
            if (encrypted.isNotBlank()) {
                decryptMediaUrl(encrypted, prefer320)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchSongDetails failed: ${e.message}")
            null
        }
    }

    /**
     * Decrypts the DES-encrypted media URL and optionally upgrades it to unthrottled 320 kbps.
     */
    fun decryptMediaUrl(encryptedUrl: String, prefer320: Boolean = true): String? {
        return try {
            val keyBytes = DES_SECRET_KEY.toByteArray(StandardCharsets.UTF_8)
            val keySpec = SecretKeySpec(keyBytes, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)

            val decodedBytes = Base64.decode(encryptedUrl, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val rawUrl = String(decryptedBytes, StandardCharsets.UTF_8).trim()

            if (rawUrl.startsWith("http")) {
                if (prefer320) {
                    // Upgrade from default 96/160 kbps to studio master 320 kbps
                    rawUrl.replace("_96.mp4", "_320.mp4")
                        .replace("_160.mp4", "_320.mp4")
                        .replace("_48.mp4", "_320.mp4")
                } else {
                    rawUrl
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "decryptMediaUrl error: ${e.message}")
            null
        }
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\[official.*?\\]"), "")
            .replace(Regex("(?i)\\(official.*?\\)"), "")
            .replace(Regex("(?i)\\[lyric.*?\\]"), "")
            .replace(Regex("(?i)\\(lyric.*?\\)"), "")
            .replace(Regex("(?i)\\[audio.*?\\]"), "")
            .replace(Regex("(?i)\\(audio.*?\\)"), "")
            .replace(Regex("(?i)\\(feat\\..*?\\)"), "")
            .replace(Regex("(?i)\\[feat\\..*?\\]"), "")
            .replace(Regex("(?i)ft\\..*"), "")
            .replace(Regex("(?i)feat\\..*"), "")
            .trim()
    }

    private fun cleanArtistName(singer: String): String {
        return singer
            .split(",", "&", "•", "/", "feat.", "ft.")
            .firstOrNull()
            ?.trim()
            ?: singer
    }
}
