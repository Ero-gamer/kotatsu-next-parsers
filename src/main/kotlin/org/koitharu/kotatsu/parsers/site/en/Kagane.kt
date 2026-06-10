package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("KAGANE", "Kagane")
internal class Kagane(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGANE, pageSize = 35) {

    override val configKeyDomain = ConfigKey.Domain("kagane.to")
    private val apiUrl get() = "https://yuzuki.$domain"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.POPULARITY_TODAY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
    )

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.InterceptCloudflare(defaultValue = true))
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = genresCache ?: fetchGenres().also { genresCache = it }
        return MangaListFilterOptions(
            availableTags = genres,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
            availableContentRating = EnumSet.of(
                ContentRating.SAFE,
                ContentRating.SUGGESTIVE,
                ContentRating.ADULT,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
            ),
        )
    }

    private var genresCache: Set<MangaTag>? = null
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    private fun apiHeaders() = getRequestHeaders().newBuilder()
        .add("Origin", "https://$domain")
        .add("Referer", "https://$domain/")
        .build()

    private suspend fun fetchGenres(): Set<MangaTag> {
        return try {
            val raw = webClient.httpGet("$apiUrl/api/v2/genres/list", apiHeaders()).parseRaw()
            val genres = runCatching { JSONArray(raw) }.getOrElse {
                val wrapper = runCatching { JSONObject(raw) }.getOrNull()
                wrapper?.optJSONArray("content")
                    ?: wrapper?.optJSONArray("genres")
                    ?: JSONArray()
            }
            buildSet {
                for (i in 0 until genres.length()) {
                    val item = genres.optJSONObject(i) ?: continue
                    val id = item.optString("id").ifBlank { item.optString("genre_id") }
                    val title = item.optString("genre_name")
                        .ifBlank { item.optString("genreName") }
                        .ifBlank { item.optString("name") }
                    if (id.isNotBlank() && title.isNotBlank() && UUID_REGEX.matches(id)) {
                        add(MangaTag(title, id, source))
                    }
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    // ============================== List/Search ==============================

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sortParam = when (order) {
            SortOrder.UPDATED -> "updated_at,desc"
            SortOrder.POPULARITY -> "total_views,desc"
            SortOrder.POPULARITY_TODAY -> "avg_views_today,desc"
            SortOrder.POPULARITY_WEEK -> "avg_views_week,desc"
            SortOrder.POPULARITY_MONTH -> "avg_views_month,desc"
            SortOrder.NEWEST -> "created_at,desc"
            SortOrder.ALPHABETICAL -> "series_name,asc"
            else -> "updated_at,desc"
        }

        val url = "$apiUrl/api/v2/search/series?page=${page - 1}&size=$pageSize&sort=$sortParam"
        val body = JSONObject()

        if (!filter.query.isNullOrEmpty()) {
            body.put("title", filter.query)
        }

        // Content rating
        val ratings = filter.contentRating
        val ratingArray = JSONArray()
        if (ratings.isEmpty()) {
            // default: all
            listOf("Safe", "Suggestive", "Erotica", "Pornographic").forEach { ratingArray.put(it) }
        } else {
            ratings.forEach { cr ->
                when (cr) {
                    ContentRating.SAFE -> ratingArray.put("Safe")
                    ContentRating.SUGGESTIVE -> ratingArray.put("Suggestive")
                    ContentRating.ADULT -> {
                        ratingArray.put("Erotica")
                        ratingArray.put("Pornographic")
                    }
                }
            }
        }
        body.put("content_rating", ratingArray)

        // Genres (tags include + exclude)
        val includeGenres = filter.tags.map { it.key }.filter { UUID_REGEX.matches(it) }
        val excludeGenres = filter.tagsExclude.map { it.key }.filter { UUID_REGEX.matches(it) }
        if (includeGenres.isNotEmpty() || excludeGenres.isNotEmpty()) {
            val genresObj = JSONObject()
            if (includeGenres.isNotEmpty()) {
                val arr = JSONArray(); includeGenres.forEach { arr.put(it) }
                genresObj.put("values", arr)
            }
            if (excludeGenres.isNotEmpty()) {
                val arr = JSONArray(); excludeGenres.forEach { arr.put(it) }
                genresObj.put("exclude", arr)
            }
            genresObj.put("match_all", false)
            body.put("genres", genresObj)
        }

        // States
        if (filter.states.isNotEmpty()) {
            val statusArray = JSONArray()
            filter.states.forEach { state ->
                when (state) {
                    MangaState.ONGOING -> statusArray.put("Ongoing")
                    MangaState.FINISHED -> statusArray.put("Completed")
                    MangaState.PAUSED -> statusArray.put("Hiatus")
                    MangaState.ABANDONED -> statusArray.put("Abandoned")
                    else -> {}
                }
            }
            if (statusArray.length() > 0) body.put("upload_status", statusArray)
        }

        // Content types (format)
        if (filter.types.isNotEmpty()) {
            val formatArray = JSONArray()
            filter.types.forEach { type ->
                when (type) {
                    ContentType.MANGA -> formatArray.put("Manga")
                    ContentType.MANHWA -> formatArray.put("Manhwa")
                    ContentType.MANHUA -> formatArray.put("Manhua")
                    ContentType.COMICS -> formatArray.put("Comic")
                    else -> {}
                }
            }
            if (formatArray.length() > 0) body.put("format", formatArray)
        }

        val response = webClient.httpPost(url.toHttpUrl(), body, apiHeaders()).parseJson()
        val content = response.optJSONArray("content") ?: return emptyList()

        return (0 until content.length()).mapNotNull { i ->
            val item = content.getJSONObject(i)
            parseMangaFromSearch(item)
        }
    }

    private fun parseMangaFromSearch(item: JSONObject): Manga? {
        val id = item.optString("series_id").ifBlank { item.optString("id") }
        if (id.isBlank()) return null
        val name = item.optString("title").ifBlank { item.optString("name") }.ifBlank { return null }
        val coverImageId = item.optString("cover_image_id").ifBlank { item.optString("coverImageId") }
        val coverUrl = if (coverImageId.isNotBlank()) {
            "$apiUrl/api/v2/image/$coverImageId"
        } else {
            "$apiUrl/api/v2/series/$id/thumbnail"
        }
        return Manga(
            id = generateUid(id),
            url = id,
            publicUrl = "https://$domain/series/$id",
            coverUrl = coverUrl,
            title = name.trim(),
            altTitles = emptySet(),
            rating = RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = null,
            source = source,
            contentRating = parseContentRating(item.optString("content_rating")),
        )
    }

    // ============================== Details ==============================

    override suspend fun getDetails(manga: Manga): Manga {
        val seriesId = manga.url
        val json = webClient.httpGet("$apiUrl/api/v2/series/$seriesId", apiHeaders()).parseJson()

        val state = when (
            json.optString("upload_status")
                .ifBlank { json.optString("publication_status") }
                .uppercase(Locale.ROOT)
        ) {
            "ONGOING" -> MangaState.ONGOING
            "COMPLETED" -> MangaState.FINISHED
            "HIATUS" -> MangaState.PAUSED
            "ABANDONED" -> MangaState.ABANDONED
            else -> null
        }

        val tags = json.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val key = item.optString("id").ifBlank { item.optString("genre_id") }
                val name = item.optString("genre_name").ifBlank { item.optString("name") }
                if (key.isNotBlank() && name.isNotBlank()) MangaTag(name, key, source) else null
            }.toSet()
        } ?: emptySet()

        val authors = linkedSetOf<String>()
        json.optJSONArray("series_staff")?.let { arr ->
            for (i in 0 until arr.length()) {
                val staff = arr.optJSONObject(i) ?: continue
                val role = staff.optString("role")
                if (role.contains("author", ignoreCase = true) ||
                    role.contains("story", ignoreCase = true) ||
                    role.contains("artist", ignoreCase = true) ||
                    role.contains("art", ignoreCase = true)
                ) {
                    staff.optString("name").takeIf { it.isNotBlank() }?.let(authors::add)
                }
            }
        }

        // Alternate titles → altTitles
        val altTitles = linkedSetOf<String>()
        json.optJSONArray("series_alternate_titles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i)?.optString("title") ?: continue
                if (t.isNotBlank()) altTitles.add(t)
            }
        }

        // Description + source link
        val descBuilder = StringBuilder()
        json.optString("description").ifBlank { null }?.let { descBuilder.append(it.trim()) }
        val editionInfo = json.optString("edition_info").ifBlank { null }
        if (editionInfo != null) {
            if (descBuilder.isNotEmpty()) descBuilder.append("\n\n")
            descBuilder.append("Edition: $editionInfo")
        }

        val chapters = parseChapters(seriesId, json)

        // Cover: use first series_covers entry if present
        val coverImageId = json.optJSONArray("series_covers")
            ?.optJSONObject(0)?.optString("image_id").orEmpty()
        val coverUrl = if (coverImageId.isNotBlank()) {
            "$apiUrl/api/v2/image/$coverImageId"
        } else {
            manga.coverUrl
        }

        return manga.copy(
            title = json.optString("title").trim().ifBlank { manga.title },
            altTitles = altTitles,
            coverUrl = coverUrl,
            description = descBuilder.toString().ifBlank { null },
            state = state,
            authors = authors,
            tags = tags,
            chapters = chapters,
            contentRating = parseContentRating(json.optString("content_rating")) ?: manga.contentRating,
        )
    }

    private fun parseChapters(seriesId: String, json: JSONObject): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        val books = json.optJSONArray("series_books") ?: JSONArray()
        val chapters = ArrayList<MangaChapter>(books.length())

        for (i in 0 until books.length()) {
            val ch = books.optJSONObject(i) ?: continue
            val chId = ch.optString("book_id").ifBlank { ch.optString("id") }
            if (chId.isBlank()) continue

            val chapterNo = ch.optString("chapter_no").ifBlank { null }
            val volumeNo = ch.optString("volume_no").ifBlank { null }
            val number = chapterNo?.replace(',', '.')?.toFloatOrNull()
                ?: ch.optDouble("sort_no", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
                ?: 0f
            val volume = volumeNo?.toIntOrNull() ?: 0

            // Chapter title: prefer explicit title, fallback to Ch.X
            val rawTitle = ch.optString("title").trim()
            val chapterName = buildChapterName(rawTitle, chapterNo, volumeNo)

            val dateStr = ch.optString("created_at").substringBefore('T')
            val uploadDate = try { dateFormat.parse(ch.optString("created_at"))?.time ?: 0L } catch (_: Exception) { 0L }

            val pageCount = ch.optInt("page_count", 0)
            val scanlator = ch.optJSONArray("groups")?.let { g ->
                (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("title") }
                    .filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
            }

            chapters.add(
                MangaChapter(
                    id = generateUid("$seriesId:$chId"),
                    title = chapterName,
                    number = number,
                    volume = volume,
                    url = "/series/$seriesId/reader/$chId",
                    uploadDate = uploadDate,
                    source = source,
                    scanlator = scanlator,
                    branch = null,
                ),
            )
        }
        return chapters.reversed()
    }

    private fun buildChapterName(title: String, chapterNo: String?, volumeNo: String?): String {
        val chPart = if (!chapterNo.isNullOrBlank()) "Ch.$chapterNo" else ""
        val volPart = if (!volumeNo.isNullOrBlank()) "Vol.$volumeNo " else ""
        return when {
            title.isEmpty() && chPart.isNotEmpty() -> "$volPart$chPart".trim()
            title.isEmpty() -> "Chapter"
            chPart.isEmpty() -> title
            else -> "$volPart$chPart $title".trim()
        }
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    // ============================== Pages ==============================

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // URL format: /series/{seriesId}/reader/{chapterId}
        val pathParts = chapter.url.split("/").filter { it.isNotEmpty() }
        if (pathParts.size < 4) throw Exception("Invalid chapter URL: ${chapter.url}")
        val seriesId = pathParts[1]
        val chapterId = pathParts[3]

        // 1. Integrity token
        val integrityToken = getIntegrityToken()

        // 2. POST to /api/v2/books/{chapterId} with integrity token → get access_token + cache_url + manifest
        val challengeUrl = "$apiUrl/api/v2/books/$chapterId?is_datasaver=false"
        val challengeHeaders = apiHeaders().newBuilder()
            .add("x-integrity-token", integrityToken)
            .build()
        val challengeBody = JSONObject()
        val tokenResponse = webClient.httpPost(challengeUrl.toHttpUrl(), challengeBody, challengeHeaders).parseJson()

        val accessToken = tokenResponse.optString("access_token").ifBlank {
            tokenResponse.optString("accessToken")
        }.ifBlank { throw Exception("Missing access_token in challenge response") }

        val cacheUrl = tokenResponse.optString("cache_url").ifBlank {
            tokenResponse.optString("cacheUrl")
        }.ifBlank { "https://akari.$domain" }

        // 3. Parse pages from manifest
        val manifest = tokenResponse.optJSONObject("manifest")
        val pagesArray = manifest?.optJSONArray("pages") ?: tokenResponse.optJSONArray("pages")

        if (pagesArray != null && pagesArray.length() > 0) {
            return (0 until pagesArray.length()).mapNotNull { i ->
                val p = pagesArray.optJSONObject(i) ?: return@mapNotNull null
                val pageUuid = p.optString("page_id").ifBlank { p.optString("pageUuid").ifBlank { p.optString("id") } }
                if (pageUuid.isBlank()) return@mapNotNull null
                val ext = p.optString("ext").ifBlank { "jxl" }
                val pageNo = p.optInt("page_no", p.optInt("pageNumber", i + 1))
                val imageUrl = "$cacheUrl/api/v2/books/page/$chapterId/$pageUuid.$ext" +
                    "?token=$accessToken&is_datasaver=false"
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source,
                )
            }
        }

        throw Exception("No pages found in challenge response for chapter $chapterId")
    }

    // ============================== Token ==============================

    private var integrityToken: String = ""
    private var integrityTokenExp: Long = 0L

    private suspend fun getIntegrityToken(): String {
        val now = System.currentTimeMillis()
        if (integrityToken.isNotBlank() && now < integrityTokenExp) return integrityToken

        // GET homepage first (mirrors mihon behavior to get cookies)
        webClient.httpGet("https://$domain/", apiHeaders())

        val response = webClient.httpPost(
            "https://$domain/api/integrity".toHttpUrl(),
            JSONObject(),
            apiHeaders(),
        ).parseJson()

        val token = response.optString("token")
        if (token.isBlank()) throw Exception("Failed to retrieve integrity token")
        integrityToken = token
        integrityTokenExp = response.optLong("exp", 0L) * 1000L
        return integrityToken
    }

    // ============================== Image interceptor (token refresh + decryption) ==============================

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        // Only handle image page requests: /api/v2/books/page/{chapterId}/{file}?token=...
        if (!url.queryParameterNames.contains("token") || !url.encodedPath.contains("/books/page/")) {
            return chain.proceed(request)
        }

        var response = chain.proceed(request)

        // Token refresh on auth errors (mirrors mihon refreshTokenInterceptor)
        if (response.code == 401 || response.code == 403 || response.code == 507) {
            response.close()
            // Token expired — clear cached token so next getPages call refreshes it
            integrityToken = ""
            integrityTokenExp = 0L
            // Return error response; getPages will retry on next user action
            return chain.proceed(request)
        }

        if (!response.isSuccessful) return response

        // Image decryption
        val pathSegments = url.pathSegments
        val pageIdx = pathSegments.indexOf("page")
        if (pageIdx == -1 || pageIdx + 2 >= pathSegments.size) return response

        val chapterId = pathSegments[pageIdx + 1]
        val seriesId = url.queryParameter("sid").orEmpty().ifBlank { chapterId }

        val contentType = response.body?.contentType()
        val imageBytes = response.body?.bytes() ?: return response

        return try {
            val decrypted = decryptImage(imageBytes, chapterId)
            val processed = if (decrypted != null && isValidImage(decrypted)) {
                decrypted
            } else if (decrypted != null) {
                // Try unscramble
                val pageIndex = url.queryParameter("index")?.toIntOrNull() ?: 1
                val fileName = "%04d.jpg".format(pageIndex)
                val seed = generateSeed(seriesId, chapterId, fileName)
                val mapping = Scrambler(seed, 10).getScrambleMapping()
                val unscrambled = unscramble(decrypted, mapping, true)
                if (isValidImage(unscrambled)) unscrambled else decrypted
            } else {
                imageBytes
            }
            response.newBuilder().body(processed.toResponseBody(contentType)).build()
        } catch (_: Exception) {
            response.newBuilder().body(imageBytes.toResponseBody(contentType)).build()
        }
    }

    // ============================== Helpers ==============================

    private fun parseContentRating(value: String?): ContentRating? = when (value?.lowercase(Locale.ROOT)) {
        "safe" -> ContentRating.SAFE
        "suggestive" -> ContentRating.SUGGESTIVE
        "adult", "erotica", "pornographic" -> ContentRating.ADULT
        else -> null
    }

    private fun isValidImage(data: ByteArray): Boolean {
        if (data.size < 8) return false
        return when {
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> true // JPEG
            data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() &&
                data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte() -> true // PNG
            data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
                data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
                data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte() -> true // WEBP
            data[0] == 0xFF.toByte() && data[1] == 0x0A.toByte() -> true // JXL bare
            data.size >= 12 && data[4] == 'J'.code.toByte() && data[5] == 'X'.code.toByte() &&
                data[6] == 'L'.code.toByte() && data[7] == ' '.code.toByte() -> true // JXL container
            data.size >= 6 && (
                data.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                    data.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())
                ) -> true // GIF
            data.size >= 12 && data.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()) -> true // HEIF
            else -> false
        }
    }

    // AES-GCM decryption: key = sha256(chapterId), iv = bytes[128..140], cipher = bytes[140..]
    private fun decryptImage(payload: ByteArray, chapterId: String): ByteArray? {
        if (payload.size < 140) return null
        return try {
            val keyHash = chapterId.sha256()
            val iv = payload.sliceArray(128 until 140)
            val ciphertext = payload.sliceArray(140 until payload.size)

            val secretKey: SecretKey = SecretKeySpec(keyHash, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    private fun generateSeed(t: String, n: String, e: String): BigInteger {
        val sha256 = "$t:$n:$e".sha256()
        var a = BigInteger.ZERO
        for (i in 0 until 8) a = a.shiftLeft(8).or(BigInteger.valueOf((sha256[i].toInt() and 0xFF).toLong()))
        return a
    }

    private fun unscramble(data: ByteArray, mapping: List<Pair<Int, Int>>, prepend: Boolean): ByteArray {
        val s = mapping.size
        val a = data.size
        val l = a / s
        val o = a % s
        val (remainder, body) = if (prepend) {
            if (o > 0) data.copyOfRange(0, o) to data.copyOfRange(o, a)
            else ByteArray(0) to data
        } else {
            if (o > 0) data.copyOfRange(a - o, a) to data.copyOfRange(0, a - o)
            else ByteArray(0) to data
        }
        val chunks = (0 until s).map { body.copyOfRange(it * l, (it + 1) * l) }
        val result = Array(s) { ByteArray(0) }
        for ((dst, src) in mapping) {
            if (dst < s && src < s) result[dst] = chunks[src]
        }
        val joined = result.fold(ByteArray(0)) { acc, c -> acc + c }
        return if (prepend) joined + remainder else remainder + joined
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }

    // ============================== Scrambler ==============================

    private class Scrambler(private val seed: BigInteger, private val gridSize: Int) {
        private val totalPieces = gridSize * gridSize
        private val randomizer = Randomizer(seed, gridSize)
        private val dependencyGraph = buildDependencyGraph()
        private val scramblePath = generateScramblePath()

        private fun buildDependencyGraph(): Pair<MutableMap<Int, MutableList<Int>>, MutableMap<Int, Int>> {
            val graph = mutableMapOf<Int, MutableList<Int>>()
            val inDegree = mutableMapOf<Int, Int>()
            for (n in 0 until totalPieces) { inDegree[n] = 0; graph[n] = mutableListOf() }
            val rng = Randomizer(seed, gridSize)
            for (r in 0 until totalPieces) {
                val i = (rng.prng() % BigInteger.valueOf(3) + BigInteger.valueOf(2)).toInt()
                repeat(i) {
                    val j = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                    if (j != r && !wouldCreateCycle(graph, j, r)) {
                        graph[j]!!.add(r); inDegree[r] = inDegree[r]!! + 1
                    }
                }
            }
            for (r in 0 until totalPieces) {
                if (inDegree[r] == 0) {
                    var tries = 0
                    while (tries < 10) {
                        val s = (rng.prng() % BigInteger.valueOf(totalPieces.toLong())).toInt()
                        if (s != r && !wouldCreateCycle(graph, s, r)) {
                            graph[s]!!.add(r); inDegree[r] = inDegree[r]!! + 1; break
                        }
                        tries++
                    }
                }
            }
            return graph to inDegree
        }

        private fun wouldCreateCycle(graph: Map<Int, List<Int>>, target: Int, start: Int): Boolean {
            val visited = mutableSetOf<Int>()
            val stack = ArrayDeque<Int>().also { it.add(start) }
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                if (n == target) return true
                if (!visited.add(n)) continue
                graph[n]?.let { stack.addAll(it) }
            }
            return false
        }

        private fun generateScramblePath(): List<Int> {
            val (graphCopy, inDegreeCopy) = dependencyGraph.first.mapValues { it.value.toMutableList() }.toMutableMap() to
                dependencyGraph.second.toMutableMap()
            val queue = ArrayDeque<Int>()
            for (n in 0 until totalPieces) { if (inDegreeCopy[n] == 0) queue.add(n) }
            val order = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst(); order.add(i)
                graphCopy[i]?.forEach { e ->
                    inDegreeCopy[e] = inDegreeCopy[e]!! - 1
                    if (inDegreeCopy[e] == 0) queue.add(e)
                }
            }
            return order
        }

        fun getScrambleMapping(): List<Pair<Int, Int>> {
            var e = randomizer.order.toMutableList()
            if (scramblePath.size == totalPieces) {
                val t = scramblePath.toTypedArray()
                val n = Array(totalPieces) { 0 }
                for (r in 0 until totalPieces) n[r] = e[t[r]]
                e = n.toMutableList()
            }
            return (0 until totalPieces).map { it to e[it] }
        }
    }

    private class Randomizer(seedInput: BigInteger, t: Int) {
        val size = t * t
        val seed: BigInteger
        private var state: BigInteger
        private val entropyPool: ByteArray
        val order: MutableList<Int>

        companion object {
            private val MASK64 = BigInteger("FFFFFFFFFFFFFFFF", 16)
            private val MASK32 = BigInteger("FFFFFFFF", 16)
            private val MASK8 = BigInteger("FF", 16)
            private val PRNG_MULT = BigInteger("27BB2EE687B0B0FD", 16)
            private val RND_MULT_32 = BigInteger("45d9f3b", 16)
        }

        init {
            seed = seedInput.and(MASK64)
            state = hashSeed(seed)
            entropyPool = MessageDigest.getInstance("SHA-512").digest(seed.toString().toByteArray(StandardCharsets.UTF_8))
            order = MutableList(size) { it }
            permute()
        }

        private fun hashSeed(e: BigInteger): BigInteger {
            val md = e.toString().sha256()
            return readU64BE(md, 0).xor(readU64BE(md, 8))
        }

        private fun readU64BE(bytes: ByteArray, offset: Int): BigInteger {
            var n = BigInteger.ZERO
            for (i in 0 until 8) n = n.shiftLeft(8).or(BigInteger.valueOf((bytes[offset + i].toInt() and 0xFF).toLong()))
            return n
        }

        private fun sbox(e: Int): Int {
            val t = intArrayOf(163, 95, 137, 13, 55, 193, 107, 228, 114, 185, 22, 243, 68, 218, 158, 40)
            return t[e and 15] xor t[e shr 4 and 15]
        }

        fun prng(): BigInteger {
            state = state.xor(state.shiftLeft(11).and(MASK64))
            state = state.xor(state.shiftRight(19))
            state = state.xor(state.shiftLeft(7).and(MASK64))
            state = state.multiply(PRNG_MULT).and(MASK64)
            return state
        }

        private fun roundFunc(e: BigInteger, t: Int): BigInteger {
            var n = e.xor(prng()).xor(BigInteger.valueOf(t.toLong()))
            val rot = n.shiftLeft(5).or(n.shiftRight(3)).and(MASK32)
            n = rot.multiply(RND_MULT_32).and(MASK32)
            n = n.xor(BigInteger.valueOf(sbox(n.and(MASK8).toInt()).toLong()))
            return n.xor(n.shiftRight(13))
        }

        private fun feistelMix(e: Int, t: Int, rounds: Int): Pair<BigInteger, BigInteger> {
            var r = BigInteger.valueOf(e.toLong())
            var i = BigInteger.valueOf(t.toLong())
            for (round in 0 until rounds) {
                val ent = entropyPool[round % entropyPool.size].toInt() and 0xFF
                r = r.xor(roundFunc(i, ent))
                i = i.xor(roundFunc(r, ent xor (round * 31 and 255)))
            }
            return r to i
        }

        private fun permute() {
            val half = size / 2
            val sizeBig = BigInteger.valueOf(size.toLong())
            for (t in 0 until half) {
                val (rBig, iBig) = feistelMix(t, t + half, 4)
                val s = rBig.mod(sizeBig).toInt()
                val a = iBig.mod(sizeBig).toInt()
                val tmp = order[s]; order[s] = order[a]; order[a] = tmp
            }
            for (e in size - 1 downTo 1) {
                val ent = entropyPool[e % entropyPool.size].toInt() and 0xFF
                val n = prng().add(BigInteger.valueOf(ent.toLong())).mod(BigInteger.valueOf((e + 1).toLong())).toInt()
                val tmp = order[e]; order[e] = order[n]; order[n] = tmp
            }
        }
    }
}

private fun String.sha256(): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(StandardCharsets.UTF_8))
