package org.koitharu.kotatsu.parsers.site.id

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * The site used to be a MangaReader/Themesia install scraped as HTML — `/manga`
 * is a 404 now and none of those selectors survive. Everything comes from a
 * JSON API instead, which paginates by opaque cursor rather than page number.
 */
@MangaSourceParser("COSMIC_SCANS", "CosmicScans.id", "id")
internal class CosmicScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.COSMIC_SCANS, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("02.cosmicscans.to", "01.cosmicscans.to")

	private val apiUrl = "https://cdncid.csmcscns.id/v1/manga"

	override val sourceLocale: Locale = Locale.ENGLISH

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			// The search endpoint matches creator names as well as titles, so
			// tapping an author resolves through the very same query.
			isAuthorSearchSupported = true,
		)

	// The listing endpoint ignores every genre parameter spelling it was probed
	// with, so genres can only be matched against what each entry reports.
	// [getListPage] keeps pulling further api pages to make up for the ones
	// filtered away.
	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = GENRES.mapTo(LinkedHashSet(GENRES.size)) { title ->
			MangaTag(key = title.lowercase(Locale.ENGLISH), title = title, source = source)
		},
	)

	private val apiHeaders
		get() = getRequestHeaders().newBuilder()
			.set("Origin", "https://$domain")
			.set("Referer", "https://$domain/")
			.build()

	/**
	 * The listing endpoints page by passing back the `nextCursor` of the
	 * previous response, so the cursor for page N is only known once page N-1
	 * has been read. Kotatsu walks pages in order, so remembering each cursor
	 * as it appears is enough; an unknown cursor simply ends the list instead
	 * of silently repeating page 1.
	 */
	private val cursors = ConcurrentHashMap<String, String>()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val wantedTags = filter.tags.mapTo(HashSet(filter.tags.size)) { it.title.lowercase(Locale.ENGLISH) }
		// An author lookup goes through the same keyword search.
		val query = filter.query?.nullIfEmpty() ?: filter.author?.nullIfEmpty()

		if (query != null) {
			// The search endpoint answers with every match at once, uncursored.
			if (page > 1) {
				return emptyList()
			}
			val url = "$apiUrl/search".toHttpUrl().newBuilder()
				.addQueryParameter("limit", pageSize.toString())
				.addQueryParameter("q", query)
				.build()
			return webClient.httpGet(url, apiHeaders).parseJson()
				.optJSONArray("data")
				?.mapJSONNotNull { it.toManga() }
				.orEmpty()
				.filter { it.matches(wantedTags) }
		}

		val orderBy = when (order) {
			SortOrder.UPDATED -> "update"
			SortOrder.POPULARITY -> "popular"
			SortOrder.NEWEST -> "added"
			SortOrder.ALPHABETICAL -> "az"
			SortOrder.ALPHABETICAL_DESC -> "za"
			else -> "update"
		}
		// The cursor for a follow-up page is only known once the page before it
		// has been read; without it there is nothing sensible left to request.
		var cursor = if (page > 1) cursors["$orderBy:$page"] ?: return emptyList() else null

		val result = ArrayList<Manga>(pageSize)
		var requests = 0
		while (true) {
			val url = "$apiUrl/filter".toHttpUrl().newBuilder()
				.addQueryParameter("limit", pageSize.toString())
				.addQueryParameter("order_by", orderBy)
				.apply { cursor?.let { addQueryParameter("after", it) } }
				.build()
			val response = webClient.httpGet(url, apiHeaders).parseJson()
			response.optJSONArray("data")
				?.mapJSONNotNull { it.toManga() }
				?.filterTo(result) { it.matches(wantedTags) }
			cursor = response.optJSONObject("cursor")?.optString("nextCursor")?.nullIfEmpty()
			requests++

			// One api page per listing page unless genres thinned it out, in
			// which case read ahead a little rather than hand back a short or
			// empty page that would look like the end of the list.
			if (cursor == null || wantedTags.isEmpty() || result.size >= pageSize) break
			if (requests >= MAX_FILTERED_REQUESTS) break
		}
		cursor?.let { cursors["$orderBy:${page + 1}"] = it }
		return result
	}

	private fun Manga.matches(wantedTags: Set<String>): Boolean = wantedTags.isEmpty() ||
		wantedTags.all { wanted -> tags.any { it.title.lowercase(Locale.ENGLISH) == wanted } }

	private fun JSONObject.toManga(): Manga? {
		val slug = optString("slug").nullIfEmpty() ?: return null
		val title = optString("title").nullIfEmpty() ?: return null
		return Manga(
			id = generateUid(slug),
			url = "/series/$slug",
			publicUrl = "https://$domain/series/$slug",
			title = title,
			altTitles = emptySet(),
			coverUrl = optString("cover").nullIfEmpty(),
			largeCoverUrl = optString("big_cover").nullIfEmpty(),
			authors = setOfNotNull(optString("author").nullIfEmpty()),
			description = optString("sinopsis").nullIfEmpty(),
			tags = optJSONArray("genres").toTags() + optJSONArray("genre").toTags(),
			state = parseState(optString("status")),
			rating = optString("rating").toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
			contentRating = null,
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removeSuffix("/").substringAfterLast('/')
		val data = webClient.httpGet("$apiUrl/mangaDetail/$slug", apiHeaders).parseJson()
			.optJSONObject("data")
			?: return manga

		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		// Listed newest first; Kotatsu expects the opposite.
		val chapters = data.optJSONArray("chapters")?.mapJSONNotNull { item ->
			val chapterSlug = item.optString("slug").nullIfEmpty() ?: return@mapJSONNotNull null
			// Entries pointing somewhere else are not readable through the API.
			if (item.optString("redirect_link").nullIfEmpty() != null) {
				return@mapJSONNotNull null
			}
			// A number is often decorated ("576 FIX", "530.5 HBD", "515 V2"),
			// so take the leading value rather than parsing the whole string —
			// otherwise every decorated chapter lands on 0 and they collapse
			// into one another below.
			val numberText = item.optString("chapterNum")
			val number = CHAPTER_NUMBER_REGEX.find(numberText)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
			MangaChapter(
				id = generateUid(chapterSlug),
				title = numberText.nullIfEmpty()?.let { "Chapter $it" },
				number = number,
				volume = 0,
				url = "/chapter/$chapterSlug",
				scanlator = null,
				uploadDate = dateFormat.parseSafe(item.optString("time")),
				branch = null,
				source = source,
			)
		}
			// The site hosts some chapters twice under a zero-padded slug as
			// well ("chapter-3" and "chapter-03"), which would otherwise show up
			// as two entries with the same number. Only numbered chapters are
			// collapsed; anything unnumbered stays as its own entry rather than
			// being merged with every other unnumbered one.
			?.let { parsed ->
				val (numbered, unnumbered) = parsed.partition { it.number > 0f }
				numbered.groupBy { it.number }
					.map { (_, duplicates) -> duplicates.maxBy { it.uploadDate } }
					.plus(unnumbered.distinctBy { it.url })
					.sortedBy { it.number }
			}
			.orEmpty()

		return manga.copy(
			title = data.optString("title").nullIfEmpty() ?: manga.title,
			coverUrl = data.optString("cover").nullIfEmpty() ?: manga.coverUrl,
			largeCoverUrl = data.optString("big_cover").nullIfEmpty() ?: manga.largeCoverUrl,
			description = data.optString("sinopsis").nullIfEmpty() ?: manga.description,
			authors = setOfNotNull(
				data.optString("author").nullIfEmpty(),
				data.optString("artist").nullIfEmpty(),
			).ifEmpty { manga.authors },
			tags = (data.optJSONArray("genre").toTags() + data.optJSONArray("genres").toTags())
				.ifEmpty { manga.tags },
			state = parseState(data.optString("status")) ?: manga.state,
			rating = data.optString("rating").toFloatOrNull()?.div(10f) ?: manga.rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.removeSuffix("/").substringAfterLast('/')
		val data = webClient.httpGet("$apiUrl/readingPage/$slug", apiHeaders).parseJson()
			.optJSONObject("data")
			?: return emptyList()
		if (data.optString("redirect_link").nullIfEmpty() != null) {
			return emptyList()
		}
		// Each entry is a small HTML snippet wrapping a single <img>.
		val pages = data.optJSONArray("chapters") ?: return emptyList()
		return (0 until pages.length()).mapNotNull { i ->
			val html = pages.optString(i).nullIfEmpty() ?: return@mapNotNull null
			val url = Jsoup.parseBodyFragment(html).selectFirst("img")
				?.attr("src")
				?.trim()
				?.nullIfEmpty()
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun org.json.JSONArray?.toTags(): Set<MangaTag> {
		if (this == null) return emptySet()
		return (0 until length()).mapNotNullTo(LinkedHashSet()) { i ->
			val title = optString(i).trim().nullIfEmpty() ?: return@mapNotNullTo null
			MangaTag(key = title.lowercase(Locale.ENGLISH), title = title, source = source)
		}
	}

	private fun parseState(value: String?): MangaState? = when (value?.lowercase(Locale.ENGLISH)) {
		"ongoing" -> MangaState.ONGOING
		"completed", "complete" -> MangaState.FINISHED
		"hiatus", "on hiatus", "on-hold", "on hold" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}

	private companion object {
		private const val MAX_FILTERED_REQUESTS = 5

		/** Leading value of a chapter label such as "530.5 HBD". */
		private val CHAPTER_NUMBER_REGEX = Regex("""^\s*(\d+(?:\.\d+)?)""")

		// A curated subset: the api reports ~87 distinct genre strings, most of
		// them one-off typos, weekday names or content types rather than genres.
		private val GENRES = listOf(
			"Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Harem",
			"Historical", "Horror", "Isekai", "Josei", "Magic", "Martial Arts",
			"Mature", "Murim", "Mystery", "Psychological", "Regression",
			"Reincarnation", "Romance", "School Life", "Sci-fi", "Seinen",
			"Shoujo", "Shounen", "Slice of Life", "Sports", "Super Power",
			"Supernatural", "Survival", "System", "Thriller", "Tragedy",
		)
	}
}
