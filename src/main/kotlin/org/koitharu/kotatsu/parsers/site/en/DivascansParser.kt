package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * Parser for [DivaScans](https://divascans.org/).
 *
 * List/search/genres use the public JSON API; details (full chapter list) and
 * pages are scraped from the Next.js HTML responses.
 */
@MangaSourceParser("DIVASCANS", "DivaScans", "en", type = ContentType.MANHWA)
internal class DivascansParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DIVASCANS, 20) {

	override val configKeyDomain = ConfigKey.Domain("divascans.org")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.ABANDONED,
			MangaState.UPCOMING,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.MANGA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotEmpty()) {
			return searchSeries(query, page)
		}

		val url = "https://$domain/api/series".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", pageSize.toString())
			.apply {
				filter.tags.oneOrThrowIfMany()?.let { tag ->
					addQueryParameter("genre", tag.key)
				}
				filter.states.oneOrThrowIfMany()?.let { state ->
					addQueryParameter(
						"status",
						when (state) {
							MangaState.ONGOING -> "ONGOING"
							MangaState.FINISHED -> "COMPLETED"
							MangaState.ABANDONED -> "DROPPED"
							MangaState.UPCOMING -> "COMING_SOON"
							else -> return@let
						},
					)
				}
				filter.types.oneOrThrowIfMany()?.let { type ->
					addQueryParameter(
						"type",
						when (type) {
							ContentType.MANHWA -> "MANHWA"
							ContentType.MANHUA -> "MANHUA"
							ContentType.MANGA -> "MANGA"
							else -> return@let
						},
					)
				}
				// Best-effort sort params — API may ignore unknown ones.
				when (order) {
					SortOrder.POPULARITY -> {
						addQueryParameter("sortBy", "viewCount")
						addQueryParameter("sortOrder", "desc")
					}
					SortOrder.RATING -> {
						addQueryParameter("sortBy", "rating")
						addQueryParameter("sortOrder", "desc")
					}
					SortOrder.NEWEST -> {
						addQueryParameter("sortBy", "createdAt")
						addQueryParameter("sortOrder", "desc")
					}
					SortOrder.ALPHABETICAL -> {
						addQueryParameter("sortBy", "title")
						addQueryParameter("sortOrder", "asc")
					}
					else -> {
						addQueryParameter("sortBy", "updatedAt")
						addQueryParameter("sortOrder", "desc")
					}
				}
			}
			.build()

		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()
		return parseSeriesArray(data)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = extractSlug(manga.url)
		val typePath = extractTypePath(manga.url)
		val pageUrl = "https://$domain/series/$typePath/$slug"
		val html = webClient.httpGet(pageUrl).parseHtml().outerHtml()
		val chapters = parseChaptersFromHtml(html, typePath, slug)
		val description = extractDescription(html) ?: manga.description
		val author = extractAuthor(html)
		val isMature = html.contains("\"isMature\":true") || html.contains("\\\"isMature\\\":true")

		return manga.copy(
			description = description,
			authors = author?.let { setOf(it) } ?: manga.authors,
			contentRating = when {
				isMature || manga.contentRating == ContentRating.ADULT -> ContentRating.ADULT
				else -> manga.contentRating
			},
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val html = webClient.httpGet(fullUrl).parseHtml().outerHtml()
		// Full pages use p-*.webp; s-*.webp are thumbs / sprites.
		val pageUrls = PAGE_URL_REGEX.findAll(html)
			.map { it.value }
			.distinct()
			.toList()
		if (pageUrls.isEmpty()) {
			// Locked / premium chapters often render without page images.
			if (
				html.contains("\"isLocked\":true") ||
				html.contains("\\\"isLocked\\\":true")
			) {
				throw ParseException("Chapter is locked on DivaScans", fullUrl)
			}
			throw ParseException("No pages found", fullUrl)
		}
		return pageUrls.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun searchSeries(query: String, page: Int): List<Manga> {
		val url = "https://$domain/api/search".toHttpUrl().newBuilder()
			.addQueryParameter("q", query)
			.addQueryParameter("page", page.toString())
			.build()
		val json = webClient.httpGet(url).parseJson()
		val series = json.optJSONArray("series") ?: return emptyList()
		return parseSeriesArray(series)
	}

	private fun parseSeriesArray(data: JSONArray): List<Manga> {
		val result = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			val item = data.optJSONObject(i) ?: continue
			parseSeriesItem(item)?.let(result::add)
		}
		return result
	}

	private fun parseSeriesItem(item: JSONObject): Manga? {
		val slug = item.optString("urlSlug").nullIfEmpty()
			?: item.optString("slug").nullIfEmpty()
			?: return null
		val title = item.optString("title").nullIfEmpty() ?: return null
		val type = item.optString("type").ifEmpty { "MANHWA" }
		val typePath = typeToPath(type)
		val mangaUrl = "/series/$typePath/$slug"
		val cover = item.optString("coverImage").nullIfEmpty()?.let { absUrl(it) }
		val rating = item.optDouble("rating", Double.NaN).let { value ->
			if (value.isNaN() || value <= 0.0) RATING_UNKNOWN else (value / 10.0).toFloat().coerceIn(0f, 1f)
		}
		val isMature = item.optBoolean("isMature", false) ||
			item.optJSONArray("nsfwGenreSlugs")?.length()?.let { it > 0 } == true

		val tags = buildSet {
			val genres = item.optJSONArray("genres")
			if (genres != null) {
				for (i in 0 until genres.length()) {
					val g = genres.optJSONObject(i) ?: continue
					val genreObj = g.optJSONObject("genre") ?: g
					val key = genreObj.optString("slug").nullIfEmpty() ?: continue
					val name = genreObj.optString("name").nullIfEmpty() ?: key
					add(MangaTag(key = key, title = name.toTitleCase(sourceLocale), source = source))
				}
			}
		}

		return Manga(
			id = generateUid(mangaUrl),
			url = mangaUrl,
			publicUrl = mangaUrl.toAbsoluteUrl(domain),
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			altTitles = emptySet(),
			authors = emptySet(),
			tags = tags,
			rating = rating,
			state = mapStatus(item.optString("status")),
			contentRating = if (isMature) ContentRating.ADULT else ContentRating.SAFE,
			source = source,
			description = null,
		)
	}

	private fun parseChaptersFromHtml(html: String, typePath: String, slug: String): List<MangaChapter> {
		// HTML embeds RSC flight payloads with heavily escaped JSON chapter objects.
		var text = html
		repeat(3) {
			text = text.replace("\\\"", "\"").replace("\\\\", "\\")
		}
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val regex = Regex(
			""""id":"(cm[a-z0-9]+)","number":(\d+),"title":"([^"]*)","coverImage":(?:null|"[^"]*"),"isLocked":(true|false),"coinPrice":(\d+),"viewCount":(\d+),"likeCount":(\d+),"publishedAt":"([^"]+)"""",
		)
		val byId = LinkedHashMap<String, MangaChapter>()
		for (match in regex.findAll(text)) {
			val id = match.groupValues[1]
			val number = match.groupValues[2].toFloatOrNull() ?: continue
			val title = match.groupValues[3].nullIfEmpty()
			val isLocked = match.groupValues[4] == "true"
			val publishedAt = match.groupValues[8]
			// Skip locked/premium chapters — no readable pages without payment.
			if (isLocked) continue
			val chapterPath = "/series/$typePath/$slug/chapter/${number.toInt()}"
			byId[id] = MangaChapter(
				id = generateUid(chapterPath),
				title = title,
				number = number,
				volume = 0,
				url = chapterPath,
				scanlator = null,
				uploadDate = runCatching { dateFormat.parse(publishedAt)?.time ?: 0L }.getOrDefault(0L),
				branch = null,
				source = source,
			)
		}
		// API list order is often newest-first; normalize ascending for mapChapters(reversed).
		val sorted = byId.values.sortedBy { it.number }
		return sorted.mapChapters(reversed = true) { _, ch -> ch }
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val json = webClient.httpGet("https://$domain/api/genres").parseJson()
		val genres = json.optJSONArray("genres") ?: return emptySet()
		return buildSet {
			for (i in 0 until genres.length()) {
				val g = genres.optJSONObject(i) ?: continue
				val key = g.optString("slug").nullIfEmpty() ?: continue
				val name = g.optString("name").nullIfEmpty() ?: key
				add(
					MangaTag(
						key = key,
						title = name.toTitleCase(sourceLocale),
						source = source,
					),
				)
			}
		}
	}

	private fun mapStatus(status: String): MangaState? = when (status.uppercase(Locale.ROOT)) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED", "FINISHED" -> MangaState.FINISHED
		"DROPPED", "CANCELLED", "CANCELED" -> MangaState.ABANDONED
		"HIATUS", "ON_HIATUS" -> MangaState.PAUSED
		"COMING_SOON", "UPCOMING" -> MangaState.UPCOMING
		else -> null
	}

	private fun typeToPath(type: String): String = when (type.uppercase(Locale.ROOT)) {
		"NOVEL", "NOVELS" -> "novel"
		"MANHUA" -> "comic"
		"MANGA" -> "comic"
		else -> "comic" // MANHWA and default
	}

	private fun extractSlug(url: String): String {
		// /series/comic/some-slug or /series/comic/some-slug/chapter/4
		val parts = url.trim('/').split('/')
		val seriesIdx = parts.indexOf("series")
		return if (seriesIdx >= 0 && parts.size > seriesIdx + 2) {
			parts[seriesIdx + 2]
		} else {
			parts.last { it.isNotEmpty() && it != "chapter" && !it.all(Char::isDigit) }
		}
	}

	private fun extractTypePath(url: String): String {
		val parts = url.trim('/').split('/')
		val seriesIdx = parts.indexOf("series")
		return if (seriesIdx >= 0 && parts.size > seriesIdx + 1) {
			parts[seriesIdx + 1]
		} else {
			"comic"
		}
	}

	private fun absUrl(path: String): String {
		return if (path.startsWith("http://") || path.startsWith("https://")) {
			path
		} else {
			path.toAbsoluteUrl(domain)
		}
	}

	private fun extractDescription(html: String): String? {
		// Prefer schema.org Book description in page head / LD+JSON.
		val candidates = listOf(
			Regex(""""description"\s*:\s*"((?:[^"\\]|\\.){10,})""""),
			Regex("""\\"description\\":\\"((?:[^"\\]|\\.){10,})\\""""),
		)
		for (regex in candidates) {
			val raw = regex.find(html)?.groupValues?.get(1) ?: continue
			val cleaned = raw
				.replace("\\n", "\n")
				.replace("\\\"", "\"")
				.replace("\\u003c", "<")
				.replace("\\u003e", ">")
				.replace(Regex("<[^>]+>"), " ")
				.replace(Regex("\\s+"), " ")
				.trim()
			if (cleaned.isNotEmpty()) return cleaned
		}
		return null
	}

	private fun extractAuthor(html: String): String? {
		val m = Regex(
			""""author"\s*:\s*\{\s*"@type"\s*:\s*"Person"\s*,\s*"name"\s*:\s*"([^"]+)"""",
		).find(html)
		return m?.groupValues?.get(1)?.nullIfEmpty()
	}

	private companion object {
		val PAGE_URL_REGEX = Regex(
			"""https://media\.divascans\.org/series/[^"'\\\s>]+/p-[a-f0-9-]+\.webp""",
			RegexOption.IGNORE_CASE,
		)
	}
}
