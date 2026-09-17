package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import org.jsoup.HttpStatusException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("BATCAVE", "BatCave", "en", ContentType.COMICS)
internal class BatCave(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.BATCAVE, 20) {

	override val configKeyDomain = ConfigKey.Domain("batcave.biz")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        val headersBuilder = request.headers.newBuilder()

        // Set appropriate Referer based on the URL
        headersBuilder.removeAll("Referer")
        when {
            url.contains("readcomicsonline.ru") -> {
                headersBuilder.add("Referer", "https://readcomicsonline.ru/")
            }
            else -> {
                headersBuilder.add("Referer", "https://$domain/")
            }
        }

        val newRequest = request.newBuilder()
            .headers(headersBuilder.build())
            .build()

        return chain.proceed(newRequest)
    }

	private val dleGuardMutex = Mutex()

	@Volatile
	private var lastGuardSolveAt = 0L

	private val availableTags = suspendLazy(initializer = ::fetchTags)
	private val captureAllPattern = Regex(".*")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isYearRangeSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
	)

	private suspend fun captureDocument(
		initialUrl: String,
		preferredMatch: Regex? = null,
		timeoutMs: Long = 15000L,
		allowBrowserAction: Boolean = true,
	): Document {
		// Try HTTP first - only use WebView if Cloudflare protection is detected
		tryHttpDocument(initialUrl)?.let { doc ->
			return doc
		}

		// DLE Guard redirects to /_c until a WebView sets the trust cookie; solve it and retry
		if (solveDleGuard(initialUrl)) {
			tryHttpDocument(initialUrl)?.let { doc ->
				return doc
			}
		}

		// HTTP failed, likely due to Cloudflare protection - try WebView
		loadDocumentViaWebView(initialUrl)?.let { doc ->
			return doc
		}

		val capturedUrls = try {
			context.captureWebViewUrls(
				pageUrl = initialUrl,
				urlPattern = captureAllPattern,
				timeout = timeoutMs,
			)
		} catch (e: Exception) {
			throw ParseException("Failed to capture webview URLs", initialUrl, e)
		}

		if (capturedUrls.isEmpty()) {
			throw ParseException("WebView did not produce any matching requests", initialUrl)
		}

		val resolvedUrl = preferredMatch?.let { pattern ->
			capturedUrls.firstOrNull { pattern.containsMatchIn(it) }
		} ?: capturedUrls.firstOrNull { url ->
			url.startsWith("https://$domain") || url.startsWith("http://$domain")
		} ?: capturedUrls.firstOrNull()

		val finalUrl = resolvedUrl ?: initialUrl

		loadDocumentViaWebView(finalUrl)?.let { doc ->
			return doc
		}

		if (allowBrowserAction) {
			context.requestBrowserAction(this, finalUrl)
			throw ParseException("Browser action requested for Cloudflare bypass", finalUrl)
		}

		throw ParseException("Failed to load page via webview", finalUrl)
	}

	private suspend fun tryHttpDocument(url: String): Document? {
		val response = runCatching { webClient.httpGet(url) }.getOrNull() ?: return null
		return response.use { res ->
			if (res.request.url.isDleGuard()) {
				return null
			}
			val doc = runCatching { res.parseHtml() }.getOrNull() ?: return null

			// Check for successful BatCave content first
			if (hasValidBatCaveContent(doc)) {
				return doc
			}

			// Only reject if it's clearly an active Cloudflare challenge
			val html = doc.outerHtml()
			if (isActiveCloudflareChallenge(html)) {
				return null
			}

			// If we're not sure, allow the page through
			doc
		}
	}


	private suspend fun loadDocumentViaWebView(url: String): Document? {
		val script = """
			(() => {
				return new Promise(resolve => {
					const finish = () => {
						resolve(document.documentElement ? document.documentElement.outerHTML : "");
					};
					if (document.readyState === "complete") {
						setTimeout(finish, 200);
					} else {
						window.addEventListener("load", () => setTimeout(finish, 200), { once: true });
					}
					setTimeout(finish, 3000);
				});
			})();
		""".trimIndent()

		val html = context.evaluateJs(url, script, timeout = 10000L) ?: return null
		if (html.isBlank()) {
			return null
		}

		val doc = Jsoup.parse(html, url)

		// Check for successful BatCave content instead of rejecting Cloudflare
		if (hasValidBatCaveContent(doc)) {
			return doc
		}

		if (isDleGuardPage(doc)) {
			return null
		}

		// Only reject if it's clearly an active Cloudflare challenge page
		if (isActiveCloudflareChallenge(html)) {
			return null
		}

		// If we're not sure, allow the page through
		return doc
	}

	private suspend fun solveDleGuard(url: String): Boolean {
		return dleGuardMutex.withLock {
			// Parallel requests hitting the guard at the same time reuse a fresh solve
			if (System.currentTimeMillis() - lastGuardSolveAt < GUARD_TRUST_WINDOW_MS) {
				return@withLock true
			}
			// The challenge page runs its check and redirects back once the trust cookie is set
			val script = """
				(() => new Promise(resolve => {
					const started = Date.now();
					const check = () => {
						if (!location.pathname.startsWith("/_c") || document.cookie.indexOf("$DLE_GUARD_COOKIE=") >= 0) {
							resolve("ok");
						} else if (Date.now() - started > 25000) {
							resolve("timeout");
						} else {
							setTimeout(check, 250);
						}
					};
					check();
				}))();
			""".trimIndent()
			runCatchingCancellable { context.evaluateJs(url, script, timeout = 30000L) }
			hasDleGuardTrust().also { solved ->
				if (solved) lastGuardSolveAt = System.currentTimeMillis()
			}
		}
	}

	private fun hasDleGuardTrust(): Boolean =
		context.cookieJar.getCookies(domain).any { it.name == DLE_GUARD_COOKIE }

	private fun HttpUrl.isDleGuard(): Boolean = pathSegments.firstOrNull() == "_c"

	private fun isDleGuardPage(doc: Document): Boolean =
		doc.location().toHttpUrlOrNull()?.isDleGuard() == true

	private fun hasValidBatCaveContent(doc: Document): Boolean {
		if (isDleGuardPage(doc)) {
			return false
		}
		// Check for BatCave-specific content that indicates successful load
		return doc.select("#dle-content > .readed, div.readed.d-flex.short").isNotEmpty() ||
			doc.select("script:containsData(__DATA__)").isNotEmpty() ||
			doc.select("script:containsData(__XFILTER__)").isNotEmpty() ||
			doc.select("h1.serie-title").isNotEmpty() ||
			doc.title().contains("BatCave", ignoreCase = true)
	}

	private fun isActiveCloudflareChallenge(html: String): Boolean {
		if (html.length < 100) {
			return true
		}
		val lower = html.lowercase()
		// Only reject pages that are clearly active challenge pages
		return (lower.contains("just a moment") && lower.contains("cloudflare")) ||
			(lower.contains("checking your browser") && lower.contains("cloudflare")) ||
			lower.contains("cf-browser-verification") ||
			lower.contains("cf-chl-opt")
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Sec-Fetch-Dest", "document")
		.set("Sec-Fetch-Mode", "navigate")
		.set("Sec-Fetch-Site", "none")
		.set("Sec-Fetch-User", "?1")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val pagePath = if (page > 1) "page/$page/" else ""
		val (sortBy, direction) = when (order) {
			SortOrder.POPULARITY -> "rating" to "desc"
			SortOrder.NEWEST -> "date" to "desc"
			SortOrder.ALPHABETICAL -> "title" to "asc"
			else -> "editdate" to "desc"
		}
		return when {
			!filter.query.isNullOrEmpty() -> {
				// The query is a single path segment and the site expects a trailing slash
				val url = "https://$domain".toHttpUrl().newBuilder()
					.addPathSegment("search")
					.addPathSegment(filter.query.trim())
				if (page > 1) {
					url.addPathSegment("page").addPathSegment(page.toString())
				}
				parseReadedList(fetchDocument(url.addPathSegment("").build().toString()))
			}

			filter.tags.isNotEmpty() || filter.yearFrom != YEAR_UNKNOWN || filter.yearTo != YEAR_UNKNOWN -> {
				val filterPath = buildString {
					if (filter.yearFrom != YEAR_UNKNOWN) append("y[from]=").append(filter.yearFrom).append('/')
					if (filter.yearTo != YEAR_UNKNOWN) append("y[to]=").append(filter.yearTo).append('/')
					if (filter.tags.isNotEmpty()) append("g=").append(filter.tags.joinToString(",") { it.key }).append('/')
				}
				val form = mapOf(
					"dlenewssortby" to sortBy,
					"dledirection" to direction,
					"set_new_sort" to "dle_sort_xfilter",
					"set_direction_sort" to "dle_direction_xfilter",
				)
				parseReadedList(fetchDocument("https://$domain/ComicList/$filterPath$pagePath", form))
			}

			// The home page is the only list sorted by last chapter update
			order == SortOrder.UPDATED -> parseLatestList(fetchDocument("https://$domain/$pagePath"))

			else -> {
				val form = mapOf(
					"dlenewssortby" to sortBy,
					"dledirection" to direction,
					"set_new_sort" to "dle_sort_cat_1",
					"set_direction_sort" to "dle_direction_cat_1",
				)
				parseReadedList(fetchDocument("https://$domain/comix/$pagePath", form))
			}
		}
	}

	/**
	 * Loads a list page, solving the DLE Guard once when the request is redirected to its challenge.
	 * The challenge page answers 404, so the redirect also shows up as a failed request.
	 */
	private suspend fun fetchDocument(url: String, form: Map<String, String>? = null): Document {
		var guardSolved = false
		while (true) {
			val result = runCatchingCancellable {
				if (form == null) webClient.httpGet(url) else webClient.httpPost(url.toHttpUrl(), form)
			}
			val response = result.getOrNull()
			val error = result.exceptionOrNull()
			val isGuarded = response?.request?.url?.isDleGuard() == true ||
				(error as? HttpStatusException)?.url?.toHttpUrlOrNull()?.isDleGuard() == true
			if (!isGuarded) {
				if (error != null) throw error
				val doc = checkNotNull(response).parseHtml()
				if (isActiveCloudflareChallenge(doc.outerHtml())) {
					context.requestBrowserAction(this, url)
				}
				return doc
			}
			response?.close()
			// The guard can only be solved by loading a page, POST requests are replayed after solving it on the home page
			if (guardSolved || !solveDleGuard(if (form == null) url else "https://$domain/")) {
				context.requestBrowserAction(this, url)
			}
			guardSolved = true
		}
	}

	private fun parseLatestList(doc: Document): List<Manga> {
		return doc.select("#content-load > .latest.grid-item").mapNotNull { item ->
			val titleElement = item.selectFirst(".latest__title > a") ?: return@mapNotNull null
			val title = titleElement.ownText().trim().ifEmpty { return@mapNotNull null }
			val href = titleElement.attrAsRelativeUrl("href")
			val img = item.selectFirst(".latest__img img")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = titleElement.attrAsAbsoluteUrl("href"),
				title = title,
				altTitles = emptySet(),
				authors = emptySet(),
				description = null,
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				coverUrl = img?.attrAsAbsoluteUrlOrNull("src") ?: img?.attrAsAbsoluteUrlOrNull("data-src"),
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
			)
		}
	}

	private fun parseReadedList(doc: Document): List<Manga> {
		val items = doc.select("#dle-content > .readed").ifEmpty { doc.select("div.readed.d-flex.short") }
		return items.mapNotNull { item ->
			val titleElement = item.selectFirst(".readed__title > a") ?: return@mapNotNull null
			val img = item.selectFirst(".readed__img img")
			val href = titleElement.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = titleElement.attrAsAbsoluteUrl("href"),
				title = titleElement.ownText().ifEmpty { titleElement.text() },
				altTitles = emptySet(),
				authors = emptySet(),
				description = null,
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				coverUrl = img?.attrAsAbsoluteUrlOrNull("data-src") ?: img?.attrAsAbsoluteUrlOrNull("src"),
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = captureDocument(manga.url.toAbsoluteUrl(domain))

		val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)

		val scriptData = doc.selectFirst("script:containsData(__DATA__)")?.data()
			?.let { data ->
				val jsonStart = data.indexOf("window.__DATA__ = ") + "window.__DATA__ = ".length
				val jsonEnd = data.indexOf("};", startIndex = jsonStart)
				if (jsonEnd != -1) {
					// substring, include "}" symbol
					data.substring(jsonStart, jsonEnd + 1)
				} else {
					null
				}
			} ?: doc.parseFailed("Script data not found")

		val jsonData = JSONObject(scriptData)
		val newsId = jsonData.getLong("news_id")
		val chaptersJson = jsonData.getJSONArray("chapters")

		val chapters = List(chaptersJson.length()) { i ->
			val chapter = chaptersJson.getJSONObject(i)
			val chapterId = chapter.getLong("id")

			MangaChapter(
				id = generateUid("$newsId/$chapterId"),
				url = "/reader/$newsId/$chapterId",
				number = chapter.getFloatOrDefault("posi", 0f),
				title = chapter.getStringOrNull("title"),
				uploadDate = dateFormat.parseSafe(chapter.getStringOrNull("date")),
				source = source,
				scanlator = null,
				branch = null,
				volume = 0,
			)
		}.reversed()

		val author = doc.selectFirst("li:contains(Publisher:)")
			?.textOrNull()
			?.substringAfter("Publisher:")
			?.trim()
			?.nullIfEmpty()
		val state = when (
			doc.selectFirst("li:contains(Release type:)")?.text()?.substringAfter("Release type:")?.trim()
		) {
			"Ongoing" -> MangaState.ONGOING
			else -> MangaState.FINISHED
		}

		val tagLinks = doc.getElementsByAttributeValueContaining("href", "/genres/")
		val tags = if (tagLinks.isNotEmpty()) {
			availableTags.getOrNull()?.let { allTags ->
				tagLinks.mapNotNullToSet { a ->
					val tagName = a.text()
					allTags.find { it.title.equals(tagName, ignoreCase = true) }
				}
			}
		} else {
			null
		}

		return manga.copy(
			authors = setOfNotNull(author),
			state = state,
			chapters = chapters,
			description = doc.select("div.page__text.full-text.clearfix").textOrNull(),
			tags = tags ?: manga.tags,
		)
	}

	private companion object {

		const val DLE_GUARD_COOKIE = "__guard_trust"
		const val GUARD_TRUST_WINDOW_MS = 5_000L
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = captureDocument(chapter.url.toAbsoluteUrl(domain))
		val data = doc.selectFirst("script:containsData(__DATA__)")?.data()
			?.substringAfter("=")
			?.trim()
			?.removeSuffix(";")
			?.substringAfter("\"images\":[")
			?.substringBefore("]")
			?.split(",")
			?.map { it.trim().removeSurrounding("\"").replace("\\", "") }
			?: throw ParseException("Image data not found", chapter.url)

		return data.map { imageUrl ->
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = captureDocument("https://$domain/comix/")
		val scriptData = doc.selectFirstOrThrow("script:containsData(__XFILTER__)").data()

		val genresJson = scriptData
			.substringAfter("\"g\":{")
			.substringBefore("}}}") + "}"

		val genresObj = JSONObject("{$genresJson}")
		val valuesArray = genresObj.getJSONArray("values")

		return Set(valuesArray.length()) { i ->
			val genre = valuesArray.getJSONObject(i)
			MangaTag(
				key = genre.getInt("id").toString(),
				title = genre.getString("value").toTitleCase(sourceLocale),
				source = source,
			)
		}
	}
}
