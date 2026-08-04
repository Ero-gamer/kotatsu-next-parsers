package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
        listOf(
            SortOrder.RELEVANCE,
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL
        )
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    // The site's curated genres, keyed by the numeric id the API expects in
    // `genres_in[]` (verified against /api/v1/tags/search?type=genre). The
    // narrative "tags" (Demons, School Life, ...) live in a separate id space
    // with thousands of entries and no listing endpoint, so they aren't
    // enumerated here — they still work via search because every tag shown on
    // a manga's detail page carries its own numeric id (see [parseTerms]),
    // and any non-numeric tag key is resolved by name through [resolveTagId].
    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return setOf(
            MangaTag(key = "6", title = "Action", source = source),
            MangaTag(key = "87264", title = "Adult", source = source),
            MangaTag(key = "7", title = "Adventure", source = source),
            MangaTag(key = "8", title = "Boys Love", source = source),
            MangaTag(key = "9", title = "Comedy", source = source),
            MangaTag(key = "10", title = "Crime", source = source),
            MangaTag(key = "11", title = "Drama", source = source),
            MangaTag(key = "87265", title = "Ecchi", source = source),
            MangaTag(key = "12", title = "Fantasy", source = source),
            MangaTag(key = "13", title = "Girls Love", source = source),
            MangaTag(key = "40", title = "Harem", source = source),
            MangaTag(key = "87266", title = "Hentai", source = source),
            MangaTag(key = "14", title = "Historical", source = source),
            MangaTag(key = "15", title = "Horror", source = source),
            MangaTag(key = "16", title = "Isekai", source = source),
            MangaTag(key = "17", title = "Magical Girls", source = source),
            MangaTag(key = "87267", title = "Mature", source = source),
            MangaTag(key = "18", title = "Mecha", source = source),
            MangaTag(key = "19", title = "Medical", source = source),
            MangaTag(key = "20", title = "Mystery", source = source),
            MangaTag(key = "21", title = "Philosophical", source = source),
            MangaTag(key = "22", title = "Psychological", source = source),
            MangaTag(key = "23", title = "Romance", source = source),
            MangaTag(key = "24", title = "Sci-Fi", source = source),
            MangaTag(key = "25", title = "Slice of Life", source = source),
            MangaTag(key = "87268", title = "Smut", source = source),
            MangaTag(key = "26", title = "Sports", source = source),
            MangaTag(key = "27", title = "Superhero", source = source),
            MangaTag(key = "28", title = "Thriller", source = source),
            MangaTag(key = "29", title = "Tragedy", source = source),
            MangaTag(key = "30", title = "Wuxia", source = source),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // The `/api/v1/manga` endpoint is request-signed (an unsigned GET 403s
        // with "missing token"), so instead of calling it we do what the website
        // does: load the `/browse` page and read the results the server embeds in
        // its `script#initial-data` JSON. No token needed.
        val query = filter.query
        val browseUrl = buildString {
            append("https://")
            append(domain)
            append("/browse?")
            var firstParam = true
            fun addParam(param: String) {
                if (firstParam) {
                    append(param)
                    firstParam = false
                } else {
                    append("&").append(param)
                }
            }

            if (!query.isNullOrEmpty()) {
                // The website routes keyword search through `q` + `sort`.
                addParam("q=${query.urlEncoded()}")
                addParam("sort=relevance:desc")
            } else {
                when (order) {
                    SortOrder.RELEVANCE -> addParam("order[relevance]=desc")
                    SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
                    SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
                    SortOrder.NEWEST -> addParam("order[created_at]=desc")
                    SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
                    else -> addParam("order[chapter_updated_at]=desc")
                }
            }

            // Handle genre/tag filtering. A tag key is normally the numeric id
            // the API wants; anything non-numeric (e.g. a tag tapped from a
            // manga's detail page that predates this change) is resolved by name.
            val includedIds = LinkedHashSet<String>()
            for (tag in filter.tags) {
                val id = tag.key.toIntOrNull()?.let { tag.key } ?: resolveTagId(tag.title)
                if (id != null) includedIds.add(id)
            }
            for (id in includedIds) {
                addParam("genres_in[]=$id")
            }

            // Default exclude adult content, unless the user explicitly asked
            // for one of those genres via the filter.
            for (excludeId in ADULT_EXCLUDE_IDS) {
                if (excludeId !in includedIds) {
                    addParam("genres_ex[]=$excludeId")
                }
            }
            addParam("page=$page")
        }

        val items = loadBrowseItems(browseUrl)
        return (0 until items.length()).map { i ->
            parseMangaFromJson(items.getJSONObject(i))
        }
    }

    /**
     * Returns the manga items the `/browse` page exposes. The browse listing is
     * not server-rendered — the page fetches it over a signed, encrypted XHR
     * after hydration — so we load the page in a WebView and capture the payload
     * it decrypts and parses (mirroring the upstream Keiyoushi fallback). A plain
     * GET is tried first for the rare route that does inline `script#initial-data`.
     */
    private suspend fun loadBrowseItems(browseUrl: String): JSONArray {
        runCatching { webClient.httpGet(browseUrl).parseHtml() }
            .getOrNull()
            ?.let { extractInitialDataItems(it) }
            ?.let { return it }

        val response = evaluateWebViewApiJson(browseUrl, BROWSE_CAPTURE_SCRIPT)
        return response.optJSONObject("result")?.optJSONArray("items")
            ?: response.optJSONArray("items")
            ?: throw ParseException("Comix browse page returned no results", browseUrl)
    }

    /**
     * Loads a page and returns its rendered HTML as a [Document], retrying so a
     * cold Cloudflare challenge can clear. A plain GET is tried first (it works
     * once the CF cookie is in the shared client); otherwise a WebView drives
     * the navigation, which both passes the challenge and renders the SSR HTML.
     * [isReady] decides whether a candidate document actually carries the data
     * we need (vs. a challenge/empty shell), so we keep retrying until it does.
     */
    private suspend fun loadRenderedDocument(url: String, isReady: (Document) -> Boolean): Document? {
        repeat(WEBVIEW_PAGE_ATTEMPTS) {
            runCatching { webClient.httpGet(url).parseHtml() }
                .getOrNull()
                ?.takeIf(isReady)
                ?.let { return it }

            val html = context.evaluateJs(url, PAGE_HTML_SCRIPT, WEBVIEW_PAGE_TIMEOUT)
            if (!html.isNullOrBlank()) {
                Jsoup.parse(html, url).takeIf(isReady)?.let { return it }
            }
        }
        return null
    }

    private fun extractInitialDataItems(document: Document): JSONArray? {
        val raw = document.selectFirst("script#initial-data")?.data()?.nullIfEmpty() ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        for (key in queries.keys()) {
            val value = queries.optJSONObject(key) ?: continue
            val items = value.optJSONObject("result")?.optJSONArray("items")
                ?: value.optJSONArray("items")
            if (items != null && items.length() > 0) return items
        }
        return null
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.optString("hid").ifBlank { json.optString("hash_id") }
        val title = json.getString("title")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.optJSONObject("poster")
        val coverUrl = poster?.optString("large", "")?.nullIfEmpty()
            ?: poster?.optString("medium", "")?.nullIfEmpty()
            ?: poster?.optString("small", "")?.nullIfEmpty()
        val status = json.optString("status", "")
        val rating = json.optDouble("ratedAvg", Double.NaN)
            .takeUnless { it.isNaN() }
            ?: json.optDouble("rated_avg", 0.0)

        val state = when (status) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        return Manga(
            id = generateUid(hashId),
            url = "/title/$hashId",
            publicUrl = "https://comix.to/title/$hashId",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = if (rating > 0) (rating / 10.0).toFloat() else RATING_UNKNOWN,
            tags = parseTerms(json),
            authors = parseAuthors(json),
            state = state,
            source = source,
            contentRating = if (json.optString("contentRating") in NSFW_RATINGS) ContentRating.ADULT else ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val chaptersDeferred = async { getChapters(manga) }

        // Enrich from the title page's `script#initial-data` (the same SSR JSON
        // the website hydrates from), so no signed API call is needed. If the
        // page is gated/empty, fall back to the listing-derived manga (which
        // already carries synopsis/tags/authors) so details still open.
        val updatedManga = loadRenderedDocument(manga.url.toAbsoluteUrl(domain)) {
            extractInitialDataDetail(it) != null
        }
            ?.let { extractInitialDataDetail(it) }
            ?.let { parseMangaFromJson(it) }
            ?: manga

        return@coroutineScope updatedManga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    private fun extractInitialDataDetail(document: Document): JSONObject? {
        val raw = document.selectFirst("script#initial-data")?.data()?.nullIfEmpty() ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        // The detail query key embeds "detail"; its value is the manga object
        // (occasionally wrapped in `result`).
        for (key in queries.keys()) {
            if (!key.contains("detail")) continue
            val value = queries.optJSONObject(key) ?: continue
            val candidate = value.optJSONObject("result") ?: value
            if (candidate.has("hid") || candidate.has("hash_id") || candidate.has("title")) {
                return candidate
            }
        }
        return null
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val readerUrl = chapter.url.toAbsoluteUrl(domain)

        // Capture the reader page's own (signed, decrypted) page payload rather
        // than re-implementing the request signing, which hangs (see [loadAllChapters]).
        val response = runCatching { webClient.httpGet(readerUrl).parseHtml() }
            .getOrNull()
            ?.let { extractInitialDataPages(it) }
            ?: evaluateWebViewApiJson(readerUrl, PAGE_CAPTURE_SCRIPT)
        val pagesRoot = response.optJSONObject("result")?.optJSONObject("pages")
        val baseUrl = pagesRoot?.optString("baseUrl").orEmpty().trimEnd('/')
        val pages = pagesRoot?.optJSONArray("items")
            ?: response.optJSONObject("result")?.optJSONArray("pages")
            ?: JSONArray()

        return (0 until pages.length()).map { i ->
            val item = pages.optJSONObject(i)
            val rawUrl = item?.getString("url") ?: pages.get(i).toString()
            val imageUrl = if (rawUrl.startsWith("http", ignoreCase = true) || baseUrl.isBlank()) {
                rawUrl
            } else {
                "$baseUrl/${rawUrl.trimStart('/')}"
            }
            // `s == 1` marks a "v3" tile-scrambled image. The server only returns
            // the x-scramble-*/x-enc-* headers when the request carries the `v3`
            // query flag, so we add it here; the interceptor then descrambles based
            // on those headers. The `#scrambled` fragment (dropped before the request
            // is sent) keeps scrambled pages from colliding with any unscrambled
            // namesake in the cache.
            val finalUrl = if (item?.optInt("s", 0) == 1) {
                val withV3 = if (imageUrl.toHttpUrl().queryParameterNames.contains("v3")) {
                    imageUrl
                } else {
                    imageUrl.toHttpUrl().newBuilder().addQueryParameter("v3", null).build().toString()
                }
                "$withV3#$SCRAMBLED_FRAGMENT"
            } else {
                imageUrl
            }
            MangaPage(
                id = generateUid("$chapterId-$i"),
                url = finalUrl,
                preview = null,
                source = source,
            )
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) {
            return response
        }

        // The CDN protects images with two independent, stackable layers, each
        // signalled by its own response headers (only protected images carry
        // them, so API and HTML responses pass straight through):
        //   * a byte-level XOR stream cipher        — x-enc-seed / x-enc-len / x-enc-algo
        //   * a 5x5 tile shuffle on the decoded image — x-scramble-seed / x-scramble-grid /
        //                                               x-scramble-algo / x-scramble-hash
        val rawScrambleGrid = response.header("x-scramble-grid")
        val rawScrambleAlgo = response.header("x-scramble-algo")
        val rawScrambleHash = response.header("x-scramble-hash")
        val rawEncAlgo = response.header("x-enc-algo")

        val encSeed = response.header("x-enc-seed")?.toLongOrNull()?.toInt()
        val encLen = response.header("x-enc-len")?.toIntOrNull()
        val scrambleSeed = response.header("x-scramble-seed")?.toLongOrNull()?.toInt()
        val scrambleHash = decodeScrambleHash(rawScrambleHash)

        val needsXor = encSeed != null && encSeed != 0 && encLen != null
        val shouldDescrambleGrid = rawScrambleGrid == "5x5" &&
            (rawScrambleAlgo == null || rawScrambleAlgo == "1" || rawScrambleAlgo == "2" || rawScrambleAlgo == "3") &&
            scrambleSeed != null && scrambleSeed != 0

        if (!needsXor && !shouldDescrambleGrid) {
            return response
        }

        val contentType = response.body?.contentType()
        val originalBytes = response.body?.bytes() ?: return response
        val bytes = if (needsXor) {
            decodeEncodedBytes(originalBytes, encSeed!!, encLen!!, rawEncAlgo)
        } else {
            originalBytes
        }

        // Re-wrap the (de-XORed) bytes so the redraw helper can decode them into
        // a bitmap, then undo the tile shuffle on top.
        val decodedResponse = response.newBuilder()
            .body(bytes.toResponseBody(contentType))
            .build()

        if (!shouldDescrambleGrid) {
            return decodedResponse
        }

        return context.redrawImageResponse(decodedResponse) { bitmap ->
            descramble(bitmap, scrambleSeed!! xor scrambleHash, rawScrambleAlgo)
        }
    }

    // A handful of older images ship a constant hash that gets folded into the
    // scramble seed; everything else (and the modern format) uses the seed as-is.
    private fun decodeScrambleHash(hash: String?): Int = when (hash?.trim()) {
        "03632" -> 58414
        else -> 0
    }

    // Undo the x-enc XOR stream. Algo "2" is ambiguous about which generator the
    // server used, so we try each candidate and keep the first that decodes to a
    // recognisable image; every other algo is the plain LCG keystream.
    private fun decodeEncodedBytes(bytes: ByteArray, seed: Int, length: Int, algo: String?): ByteArray {
        if (algo != "2") {
            return decodeWithLcg(bytes, seed, length)
        }
        val candidates = listOf(
            decodeWithXorshift(bytes, seed or 1, length, false),
            decodeWithXorshift(bytes, seed, length, false),
            decodeWithXorshift(bytes, seed or 1, length, true),
            decodeWithLcg(bytes, seed, length),
        )
        return candidates.firstOrNull { it.hasImageSignature() } ?: candidates.first()
    }

    private fun decodeWithLcg(bytes: ByteArray, seed: Int, length: Int): ByteArray {
        val result = bytes.copyOf()
        var state = seed
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = state * ENC_MULTIPLIER + ENC_INCREMENT
            result[i] = (result[i].toInt() xor (state ushr 24)).toByte()
        }
        return result
    }

    private fun decodeWithXorshift(bytes: ByteArray, initialState: Int, length: Int, highByte: Boolean): ByteArray {
        val result = bytes.copyOf()
        var state = initialState
        val limit = minOf(result.size, length)
        for (i in 0 until limit) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val key = if (highByte) state ushr 24 else state and 0xFF
            result[i] = (result[i].toInt() xor key).toByte()
        }
        return result
    }

    private fun ByteArray.hasImageSignature(): Boolean = size >= 12 && (
        (
            this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() && this[2] == 'F'.code.toByte() &&
                this[3] == 'F'.code.toByte() && this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
                this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
            ) ||
            (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) ||
            (
                this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'N'.code.toByte() &&
                    this[3] == 'G'.code.toByte()
                )
        )

    // Reverses the site's 5x5 tile shuffle. The scramble order is a Fisher-Yates
    // permutation driven by a PRNG seeded with `x-scramble-seed` (xored with the
    // optional hash). Algo "3" uses a xorshift generator; every other algo uses an
    // LCG. `order[srcIdx]` gives the destination position of scrambled tile srcIdx.
    private fun descramble(source: Bitmap, seed: Int, algo: String?): Bitmap {
        val width = source.width
        val height = source.height
        val tileW = width / GRID_COLS
        val tileH = height / GRID_ROWS
        val order = if (algo == "3") {
            buildScrambleOrderXorshift(seed, NUM_TILES)
        } else {
            buildScrambleOrderLcg(seed, NUM_TILES)
        }

        val output = context.createBitmap(width, height)
        // Copy the whole image first so any edge pixels left over from the
        // integer tile division are preserved.
        output.drawBitmap(source, Rect(0, 0, width, height), Rect(0, 0, width, height))

        for (srcIdx in 0 until NUM_TILES) {
            val dstIdx = order[srcIdx]
            val srcCol = srcIdx % GRID_COLS
            val srcRow = srcIdx / GRID_COLS
            val dstCol = dstIdx % GRID_COLS
            val dstRow = dstIdx / GRID_COLS
            val srcRect = Rect(srcCol * tileW, srcRow * tileH, (srcCol + 1) * tileW, (srcRow + 1) * tileH)
            val dstRect = Rect(dstCol * tileW, dstRow * tileH, (dstCol + 1) * tileW, (dstRow + 1) * tileH)
            output.drawBitmap(source, srcRect, dstRect)
        }
        return output
    }

    private fun buildScrambleOrderLcg(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed
        for (i in n - 1 downTo 1) {
            state = state * LCG_MULTIPLIER + LCG_INCREMENT
            val j = ((state.toLong() and 0xFFFFFFFFL) % (i + 1)).toInt()
            val tmp = arr[i]
            arr[i] = arr[j]
            arr[j] = tmp
        }
        return arr
    }

    private fun buildScrambleOrderXorshift(seed: Int, n: Int): IntArray {
        val arr = IntArray(n) { it }
        var state = seed or 1
        for (i in n - 1 downTo 1) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            val j = ((state.toLong() and 0xFFFFFFFFL) % (i + 1)).toInt()
            val tmp = arr[i]
            arr[i] = arr[j]
            arr[j] = tmp
        }
        return arr
    }

    private suspend fun getChapters(manga: Manga): List<MangaChapter> {
        val hashId = manga.url.substringAfter("/title/")
        val payload = loadAllChapters(hashId)
        val rawItems = payload.optJSONArray("items") ?: return emptyList()
        val parsed = (0 until rawItems.length()).mapNotNull { rawItems.optJSONObject(it) }
        if (parsed.isEmpty()) {
            return emptyList()
        }

        // The script sends the shared URL prefix and the group list once and has
        // every chapter reference them by index — see [CHAPTER_SCRIPT].
        val urlPrefix = payload.optString("prefix")
        val groups = payload.optJSONArray("groups")

        // Every scanlation team is kept: each one becomes its own branch, so the
        // reader gets the site's full "All groups" list with a translation
        // picker rather than a single team chosen for it.
        //
        // The site serves chapters newest-first and the capture script merges
        // several pages, so order the list here instead of trusting either.
        val chapters = parsed.sortedBy { it.optDouble("n", 0.0) }

        val chaptersBuilder = ChaptersListBuilder(chapters.size)
        for (chapterData in chapters) {
            val chapterId = chapterData.optLong("i")
            val number = chapterData.optDouble("n", 0.0).toFloat()
            val name = chapterData.optString("t").nullIfEmpty()
            val scanlator = teamNameOf(groups?.optJSONObject(chapterData.optInt("g", -1)))
            val label = number.toChapterUrlPart()
            val title = if (name != null) {
                "Chapter $label: $name"
            } else {
                "Chapter $label"
            }
            // Prefer the canonical path the site itself links to — it carries the
            // full title slug (e.g. `/title/x0ynk-villains.../<id>-chapter-N`).
            // The hashId-only path 404s in the reader.
            val chapterUrl = chapterData.optString("u").nullIfEmpty()
                ?.let { urlPrefix + it }
                ?: "/title/$hashId/$chapterId-chapter-$label"
            chaptersBuilder.add(
                MangaChapter(
                    id = generateUid("$scanlator-$chapterId"),
                    title = title,
                    number = number,
                    volume = chapterData.optIntOrNull("v")?.coerceAtLeast(0) ?: 0,
                    url = chapterUrl,
                    uploadDate = chapterUploadDate(chapterData),
                    source = source,
                    scanlator = scanlator,
                    branch = scanlator,
                ),
            )
        }

        return chaptersBuilder.toList()
    }

    /**
     * The capture script emits an epoch timestamp (`c`) when the API payload
     * carried one, and only the site's relative label (`d`, "3 days ago") when
     * the row was read off the rendered list.
     */
    private fun chapterUploadDate(chapter: JSONObject): Long {
        chapter.optLongOrNull("c")?.let { raw ->
            return if (raw < SECONDS_TIMESTAMP_LIMIT) raw * 1000L else raw
        }
        return parseRelativeDate(chapter.optString("d"))
    }

    /** The branch a chapter belongs to — its scanlation team. */
    private fun teamNameOf(group: JSONObject?): String {
        return group?.optString("name")?.nullIfEmpty()
            ?: if (group?.optInt("o") == 1) "Official" else "Unknown"
    }

    private suspend fun loadAllChapters(hashId: String): JSONObject {
        val titleUrl = "https://$domain/title/$hashId"

        // The title page ships no chapters in `script#initial-data` — the list is
        // fetched over the signed XHR after hydration — so the page has to render
        // it for us. [CHAPTER_SCRIPT] scrapes the rendered list and walks the
        // pager, which is why it doesn't matter that our hooks are installed only
        // after the first request has already been made and parsed.
        val response = evaluateWebViewApiJson(titleUrl, CHAPTER_SCRIPT, CHAPTER_WEBVIEW_TIMEOUT)
        val items = response.optJSONArray("items")
            ?: throw ParseException("Comix chapter capture returned no items array", titleUrl)
        // `empty` means the page rendered its "No chapters match." state, i.e. the
        // title really has none — as opposed to us never seeing it render.
        if (items.length() == 0 && !response.optBoolean("empty")) {
            throw ParseException("Comix chapter list did not load", titleUrl)
        }
        return response
    }

    private fun extractInitialDataPages(document: Document): JSONObject? {
        val raw = document.selectFirst("script#initial-data")?.data()?.nullIfEmpty() ?: return null
        val queries = runCatching { JSONObject(raw).optJSONObject("queries") }.getOrNull() ?: return null
        for (key in queries.keys()) {
            val value = queries.optJSONObject(key) ?: continue
            if (value.optJSONObject("result")?.has("pages") == true) return value
            if (value.has("pages")) return JSONObject().put("result", value)
        }
        return null
    }

    private fun apiUrl(path: String): String = "https://$domain/api/v1/${path.removePrefix("/")}"

    private suspend fun evaluateWebViewApiJson(
        pageUrl: String,
        script: String,
        timeoutMs: Long = WEBVIEW_API_TIMEOUT,
    ): JSONObject {
        val bridgeScript = buildWebViewApiBridgeScript(script)
        val requests = runCatching {
            context.interceptWebViewRequests(
                pageUrl,
                InterceptionConfig(
                    timeoutMs = timeoutMs,
                    maxRequests = 1,
                    urlPattern = INTERCEPT_URL_REGEX,
                    pageScript = bridgeScript,
                ),
            )
        }.getOrElse { e ->
            throw ParseException("Comix WebView API interception failed", pageUrl, e)
        }
        val resultUrl = requests.firstOrNull()?.url
            ?: throw ParseException("Comix WebView API did not return a bridge result", pageUrl)
        val decoded = when {
            resultUrl.contains("/error", ignoreCase = true) -> {
                val message = resultUrl.queryParameterValue("msg") ?: "unknown WebView error"
                throw ParseException("Comix WebView API failed: $message", pageUrl)
            }
            else -> resultUrl.queryParameterValue("data")
                ?: throw ParseException("Comix WebView API bridge result missing data", pageUrl)
        }
        if (decoded == CLOUDFLARE_BLOCKED || isCloudflarePage(decoded)) {
            requestCloudflareVerification(pageUrl)
        }
        if (decoded.isBlank()) {
            throw ParseException("Comix WebView API returned an empty response", pageUrl)
        }
        val json = runCatching { JSONObject(decoded) }.getOrElse { e ->
            throw ParseException("Comix WebView API returned invalid JSON: ${decoded.take(200)}", pageUrl, e)
        }
        json.optString("error").nullIfEmpty()?.let { error ->
            throw ParseException("Comix WebView API failed: $error", pageUrl)
        }
        return json
    }

    private fun buildWebViewApiBridgeScript(script: String): String {
        return """
            (async function() {
                try {
                    const result = await $script;
                    window.location.href = "$INTERCEPT_RESULT_URL#data=" + encodeURIComponent(String(result || ""));
                } catch (e) {
                    window.location.href = "$INTERCEPT_ERROR_URL#msg=" + encodeURIComponent(String((e && e.message) || e));
                }
            })();
        """.trimIndent()
    }

    private fun requestCloudflareVerification(url: String, cause: Throwable? = null): Nothing {
        try {
            context.requestBrowserAction(this, url)
        } catch (e: UnsupportedOperationException) {
            throw ParseException(CLOUDFLARE_MESSAGE, url, cause ?: e)
        }
    }

    private fun String.queryParameterValue(name: String): String? {
        val query = substringAfter('#', substringAfter('?', ""))
        if (query.isEmpty()) return null
        return query.split('&')
            .asSequence()
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
    }

    private fun String.toJsString(): String {
        return "\"" + replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }

    private fun isCloudflarePage(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase(Locale.US)
        return lower.contains("<title>just a moment") ||
            ((lower.contains("just a moment") || lower.contains("checking your browser")) && lower.contains("cloudflare")) ||
            lower.contains("cf-browser-verification") ||
            lower.contains("cf-chl-opt") ||
            lower.contains("challenge-platform") ||
            lower.contains("challenges.cloudflare.com") ||
            lower.contains("cf-turnstile") ||
            lower.contains("turnstile") ||
            lower.contains("we're maintaining the site")
    }

    private fun parseTerms(json: JSONObject): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>()
        for (key in TERM_KEYS) {
            tags += parseTerms(json.optJSONArray(key))
        }
        return tags
    }

    private fun parseTerms(array: JSONArray?): Set<MangaTag> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNullTo(LinkedHashSet()) { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNullTo null
            val title = item.optString("title").nullIfEmpty()
                ?: item.optString("name").nullIfEmpty()
                ?: return@mapNotNullTo null
            // Prefer the numeric id — it's exactly what `genres_in[]` expects,
            // so a tag chip tapped on the details page filters correctly with
            // no name lookup. Fall back to the title for safety.
            val key = item.optIntOrNull("id")?.toString() ?: title
            MangaTag(
                key = key,
                title = title,
                source = source,
            )
        }
    }

    private val tagIdCache = ConcurrentHashMap<String, String>()

    /**
     * Resolve a genre/tag name to the numeric id the API uses in `genres_in[]`,
     * via the public /tags/search endpoint. Curated genres are looked up first
     * (`type=genre`), then the larger narrative-tag space (`type=tag`). Results
     * are cached; an empty string marks a name that matched nothing.
     */
    private suspend fun resolveTagId(name: String): String? {
        val cacheKey = name.trim().lowercase(Locale.US)
        if (cacheKey.isEmpty()) return null
        tagIdCache[cacheKey]?.let { return it.nullIfEmpty() }
        for (type in arrayOf("genre", "tag")) {
            val url = apiUrl("tags/search?type=$type&q=${name.urlEncoded()}")
            val result = runCatching {
                webClient.httpGet(url).parseJson().optJSONArray("result")
            }.getOrNull()
            val id = result?.optJSONObject(0)?.optIntOrNull("id")?.toString()
            if (id != null) {
                tagIdCache[cacheKey] = id
                return id
            }
        }
        tagIdCache[cacheKey] = ""
        return null
    }

    private fun parseAuthors(json: JSONObject): Set<String> {
        val authors = json.optJSONArray("authors") ?: json.optJSONArray("author") ?: return emptySet()
        return (0 until authors.length()).mapNotNullTo(LinkedHashSet()) { i ->
            val item = authors.optJSONObject(i) ?: return@mapNotNullTo null
            item.optString("title").nullIfEmpty() ?: item.optString("name").nullIfEmpty()
        }
    }

    private fun parseRelativeDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val match = RELATIVE_DATE_REGEX.find(date.trim().lowercase().removeSuffix(" ago")) ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val calendar = Calendar.getInstance()
        when (match.groupValues[2]) {
            "s", "sec", "secs" -> calendar.add(Calendar.SECOND, -amount)
            "m", "min", "mins" -> calendar.add(Calendar.MINUTE, -amount)
            "h", "hr", "hrs" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
            "d", "day", "days" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
            "w", "week", "weeks" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            "mo", "mos", "month", "months" -> calendar.add(Calendar.MONTH, -amount)
            "y", "yr", "yrs", "year", "years" -> calendar.add(Calendar.YEAR, -amount)
        }
        return calendar.timeInMillis
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key) else null
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        return if (has(key) && !isNull(key)) optLong(key) else null
    }

    private fun Float.toChapterUrlPart(): String {
        return if (this % 1f == 0f) {
            toInt().toString()
        } else {
            toString().trimEnd('0').trimEnd('.')
        }
    }

    private companion object {
        private val NSFW_RATINGS = setOf("erotica", "pornographic")
        private val TERM_KEYS = arrayOf("genres", "genre", "tags", "theme", "demographics", "demographic", "formats")
        private val ADULT_EXCLUDE_IDS = listOf("87264", "87266", "87268", "87265") // Adult, Hentai, Smut, Ecchi
        private const val SCRAMBLED_FRAGMENT = "scrambled"
        private const val GRID_COLS = 5
        private const val GRID_ROWS = 5
        private const val NUM_TILES = GRID_COLS * GRID_ROWS
        private const val LCG_MULTIPLIER = 1664525
        private const val LCG_INCREMENT = 1013904223
        private const val ENC_MULTIPLIER = 1000005
        private const val ENC_INCREMENT = 1234567891
        private val RELATIVE_DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""")
        private const val WEBVIEW_API_TIMEOUT = 90000L

        // Chapter collection is not time-boxed: [CHAPTER_SCRIPT] pages until the
        // site reports the list complete. This is only the ceiling for a WebView
        // that has stopped responding altogether, so it is deliberately far
        // higher than any real chapter list should need.
        private const val CHAPTER_WEBVIEW_TIMEOUT = 600000L

        // How long the script waits for one page to render before deciding the
        // list has stalled and returning what it already has.
        private const val CHAPTER_STALL_MS = 45000

        // Below this, a timestamp is seconds rather than milliseconds
        // (2286-11-20 in seconds, 1973-03-03 in milliseconds).
        private const val SECONDS_TIMESTAMP_LIMIT = 10_000_000_000L
        private const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"
        private const val INTERCEPT_RESULT_URL = "https://kotatsu.intercept/result"
        private const val INTERCEPT_ERROR_URL = "https://kotatsu.intercept/error"
        private val INTERCEPT_URL_REGEX = Regex("https://kotatsu\\.intercept/.*", RegexOption.IGNORE_CASE)
        private const val CLOUDFLARE_MESSAGE =
            "Cloudflare verification is required. Open Comix in the in-app browser, complete the check, then try again."

        private const val WEBVIEW_PAGE_ATTEMPTS = 3
        private const val WEBVIEW_PAGE_TIMEOUT = 20000L

        // Collects the whole chapter list off the title page.
        //
        // The script is injected once the page has finished loading, which is
        // normally *after* the SPA has already fetched and parsed the first page
        // of chapters — so hooking `JSON.parse`/`fetch`/XHR alone silently loses
        // it. The rendered list is therefore the primary source (it is there no
        // matter when we arrive) and the payload hooks only enrich the pages that
        // are fetched later, while we walk the pager.
        //
        // There is no page or item limit: it keeps paging until the site says the
        // list is complete, and only gives up if a page stops responding for
        // [CHAPTER_STALL_MS].
        //
        // The result crosses back as a URL fragment, so it is emitted in a
        // compact form — the shared URL prefix and the scanlation groups are sent
        // once and referenced by index, and absent fields are omitted:
        //   prefix  shared start of every chapter URL
        //   groups  [{ id?, name?, o }] — o = 1 when the group's release is official
        //   items   [{ i: id, n: number, u: url suffix, g: group index,
        //              v: volume?, t: name?, c: epoch seconds?, d: relative date? }]
        private val CHAPTER_SCRIPT = """
            (async () => {
                const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
                // A page turn is quick; only the initial render gets the full
                // stall allowance.
                const CLICK_TIMEOUT = 15000;
                const byId = new Map();
                const fromPayload = new Set();

                // Waits for the page to do something, rather than against an
                // overall budget: a long series may take as many pages as it
                // takes, we only bail when nothing moves for a whole stall.
                const waitFor = async (predicate, timeout) => {
                    const until = Date.now() + (timeout || $CHAPTER_STALL_MS);
                    while (Date.now() < until) {
                        if (predicate()) return true;
                        await sleep(100);
                    }
                    return false;
                };

                const text = (root, selector) => {
                    const node = root.querySelector(selector);
                    return node ? (node.textContent || '').trim() : '';
                };
                const number = (raw) => {
                    const match = /(-?[0-9]+(?:\.[0-9]+)?)/.exec(String(raw || '').replace(/,/g, ''));
                    return match ? Number(match[1]) : null;
                };
                const put = (chapter, isPayload) => {
                    if (!chapter || chapter.id == null) return;
                    const key = String(chapter.id);
                    // Payload rows carry the exact number and a real timestamp,
                    // so let them replace anything scraped for the same chapter.
                    if (byId.has(key) && !(isPayload && !fromPayload.has(key))) return;
                    byId.set(key, chapter);
                    if (isPayload) fromPayload.add(key);
                };

                // --- Payload hooks: enrich pages fetched from here on. ---
                const original = JSON.parse;
                const isChapterList = (arr) =>
                    Array.isArray(arr) && arr.length > 0 && arr[0] &&
                    arr[0].id !== undefined && arr[0].number !== undefined &&
                    arr[0].url !== undefined;
                const takePayload = (parsed) => {
                    try {
                        const result = parsed && parsed.result ? parsed.result : parsed;
                        const items = result && result.items;
                        if (!isChapterList(items)) return;
                        for (const ch of items) {
                            const group = ch.group || null;
                            put({
                                id: ch.id,
                                number: typeof ch.number === 'number' ? ch.number : number(ch.number),
                                volume: typeof ch.volume === 'number' ? ch.volume : null,
                                name: ch.name || null,
                                url: ch.url || null,
                                groupId: group && group.id != null ? group.id : null,
                                groupName: group && group.name ? group.name :
                                    (ch.isOfficial ? 'Official' : null),
                                official: !!ch.isOfficial,
                                createdAt: typeof ch.createdAt === 'number' ? ch.createdAt : null,
                                date: ch.createdAtFormatted || null
                            }, true);
                        }
                    } catch (e) {}
                };
                JSON.parse = function () {
                    const parsed = original.apply(this, arguments);
                    takePayload(parsed);
                    return parsed;
                };
                if (typeof window.fetch === 'function') {
                    const originalFetch = window.fetch;
                    window.fetch = function () {
                        return originalFetch.apply(this, arguments).then((response) => {
                            try {
                                response.clone().text().then((body) => {
                                    try { takePayload(original(body)); } catch (e) {}
                                }).catch(() => {});
                            } catch (e) {}
                            return response;
                        });
                    };
                }
                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function () {
                    this.addEventListener('load', function () {
                        try { takePayload(original(this.responseText)); } catch (e) {}
                    });
                    return originalSend.apply(this, arguments);
                };

                // --- The rendered list: always available, whatever our timing. ---
                const scrape = () => {
                    const rows = document.querySelectorAll('.mchap-list .mchap-item');
                    for (const row of rows) {
                        const link = row.querySelector('a.mchap-row__primary');
                        const href = link ? link.getAttribute('href') : null;
                        if (!href) continue;
                        // The id leads the last path segment; matching it
                        // anywhere would pick up a title slug that starts with
                        // digits instead, collapsing every chapter into one.
                        const slug = href.split('?')[0].split('/').filter(Boolean).pop() || '';
                        const idMatch = /^(\d+)-/.exec(slug);
                        if (!idMatch) continue;
                        const groupLink = row.querySelector('a.mchap-row__group');
                        const groupNode = groupLink || row.querySelector('.mchap-row__group');
                        const groupId = groupLink
                            ? /\/groups\/(\d+)/.exec(groupLink.getAttribute('href') || '')
                            : null;
                        const groupName = groupNode ? (groupNode.textContent || '').trim() : '';
                        put({
                            id: Number(idMatch[1]),
                            number: number(text(row, '.mchap-row__ch')),
                            volume: number(text(row, '.mchap-row__vol')),
                            name: text(row, '.mchap-row__title') || null,
                            url: href,
                            groupId: groupId ? Number(groupId[1]) : null,
                            groupName: groupName || null,
                            official: !!(groupNode && groupNode.classList.contains('is-official')),
                            createdAt: null,
                            date: text(row, '.mchap-row__time') || null
                        }, false);
                    }
                    return rows.length;
                };

                // --- Walk the pager. ---
                // `.mchap-foot__hint` reads "Showing 21 to 40 of 300 items", so it
                // is both the progress marker and the signal that a click landed.
                const hint = () => text(document, '.mchap-foot__hint');
                const isComplete = () => {
                    const match = /Showing\s+[\d,]+\s+to\s+([\d,]+)\s+of\s+([\d,]+)/i.exec(hint());
                    if (!match) return false;
                    return Number(match[1].replace(/,/g, '')) >= Number(match[2].replace(/,/g, ''));
                };
                const currentPage = () => {
                    const active = document.querySelector('.mchap-foot .npager button.npager__num.is-active');
                    const marked = active ? Number((active.textContent || '').trim()) : NaN;
                    if (marked > 0) return marked;
                    const match = /Showing\s+([\d,]+)\s+to\s+([\d,]+)/i.exec(hint());
                    if (!match) return 1;
                    const from = Number(match[1].replace(/,/g, ''));
                    const to = Number(match[2].replace(/,/g, ''));
                    const size = to - from + 1;
                    return size > 0 ? Math.floor((from - 1) / size) + 1 : 1;
                };

                /**
                 * The pager only draws its Next arrow while the numeric window
                 * has not yet reached the final page, so Next is already gone on
                 * the second-to-last page — and on every series short enough to
                 * fit the whole window, it never appears at all. The numbered
                 * button for the following page is always on screen though, so
                 * paging by number is what actually reaches the end.
                 */
                const nextButton = () => {
                    const buttons = document.querySelectorAll('.mchap-foot .npager button');
                    const wanted = currentPage() + 1;
                    for (const button of buttons) {
                        if (button.disabled) continue;
                        if (Number((button.textContent || '').trim()) === wanted) return button;
                    }
                    for (const button of buttons) {
                        if (button.disabled) continue;
                        const label = button.getAttribute('aria-label') || '';
                        if (/next/i.test(label)) return button;
                    }
                    return null;
                };
                const firstButton = () => {
                    const buttons = document.querySelectorAll('.mchap-foot .npager button');
                    for (const button of buttons) {
                        if (button.disabled) continue;
                        const label = button.getAttribute('aria-label') || '';
                        if (/first/i.test(label)) return button;
                    }
                    return null;
                };
                const total = () => {
                    const match = /of\s+([\d,]+)\s+items/i.exec(hint());
                    return match ? Number(match[1].replace(/,/g, '')) : 0;
                };
                const isEmptyState = () => !!document.querySelector('.mpage__chapters .uempty');
                const hasRows = () => !!document.querySelector('.mchap-list .mchap-item');

                // Identifies which rows are on screen, so a page is only read
                // once it has stopped changing — scraping the instant the first
                // row appears can catch a half-rendered list.
                const rowSignature = () => {
                    const rows = document.querySelectorAll('.mchap-list .mchap-item a.mchap-row__primary');
                    let signature = rows.length + ':';
                    for (const row of rows) signature += (row.getAttribute('href') || '') + ',';
                    return signature;
                };
                const settle = async () => {
                    let previous = null;
                    for (let i = 0; i < 100; i++) {
                        const current = rowSignature();
                        if (previous !== null && current === previous) return;
                        previous = current;
                        await sleep(100);
                    }
                };

                const walk = async () => {
                    while (!isComplete()) {
                        const button = nextButton();
                        if (!button) break;
                        const before = hint();
                        // Read the page being left as well, so a click that
                        // lands late cannot cost the rows already on screen.
                        scrape();
                        button.click();
                        // A click that does not register would otherwise cost a
                        // whole page, so give it one more go before bailing out.
                        if (!await waitFor(() => hint() !== before, CLICK_TIMEOUT)) {
                            const retry = nextButton();
                            if (!retry) break;
                            retry.click();
                            if (!await waitFor(() => hint() !== before, CLICK_TIMEOUT)) break;
                        }
                        await settle();
                        scrape();
                    }
                };

                await waitFor(() => hasRows() || isEmptyState());
                await settle();
                scrape();
                await walk();

                // The site reports how many chapters exist, so a short result
                // means a click never landed and a whole page was skipped.
                // Rewinding and walking once more recovers it.
                const expected = total();
                if (expected > 0 && byId.size < expected) {
                    const first = firstButton();
                    if (first) {
                        first.click();
                        await waitFor(() => /Showing\s+1\s+to/i.test(hint()));
                        await settle();
                        scrape();
                        await walk();
                    }
                }

                // --- Compact the result for the fragment-URL trip back. ---
                const collected = [...byId.values()];
                let prefix = collected.length ? String(collected[0].url || '') : '';
                for (const chapter of collected) {
                    const url = String(chapter.url || '');
                    let i = 0;
                    while (i < prefix.length && i < url.length && prefix[i] === url[i]) i++;
                    prefix = prefix.slice(0, i);
                }

                const groups = [];
                const groupIndex = new Map();
                const items = collected.map((chapter) => {
                    const official = chapter.official ? 1 : 0;
                    const key = (chapter.groupId != null ? 'i' + chapter.groupId : 'n' + (chapter.groupName || '')) +
                        '|' + official;
                    let g = groupIndex.get(key);
                    if (g === undefined) {
                        g = groups.length;
                        groupIndex.set(key, g);
                        const entry = { o: official };
                        if (chapter.groupId != null) entry.id = chapter.groupId;
                        if (chapter.groupName) entry.name = chapter.groupName;
                        groups.push(entry);
                    }
                    const row = {
                        i: chapter.id,
                        n: chapter.number,
                        u: String(chapter.url || '').slice(prefix.length),
                        g: g
                    };
                    if (chapter.volume != null) row.v = chapter.volume;
                    if (chapter.name) row.t = chapter.name;
                    if (chapter.createdAt != null) row.c = chapter.createdAt;
                    else if (chapter.date) row.d = chapter.date;
                    return row;
                });

                return JSON.stringify({
                    prefix: prefix,
                    groups: groups,
                    items: items,
                    empty: items.length === 0 && isEmptyState()
                });
            })()
        """

        // Browse results arrive via a signed, encrypted XHR the page decrypts in
        // JS, so we hook `JSON.parse` (catches the decrypted object), `fetch` and
        // `XMLHttpRequest` (catch plain responses), plus poll `script#initial-data`
        // as a backstop. Resolves with the first `{ result: { items: [...] } }`
        // payload as a JSON string for the bridge to hand back.
        private const val BROWSE_CAPTURE_SCRIPT = """
            (async () => {
                const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
                const original = JSON.parse;
                let captured = null;
                const take = (obj) => {
                    if (captured) return true;
                    try {
                        const items = obj && obj.result && obj.result.items;
                        if (Array.isArray(items) && items.length > 0) {
                            captured = JSON.stringify(obj);
                            return true;
                        }
                    } catch (e) {}
                    return false;
                };
                JSON.parse = function () {
                    const parsed = original.apply(this, arguments);
                    take(parsed);
                    return parsed;
                };
                if (typeof window.fetch === 'function') {
                    const originalFetch = window.fetch;
                    window.fetch = function () {
                        return originalFetch.apply(this, arguments).then((response) => {
                            try {
                                response.clone().text().then((text) => {
                                    try { take(original(text)); } catch (e) {}
                                }).catch(() => {});
                            } catch (e) {}
                            return response;
                        });
                    };
                }
                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function () {
                    this.addEventListener('load', function () {
                        try { take(original(this.responseText)); } catch (e) {}
                    });
                    return originalSend.apply(this, arguments);
                };
                for (let i = 0; i < 200; i++) {
                    if (captured) return captured;
                    try {
                        const node = document.querySelector('script#initial-data');
                        if (node && node.textContent) {
                            const queries = original(node.textContent).queries;
                            if (queries) {
                                for (const k in queries) {
                                    if (take(queries[k]) || take({ result: queries[k] })) break;
                                }
                            }
                        }
                    } catch (e) {}
                    await sleep(150);
                }
                return JSON.stringify({ error: 'no browse data captured' });
            })()
        """

        // Same capture technique, for the reader page's page list, recognised by
        // a `result.pages` object. Resolves with `{ result: { pages: ... } }`.
        private const val PAGE_CAPTURE_SCRIPT = """
            (async () => {
                const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
                const original = JSON.parse;
                let captured = null;
                const take = (obj) => {
                    if (captured) return true;
                    try {
                        const result = obj && obj.result ? obj.result : obj;
                        if (result && result.pages) {
                            captured = JSON.stringify({ result: result });
                            return true;
                        }
                    } catch (e) {}
                    return false;
                };
                JSON.parse = function () {
                    const parsed = original.apply(this, arguments);
                    take(parsed);
                    return parsed;
                };
                if (typeof window.fetch === 'function') {
                    const originalFetch = window.fetch;
                    window.fetch = function () {
                        return originalFetch.apply(this, arguments).then((response) => {
                            try {
                                response.clone().text().then((text) => {
                                    try { take(original(text)); } catch (e) {}
                                }).catch(() => {});
                            } catch (e) {}
                            return response;
                        });
                    };
                }
                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function () {
                    this.addEventListener('load', function () {
                        try { take(original(this.responseText)); } catch (e) {}
                    });
                    return originalSend.apply(this, arguments);
                };
                for (let i = 0; i < 200; i++) {
                    if (captured) return captured;
                    try {
                        const node = document.querySelector('script#initial-data');
                        if (node && node.textContent) {
                            const queries = original(node.textContent).queries;
                            if (queries) {
                                for (const k in queries) { if (take(queries[k])) break; }
                            }
                        }
                    } catch (e) {}
                    await sleep(150);
                }
                return JSON.stringify({ error: 'no page data captured' });
            })()
        """

        // Drives a WebView navigation past Cloudflare and returns the rendered
        // HTML. Resolves as soon as the SSR `script#initial-data` is present
        // (so we don't wait on the full `load` when the data is already there),
        // otherwise after a short cap — the caller decides if the document is
        // usable and retries the navigation if not.
        private const val PAGE_HTML_SCRIPT = """
            (() => new Promise((resolve) => {
                const finish = () => resolve(
                    document.documentElement ? document.documentElement.outerHTML : ""
                );
                const hasData = () => {
                    const node = document.querySelector('script#initial-data');
                    return !!(node && node.textContent && node.textContent.length > 50);
                };
                let waited = 0;
                const tick = () => {
                    if (hasData()) { finish(); return; }
                    waited += 250;
                    if (waited >= 12000) { finish(); return; }
                    setTimeout(tick, 250);
                };
                tick();
            }))()
        """
    }
}
