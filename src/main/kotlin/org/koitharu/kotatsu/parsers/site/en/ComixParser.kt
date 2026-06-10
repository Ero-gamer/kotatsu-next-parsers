package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
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
        val url = buildString {
            append(apiUrl("manga"))
            append("?")
            var firstParam = true
            fun addParam(param: String) {
                if (firstParam) {
                    append(param)
                    firstParam = false
                } else {
                    append("&").append(param)
                }
            }

            // Search keyword if provided
            if (!filter.query.isNullOrEmpty()) {
                addParam("keyword=${filter.query.urlEncoded()}")
            }

            // Use the provided sort order directly
            when (order) {
                SortOrder.RELEVANCE -> addParam("order[relevance]=desc")
                SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
                SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
                SortOrder.NEWEST -> addParam("order[created_at]=desc")
                SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
                else -> addParam("order[chapter_updated_at]=desc")
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
            addParam("limit=$pageSize")
            addParam("page=$page")
        }

        val response = getApiJson(url)
        val result = response.getJSONObject("result")
        val items = result.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseMangaFromJson(item)
        }
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
        val hashId = manga.url.substringAfter("/title/")
        val chaptersDeferred = async { getChapters(manga) }

        val response = getApiJson(apiUrl("manga/$hashId"))

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedManga = parseMangaFromJson(result)

            return@coroutineScope updatedManga.copy(
                chapters = chaptersDeferred.await(),
            )
        }

        return@coroutineScope manga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val response = webViewApiJson("/api/v1/chapters/$chapterId")
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
            // `s == 1` marks a tile-scrambled image. The interceptor descrambles
            // based on the response header, but tagging the URL keeps scrambled
            // pages from colliding with any unscrambled namesake in the cache.
            val finalUrl = if (item?.optInt("s", 0) == 1) "$imageUrl#$SCRAMBLED_FRAGMENT" else imageUrl
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
        // The image CDN signals a tile-scrambled image with this header; it's
        // the authoritative trigger (only scrambled images carry it, so API and
        // HTML responses pass straight through). A zero seed means "not scrambled".
        val seed = response.header("x-scramble-seed")?.toLongOrNull()?.toInt()
        if (seed == null || seed == 0) {
            return response
        }
        return context.redrawImageResponse(response) { bitmap ->
            descramble(bitmap, seed)
        }
    }

    // Reverses the site's 5x5 tile shuffle. The scramble order is a Fisher-Yates
    // permutation driven by an LCG seeded with the `x-scramble-seed` header, so
    // tile `i` of the scrambled image belongs at position `order[i]`.
    private fun descramble(source: Bitmap, seed: Int): Bitmap {
        val width = source.width
        val height = source.height
        val tileW = width / GRID_COLS
        val tileH = height / GRID_ROWS
        val order = buildScrambleOrder(seed, NUM_TILES)

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

    private fun buildScrambleOrder(seed: Int, n: Int): IntArray {
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

    private suspend fun getChapters(manga: Manga): List<MangaChapter> {
        val hashId = manga.url.substringAfter("/title/")
        val allChapters = loadAllChapters(hashId, manga.url.toAbsoluteUrl(domain))
        val chaptersBuilder = ChaptersListBuilder(allChapters.length())
        for (i in 0 until allChapters.length()) {
            val chapterData = allChapters.getJSONObject(i)
            val chapterId = chapterData.getLong("id")
            val number = chapterData.getDouble("number").toFloat()
            val name = chapterData.optString("name", "").nullIfEmpty()
            val scanlationGroup = chapterData.optJSONObject("group") ?: chapterData.optJSONObject("scanlation_group")
            val scanlator = scanlationGroup?.optString("name", null)
                ?: if (chapterData.optBoolean("isOfficial")) "Official" else "Unknown"
            val title = if (name != null) {
                "Chapter $number: $name"
            } else {
                "Chapter $number"
            }
            chaptersBuilder.add(
                MangaChapter(
                    id = generateUid("$scanlator-$chapterId"),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/title/$hashId/$chapterId-chapter-${number.toChapterUrlPart()}",
                    uploadDate = parseRelativeDate(chapterData.optString("createdAtFormatted")),
                    source = source,
                    scanlator = scanlator,
                    branch = scanlator,
                ),
            )
        }

        return chaptersBuilder.toList().reversed()
    }

    private suspend fun loadAllChapters(hashId: String, pageUrl: String): JSONArray {
        val apiPath = "/api/v1/manga/$hashId/chapters"
        val response = evaluateWebViewApiJson(
            pageUrl = pageUrl,
            script = buildWebViewApiScript(
                """
                    const basePath = ${apiPath.toJsString()};
                    const limit = 100;
                    const items = [];
                    const seen = new Set();
                    for (let page = 1; page <= 200; page++) {
                        const payload = await fetchProtected(basePath + "?limit=" + limit + "&page=" + page);
                        const result = payload && payload.result ? payload.result : payload;
                        const pageItems = Array.isArray(result && result.items) ? result.items : [];
                        const meta = (result && (result.meta || result.pagination)) || {};
                        const currentPage = Number((meta && (meta.page || meta.currentPage)) || page);
                        const pageKey = pageItems.length > 0 ?
                            String(pageItems[0].id) + ":" + String(pageItems[pageItems.length - 1].id) :
                            "empty:" + page;
                        if (seen.has(pageKey)) break;
                        seen.add(pageKey);
                        for (const item of pageItems) items.push(item);

                        const hasNext = meta && (meta.hasNext === true || meta.hasNext === "true" ||
                            meta.hasNext === 1 || meta.hasNext === "1");
                        const hasNextFalse = meta && (meta.hasNext === false || meta.hasNext === "false" ||
                            meta.hasNext === 0 || meta.hasNext === "0");
                        const lastPage = Number(meta && (meta.lastPage || meta.totalPages || meta.pages ||
                            meta.pageCount || meta.last_page || meta.total_pages));
                        if (pageItems.length === 0 || hasNextFalse || (lastPage && currentPage >= lastPage)) {
                            break;
                        }
                        if (!hasNext && pageItems.length < limit) {
                            break;
                        }
                    }
                    return JSON.stringify({ items: items });
                """.trimIndent(),
            ),
        )
        return response.optJSONArray("items") ?: JSONArray()
    }

    private fun apiUrl(path: String): String = "https://$domain/api/v1/${path.removePrefix("/")}"

    /**
     * Plain GET against the JSON API (popular/latest/search/details). Matches the
     * upstream Keiyoushi behaviour — these endpoints aren't request-signed. If the
     * response isn't JSON it's almost certainly a Cloudflare interstitial, so we
     * hand off to the in-app browser to clear it.
     */
    private suspend fun getApiJson(url: String): JSONObject {
        val response = webClient.httpGet(url)
        return runCatching { response.parseJson() }.getOrElse { e ->
            requestCloudflareVerification(url, e)
        }
    }

    private suspend fun webViewApiJson(apiPath: String): JSONObject {
        return evaluateWebViewApiJson(
            pageUrl = "https://$domain/?kotatsu_comix_bridge=${System.currentTimeMillis()}",
            script = buildWebViewApiScript("return JSON.stringify(await fetchProtected(${apiPath.toJsString()}));"),
        )
    }

    private suspend fun evaluateWebViewApiJson(pageUrl: String, script: String): JSONObject {
        val bridgeScript = buildWebViewApiBridgeScript(script)
        val requests = runCatching {
            context.interceptWebViewRequests(
                pageUrl,
                InterceptionConfig(
                    timeoutMs = WEBVIEW_API_TIMEOUT,
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

    private fun buildWebViewApiScript(body: String): String {
        return """
            (async () => {
                const probePath = "/manga/g2rk/chapters";
                const tokenRegex = /^[A-Za-z0-9_-]{20,200}$/;
                const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
                const challengeDetected = () => {
                    const root = document.documentElement;
                    const html = (root && root.outerHTML) || "";
                    const text = ((document.body && document.body.innerText) || (root && root.innerText) || "");
                    const lower = (document.title + "\n" + text + "\n" + html).toLowerCase();
                    return document.querySelector('script[src*="challenge-platform"]') !== null ||
                        document.querySelector('script[src*="turnstile"]') !== null ||
                        document.querySelector('iframe[src*="challenges.cloudflare.com"]') !== null ||
                        document.querySelector('.cf-turnstile') !== null ||
                        document.querySelector('form[action*="__cf_chl"]') !== null ||
                        document.querySelector('.cf-browser-verification') !== null ||
                        ((lower.includes('just a moment') || lower.includes('checking your browser')) && lower.includes('cloudflare')) ||
                        lower.includes('challenge-platform') ||
                        lower.includes('challenges.cloudflare.com') ||
                        lower.includes('cf-turnstile') ||
                        lower.includes('turnstile') ||
                        lower.includes('cf-chl-opt');
                };
                const findGlue = () => {
                    let signer = null;
                    let installer = null;
                    let responseHandler = null;
                    const keys = Object.keys(window);
                    for (let i = 0; i < keys.length; i++) {
                        const topName = keys[i];
                        if (!/^vm[A-Za-z]_\w+${'$'}/.test(topName)) continue;
                        const ns = window[topName];
                        if (!ns || typeof ns !== "object") continue;
                        const fnames = Object.keys(ns);
                        for (let j = 0; j < fnames.length; j++) {
                            const fn = ns[fnames[j]];
                            if (typeof fn !== "function") continue;
                            if (!signer) {
                                try {
                                    const out = fn(probePath);
                                    if (typeof out === "string" && out !== probePath && tokenRegex.test(out)) {
                                        signer = fn;
                                    }
                                } catch (e) {}
                            }
                            if (!installer) {
                                try {
                                    let got = false;
                                    let resFn = null;
                                    const fakeAxios = {
                                        interceptors: {
                                            request: { use: function() {} },
                                            response: { use: function(fn) { got = true; resFn = fn; } }
                                        },
                                        defaults: { headers: { common: {} }, transformRequest: [], transformResponse: [] }
                                    };
                                    fn(fakeAxios);
                                    if (got) {
                                        installer = fn;
                                        responseHandler = resFn;
                                    }
                                } catch (e) {}
                            }
                            if (signer && installer) return { signer, installer, responseHandler };
                        }
                    }
                    return null;
                };

                try {
                    let glue = null;
                    for (let attempt = 0; attempt < 80; attempt++) {
                        if (challengeDetected()) {
                            return "$CLOUDFLARE_BLOCKED";
                        }
                        glue = findGlue();
                        if (glue) break;
                        await sleep(250);
                    }
                    if (!glue) throw new Error("signer/decryptor not detected");

                    const captured = { res: glue.responseHandler || null };
                    if (!captured.res) {
                        const fakeAxios = {
                            interceptors: {
                                request: { use: function() {} },
                                response: { use: function(fn) { captured.res = fn; } }
                            },
                            defaults: { headers: { common: {} }, transformRequest: [], transformResponse: [] }
                        };
                        glue.installer(fakeAxios);
                    }

                    const signCandidates = (apiPath) => {
                        const withoutApi = apiPath.replace(/^\/api\/v1/, "");
                        const withoutQuery = withoutApi.split("?")[0];
                        const decoded = (() => {
                            try { return decodeURIComponent(withoutApi); } catch (e) { return withoutApi; }
                        })();
                        return [...new Set([withoutApi, decoded, withoutQuery])];
                    };

                    const fetchProtected = async (apiPath) => {
                        const sep = apiPath.indexOf("?") === -1 ? "?" : "&";
                        let resp = null;
                        let text = "";
                        let signedUrl = "";
                        let lastError = "";
                        const candidates = signCandidates(apiPath);
                        for (const signablePath of candidates) {
                            const sig = glue.signer(signablePath);
                            if (!sig) {
                                lastError = "signer returned empty token";
                                continue;
                            }
                            signedUrl = apiPath + sep + "_=" + encodeURIComponent(sig);
                            resp = await fetch(signedUrl, {
                                credentials: "include",
                                headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" }
                            });
                            text = await resp.text();
                            if (resp.status >= 200 && resp.status < 300) break;
                            lastError = "HTTP " + resp.status + " signed=" + signablePath + ": " + text.slice(0, 200);
                            if (resp.status !== 422) break;
                        }
                        if (!resp) throw new Error(lastError || "request was not sent");
                        if (resp.status < 200 || resp.status >= 300) {
                            throw new Error(lastError || ("HTTP " + resp.status + ": " + text.slice(0, 200)));
                        }
                        const raw = JSON.parse(text);
                        if (raw && typeof raw === "object" && "e" in raw && captured.res) {
                            const fakeResp = {
                                data: raw,
                                status: resp.status,
                                statusText: resp.statusText,
                                headers: Object.fromEntries([...resp.headers.entries()]),
                                config: { url: signedUrl, method: "get", baseURL: "/api/v1" },
                                request: {}
                            };
                            const decoded = await captured.res(fakeResp);
                            return { result: decoded && decoded.data };
                        }
                        if (raw && typeof raw === "object" && "e" in raw) {
                            throw new Error("encrypted response received but decryptor was not captured");
                        }
                        if (raw && typeof raw === "object" && "result" in raw) return raw;
                        return { result: raw };
                    };

                    $body
                } catch (e) {
                    return JSON.stringify({ error: String((e && e.message) || e) });
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
        private val RELATIVE_DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""")
        private const val WEBVIEW_API_TIMEOUT = 90000L
        private const val CLOUDFLARE_BLOCKED = "CLOUDFLARE_BLOCKED"
        private const val INTERCEPT_RESULT_URL = "https://kotatsu.intercept/result"
        private const val INTERCEPT_ERROR_URL = "https://kotatsu.intercept/error"
        private val INTERCEPT_URL_REGEX = Regex("https://kotatsu\\.intercept/.*", RegexOption.IGNORE_CASE)
        private const val CLOUDFLARE_MESSAGE =
            "Cloudflare verification is required. Open Comix in the in-app browser, complete the check, then try again."
    }
}
