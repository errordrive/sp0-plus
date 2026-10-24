package com.sp0.plus.data

import com.sp0.plus.model.SongsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal InnerTube (music.youtube.com) client.
 *
 * Flow: visitor id (X-Goog-Visitor-Id) -> search -> /player stream URL -> ExoPlayer.
 * Every request carries the visitor id so YouTube treats the session as one consistent visitor.
 */
object YouTubeMusicClient {

    private const val BASE = "https://music.youtube.com/youtubei/v1"
    private const val ORIGIN = "https://music.youtube.com"
    private const val WEB_REMIX_VERSION = "1.20241127.01.00"
    private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

    private class PlayerClient(
        val name: String,
        val version: String,
        val userAgent: String,
        val extra: JSONObject.() -> Unit = {}
    )

    // Clients that return direct (un-ciphered) stream URLs, tried in order.
    @Volatile private var cachedCookies: String? = null
    @Volatile private var cachedSts: Int = 20746
    @Volatile private var cachedVisitorData: String? = null
    @Volatile private var lastSessionTimestamp: Long = 0L

    private fun refreshSession(videoId: String = ""): Boolean {
        val vid = videoId.ifBlank { "G1XrlgTOTjo" }
        val url = "https://www.youtube.com/watch?v=$vid&bpctr=9999999999&has_verified=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkModule.UA_WEB)
            .header("Cookie", "PREF=hl=en&tz=UTC; SOCS=CAI")
            .build()

        return try {
            NetworkModule.apiClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false

                val setCookies = resp.headers("Set-Cookie")
                val cookieMap = mutableMapOf<String, String>()
                cookieMap["PREF"] = "hl=en&tz=UTC"
                cookieMap["SOCS"] = "CAI"
                for (sc in setCookies) {
                    val pair = sc.substringBefore(";").trim()
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        cookieMap[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
                    }
                }
                cachedCookies = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

                val html = resp.body?.string().orEmpty()
                val stsMatch = Regex("\"STS\":\\s*(\\d+)").find(html)
                if (stsMatch != null) {
                    cachedSts = stsMatch.groupValues[1].toIntOrNull() ?: 20746
                }
                val visMatch = Regex("\"VISITOR_DATA\":\\s*\"([^\"]+)\"").find(html)
                if (visMatch != null) {
                    val v = visMatch.groupValues[1]
                    cachedVisitorData = v
                    SettingsManager.setVisitorData(v)
                }
                lastSessionTimestamp = System.currentTimeMillis()
                true
            }
        } catch (e: Throwable) {
            android.util.Log.w("YouTubeMusicClient", "refreshSession failed: ${e.message}")
            false
        }
    }

    private fun resolveVisionOsStreamUrl(videoId: String): String? {
        if (cachedCookies == null || (System.currentTimeMillis() - lastSessionTimestamp > 6 * 3600 * 1000L)) {
            refreshSession(videoId)
        }

        fun executeCall(): Pair<String?, String?> {
            val clientObj = JSONObject().apply {
                put("clientName", "VISIONOS")
                put("clientVersion", "1.02")
                put("deviceMake", "Apple")
                put("deviceModel", "RealityDevice17,1")
                put("userAgent", NetworkModule.UA_VISIONOS)
                put("osName", "visionOS")
                put("osVersion", "26.5.23O471")
                put("hl", "en")
                put("timeZone", "UTC")
                put("utcOffsetMinutes", 0)
            }
            val playbackContext = JSONObject().apply {
                put("contentPlaybackContext", JSONObject().apply {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("signatureTimestamp", cachedSts)
                })
            }
            val body = JSONObject().apply {
                put("context", JSONObject().put("client", clientObj))
                put("videoId", videoId)
                put("playbackContext", playbackContext)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val reqBuilder = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(body.toString().toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", NetworkModule.UA_VISIONOS)
                .header("X-Youtube-Client-Name", "101")
                .header("X-Youtube-Client-Version", "1.02")
                .header("Origin", "https://www.youtube.com")

            cachedCookies?.let { reqBuilder.header("Cookie", it) }
            cachedVisitorData?.let { reqBuilder.header("X-Goog-Visitor-Id", it) }

            try {
                NetworkModule.apiClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return Pair(null, "HTTP ${resp.code}")
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val status = json.optJSONObject("playabilityStatus")?.optString("status")
                    if (status != "OK") {
                        return Pair(null, status)
                    }
                    val url = pickAudioUrl(json)
                    return Pair(url, null)
                }
            } catch (e: Throwable) {
                return Pair(null, e.message)
            }
        }

        var (url, err) = executeCall()
        if (url == null) {
            android.util.Log.w("YouTubeMusicClient", "VisionOS call failed ($err), refreshing session...")
            if (refreshSession(videoId)) {
                url = executeCall().first
            }
        }
        return url
    }

    // iOS and iPadOS clients provide reliable, unthrottled, unciphered audio stream URLs.
    private val playerClients = listOf(
        PlayerClient(
            "IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        ) {
            put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2")
            put("osName", "iPhone"); put("osVersion", "18.3.2.22D82")
        },
        PlayerClient(
            "IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPad14,3; U; CPU OS 18_3_2 like Mac OS X;)"
        ) {
            put("deviceMake", "Apple"); put("deviceModel", "iPad14,3")
            put("osName", "iPadOS"); put("osVersion", "18.3.2.22D82")
        },
        PlayerClient(
            "IOS", "19.49.5",
            "com.google.ios.youtube/19.49.5 (iPhone15,3; U; CPU iOS 17_6_1 like Mac OS X;)"
        ) {
            put("deviceMake", "Apple"); put("deviceModel", "iPhone15,3")
            put("osName", "iPhone"); put("osVersion", "17.6.1.21G93")
        }
    )

    /** Fetches a fresh anonymous visitor id from YouTube Music using visitor_id or browse endpoints. */
    suspend fun fetchVisitorData(): String? = withContext(Dispatchers.IO) {
        // Attempt 1: Direct visitor_id endpoint
        try {
            val body = JSONObject().put("context", webContext(null))
            val req = Request.Builder()
                .url("$BASE/visitor_id?prettyPrint=false")
                .post(body.toString().toRequestBody(JSON))
                .header("User-Agent", NetworkModule.UA_WEB)
                .header("Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
                .build()

            NetworkModule.apiClient.newCall(req).execute().use { resp ->
                val hdr = resp.header("X-Goog-Visitor-Id")?.takeIf { it.isNotBlank() }
                if (hdr != null) return@withContext hdr

                val bodyStr = resp.body?.string().orEmpty()
                if (bodyStr.isNotBlank()) {
                    val json = JSONObject(bodyStr)
                    val visitor = json.optJSONObject("responseContext")?.optString("visitorData")
                    if (!visitor.isNullOrBlank()) return@withContext visitor
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("YouTubeMusicClient", "visitor_id fetch failed: ${e.message}")
        }

        // Attempt 2: Browse endpoint
        try {
            val body = JSONObject().put("context", webContext(null)).put("browseId", "FEmusic_home")
            val req = Request.Builder()
                .url("$BASE/browse?prettyPrint=false")
                .post(body.toString().toRequestBody(JSON))
                .header("User-Agent", NetworkModule.UA_WEB)
                .header("Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
                .build()

            NetworkModule.apiClient.newCall(req).execute().use { resp ->
                val hdr = resp.header("X-Goog-Visitor-Id")?.takeIf { it.isNotBlank() }
                if (hdr != null) return@withContext hdr

                val bodyStr = resp.body?.string().orEmpty()
                if (bodyStr.isNotBlank()) {
                    val json = JSONObject(bodyStr)
                    val visitor = json.optJSONObject("responseContext")?.optString("visitorData")
                    if (!visitor.isNullOrBlank()) return@withContext visitor
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("YouTubeMusicClient", "browse visitor fetch failed: ${e.message}")
        }
        null
    }

    /** Returns the saved visitor id, generating and saving one if none is set. */
    suspend fun ensureVisitorData(): String {
        val saved = SettingsManager.visitorData.value
        if (saved.isNotBlank()) return saved
        val fresh = fetchVisitorData().orEmpty()
        if (fresh.isNotBlank()) SettingsManager.setVisitorData(fresh)
        return fresh
    }

    suspend fun searchSongs(query: String): List<SongsModel> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val visitor = try { ensureVisitorData() } catch (_: Throwable) { "" }
            val body = JSONObject()
                .put("context", webContext(visitor))
                .put("query", query.trim())
                .put("params", java.net.URLDecoder.decode(SONGS_FILTER, "UTF-8"))
            val json = post("search", body, visitor, NetworkModule.UA_WEB)
            parseSearch(json)
        } catch (e: Throwable) {
            android.util.Log.w("YouTubeMusicClient", "searchSongs failed for '$query': ${e.message}")
            emptyList()
        }
    }

    /**
     * Finds the closest matching track for a specific title and artist.
     */
    suspend fun findMatchingSong(title: String, artist: String): SongsModel? = withContext(Dispatchers.IO) {
        val cleanT = title.trim().lowercase()
        val cleanA = artist.trim().lowercase()
        val query = "$title $artist".trim()
        val results = searchSongs(query)
        if (results.isEmpty()) return@withContext null

        // 1. Exact title + artist match
        val exact = results.firstOrNull { r ->
            val rt = r.title.lowercase().trim()
            val ra = r.singer.lowercase().trim()
            (rt == cleanT || rt.contains(cleanT) || cleanT.contains(rt)) &&
            (cleanA.isBlank() || ra.contains(cleanA) || cleanA.contains(ra))
        }
        if (exact != null) return@withContext exact

        // 2. Exact title match
        val titleMatch = results.firstOrNull { r ->
            val rt = r.title.lowercase().trim()
            rt == cleanT || (cleanT.length > 3 && rt.contains(cleanT))
        }
        if (titleMatch != null) return@withContext titleMatch

        // 3. Fallback to first result if artist or title has significant token overlap
        results.firstOrNull { r ->
            val words = cleanT.split(" ").filter { it.length > 2 }
            words.isNotEmpty() && words.any { r.title.lowercase().contains(it) }
        } ?: results.firstOrNull()
    }

    /**
     * Fetches radio / recommended tracks related to the specified track [videoId].
     * Uses YouTube Music's native 'RDAMVM' endless Radio context matching user interest.
     */
    suspend fun fetchRadioRecommendations(videoId: String, title: String = "", artist: String = ""): List<SongsModel> = withContext(Dispatchers.IO) {
        if (videoId.isBlank() && title.isBlank()) return@withContext emptyList()
        val visitor = try { ensureVisitorData() } catch (_: Throwable) { "" }

        // Method 1: YouTube Music /next with RDAMVM + videoId
        if (videoId.isNotBlank()) {
            try {
                val body = JSONObject()
                    .put("context", webContext(visitor))
                    .put("videoId", videoId)
                    .put("playlistId", "RDAMVM$videoId")
                val json = post("next", body, visitor, NetworkModule.UA_WEB)
                val recommendations = parseRadioContents(json, currentVideoId = videoId)
                if (recommendations.isNotEmpty()) {
                    return@withContext recommendations
                }
            } catch (e: Throwable) {
                android.util.Log.w("YouTubeMusicClient", "RDAMVM radio fetch failed: ${e.message}")
            }
        }

        // Method 2: Fallback to targeted search for same artist or title
        try {
            val query = if (artist.isNotBlank()) "$artist music" else "$title music"
            val searchResults = searchSongs(query).filter { it.youtubeVideoId != videoId }
            if (searchResults.isNotEmpty()) {
                return@withContext searchResults
            }
        } catch (_: Throwable) {}

        emptyList()
    }

    private fun parseRadioContents(root: JSONObject, currentVideoId: String): List<SongsModel> {
        val out = mutableListOf<SongsModel>()
        try {
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs") ?: return out

            val upNext = tabs.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
            val items = upNext?.optJSONObject("musicQueueRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents") ?: return out

            for (i in 0 until items.length()) {
                val r = items.optJSONObject(i)?.optJSONObject("playlistPanelVideoRenderer") ?: continue
                val vId = r.optString("videoId")
                if (vId.isBlank() || vId == currentVideoId) continue

                val tRuns = r.optJSONObject("title")?.optJSONArray("runs")
                val title = tRuns?.optJSONObject(0)?.optString("text").orEmpty()
                if (title.isBlank()) continue

                val bRuns = r.optJSONObject("shortBylineText")?.optJSONArray("runs")
                val artist = bRuns?.optJSONObject(0)?.optString("text").orEmpty()
                val album = if (bRuns != null && bRuns.length() > 2) bRuns.optJSONObject(2)?.optString("text").orEmpty() else ""

                val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val cover = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                    .replace(Regex("=w\\d+-h\\d+.*$"), "=w600-h600")

                out.add(
                    SongsModel(
                        id = vId,
                        title = title,
                        album = album,
                        singer = artist,
                        coverUri = cover,
                        url = "",
                        youtubeVideoId = vId,
                        durationMs = 0L,
                        audioFormat = "LOSSLESS • FLAC",
                        isLiked = false
                    )
                )
            }
        } catch (e: Throwable) {
            android.util.Log.w("YouTubeMusicClient", "parseRadioContents error: ${e.message}")
        }
        return out
    }

    /** Fetches real-time trending global music tracks. */
    suspend fun fetchTrending(): List<SongsModel> = withContext(Dispatchers.IO) {
        val results = searchSongs("Trending Top Hits 2024")
        if (results.isNotEmpty()) results else searchSongs("Top 50 Global")
    }

    /** Fetches lossless / master quality hits. */
    suspend fun fetchLosslessHits(): List<SongsModel> = withContext(Dispatchers.IO) {
        val results = searchSongs("Global Lossless Master Hits")
        if (results.isNotEmpty()) results else searchSongs("Audiophile Master Hits")
    }

    /** Fetches chill and lofi tracks for relaxing playlists. */
    suspend fun fetchChillHits(): List<SongsModel> = withContext(Dispatchers.IO) {
        val results = searchSongs("Lo-Fi Chill Beats 2024")
        if (results.isNotEmpty()) results else searchSongs("Late Night Chill Lo-Fi")
    }

    /** Resolves a direct audio URL for [videoId], or null if every client fails. */
    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        // Priority 1: VisionOS 2-step direct unciphered audio stream
        try {
            val vStream = resolveVisionOsStreamUrl(videoId)
            if (!vStream.isNullOrBlank()) {
                return@withContext vStream
            }
        } catch (e: Throwable) {
            android.util.Log.w("YouTubeMusicClient", "resolveVisionOsStreamUrl failed: ${e.message}")
        }

        // Priority 2: Fallback player clients
        var visitor = try { ensureVisitorData() } catch (_: Throwable) { "" }
        if (visitor.isBlank()) {
            visitor = fetchVisitorData().orEmpty()
            if (visitor.isNotBlank()) SettingsManager.setVisitorData(visitor)
        }
        var refreshedVisitor = false

        for (client in playerClients) {
            try {
                fun buildContext(v: String): JSONObject = JSONObject().put(
                    "client",
                    JSONObject().apply {
                        put("clientName", client.name)
                        put("clientVersion", client.version)
                        put("hl", "en")
                        put("gl", "US")
                        if (v.isNotBlank()) put("visitorData", v)
                        client.extra.invoke(this)
                    }
                )

                var body = JSONObject()
                    .put("context", buildContext(visitor))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)

                var json = post("player", body, visitor, client.userAgent)
                var status = json.optJSONObject("playabilityStatus")?.optString("status")

                if (status == "LOGIN_REQUIRED" && !refreshedVisitor) {
                    refreshedVisitor = true
                    val fresh = fetchVisitorData().orEmpty()
                    if (fresh.isNotBlank()) {
                        visitor = fresh
                        SettingsManager.setVisitorData(fresh)
                        body = JSONObject()
                            .put("context", buildContext(visitor))
                            .put("videoId", videoId)
                            .put("contentCheckOk", true)
                            .put("racyCheckOk", true)
                        json = post("player", body, visitor, client.userAgent)
                        status = json.optJSONObject("playabilityStatus")?.optString("status")
                    }
                }

                if (status != "OK") continue
                val url = pickAudioUrl(json)
                if (url != null) return@withContext url
            } catch (e: Throwable) {
                android.util.Log.w("YouTubeMusicClient", "resolveStreamUrl client ${client.name} error: ${e.message}")
            }
        }
        null
    }

    // ---------------------------------------------------------------- internals

    private fun webContext(visitor: String?): JSONObject = JSONObject().put(
        "client",
        JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", WEB_REMIX_VERSION)
            put("hl", "en")
            put("gl", "US")
            if (!visitor.isNullOrBlank()) put("visitorData", visitor)
        }
    )

    private fun post(path: String, body: JSONObject, visitorId: String?, userAgent: String): JSONObject {
        val builder = Request.Builder()
            .url("$BASE/$path?prettyPrint=false")
            .post(body.toString().toRequestBody(JSON))
            .header("User-Agent", userAgent)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Origin", ORIGIN)
        if (!visitorId.isNullOrBlank()) builder.header("X-Goog-Visitor-Id", visitorId)

        NetworkModule.apiClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} on $path")
            return JSONObject(resp.body?.string().orEmpty())
        }
    }

    private fun pickAudioUrl(player: JSONObject): String? {
        val streaming = player.optJSONObject("streamingData") ?: return null
        val formats = JSONArray().also { arr ->
            streaming.optJSONArray("adaptiveFormats")?.let { for (i in 0 until it.length()) arr.put(it.get(i)) }
            streaming.optJSONArray("formats")?.let { for (i in 0 until it.length()) arr.put(it.get(i)) }
        }
        var best: JSONObject? = null
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio/") || !f.has("url")) continue
            val itag = f.optInt("itag")
            // Prefer AAC/MP4 itag 140 (widest ExoPlayer native support), then highest bitrate.
            val score = f.optInt("bitrate") + (if (itag == 140) 10_000_000 else if (mime.startsWith("audio/mp4")) 2_000_000 else 0)
            val bestScore = best?.let {
                val bItag = it.optInt("itag")
                it.optInt("bitrate") + (if (bItag == 140) 10_000_000 else if (it.optString("mimeType").startsWith("audio/mp4")) 2_000_000 else 0)
            } ?: -1
            if (score > bestScore) best = f
        }
        val bestUrl = best?.optString("url")
        if (!bestUrl.isNullOrBlank()) return bestUrl

        val hls = streaming.optString("hlsManifestUrl")
        if (hls.isNotBlank()) return hls

        return null
    }

    private fun parseSearch(root: JSONObject): List<SongsModel> {
        val out = mutableListOf<SongsModel>()
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents") ?: return out

        for (s in 0 until sections.length()) {
            val items = sections.getJSONObject(s).optJSONObject("musicShelfRenderer")
                ?.optJSONArray("contents") ?: continue
            for (i in 0 until items.length()) {
                val r = items.getJSONObject(i).optJSONObject("musicResponsiveListItemRenderer") ?: continue
                parseItem(r)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun parseItem(r: JSONObject): SongsModel? {
        val videoId = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (videoId.isBlank()) return null

        fun runs(col: Int): List<String> {
            val arr = r.optJSONArray("flexColumns")?.optJSONObject(col)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it).optString("text") }
        }

        val title = runs(0).firstOrNull().orEmpty()
        if (title.isBlank()) return null
        val meta = runs(1).filter { it.trim() != "•" && it.isNotBlank() }
        val durationText = meta.lastOrNull { DURATION.matches(it) }
        val textual = meta.filter { it != durationText && it != "Song" && it != "Video" }
        val artist = textual.firstOrNull().orEmpty()
        val album = textual.getOrNull(1).orEmpty()

        val thumbs = r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val cover = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
            .replace(Regex("=w\\d+-h\\d+.*$"), "=w600-h600")

        return SongsModel(
            id = videoId,
            title = title,
            album = album,
            singer = artist,
            coverUri = cover,
            url = "",
            youtubeVideoId = videoId,
            durationMs = parseDuration(durationText),
            audioFormat = "LOSSLESS • FLAC",
            isLiked = false
        )
    }

    private fun parseDuration(text: String?): Long {
        if (text == null) return 0L
        val parts = text.split(":").mapNotNull { it.toLongOrNull() }
        return parts.fold(0L) { acc, v -> acc * 60 + v } * 1000L
    }
}
