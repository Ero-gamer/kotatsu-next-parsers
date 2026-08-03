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
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
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
			cursor = response.optJSONObject("cursor")?.getStringOrNull("nextCursor")
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
		val slug = getStringOrNull("slug") ?: return null
		val title = getStringOrNull("title") ?: return null
		return Manga(
			id = generateUid(slug),
			url = "/series/$slug",
			publicUrl = "https://$domain/series/$slug",
			title = title,
			altTitles = emptySet(),
			coverUrl = getStringOrNull("cover"),
			// `big_cover` is null for every entry the api returns, so without a
			// fallback the details screen is handed nothing at all even when the
			// regular cover is perfectly good.
			largeCoverUrl = getStringOrNull("big_cover") ?: getStringOrNull("cover"),
			authors = setOfNotNull(getStringOrNull("author").realName()),
			description = getStringOrNull("sinopsis"),
			tags = optJSONArray("genres").toTags() + optJSONArray("genre").toTags(),
			state = parseState(getStringOrNull("status")),
			rating = getStringOrNull("rating")?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
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
		// The api repeats some rows verbatim — the same slug listed twice — so
		// only an identical slug is a real duplicate. Labels that merely look
		// alike are separate chapters with their own pages: "90" and
		// "90 s2 end" run 58 and 31 pages, and "9" and "09" were uploaded more
		// than a year apart. Collapsing those by number would delete content.
		val seenSlugs = HashSet<String>()
		// Listed newest first; Kotatsu expects the opposite.
		val chapters = data.optJSONArray("chapters")?.mapJSONNotNull { item ->
			val chapterSlug = item.getStringOrNull("slug") ?: return@mapJSONNotNull null
			// Entries pointing somewhere else are not readable through the API.
			if (item.getStringOrNull("redirect_link") != null) {
				return@mapJSONNotNull null
			}
			// A number is often decorated ("576 FIX", "530.5 HBD", "515 V2"),
			// so take the leading value rather than parsing the whole string —
			// otherwise every decorated chapter lands on 0 and they collapse
			// into one another below.
			val numberText = item.getStringOrNull("chapterNum").orEmpty()
			val number = CHAPTER_NUMBER_REGEX.find(numberText)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
			if (!seenSlugs.add(chapterSlug)) {
				return@mapJSONNotNull null
			}
			MangaChapter(
				id = generateUid(chapterSlug),
				title = numberText.nullIfEmpty()?.let { "Chapter $it" },
				number = number,
				volume = 0,
				url = "/chapter/$chapterSlug",
				scanlator = null,
				uploadDate = dateFormat.parseSafe(item.getStringOrNull("time")),
				branch = null,
				source = source,
			)
		}
			?.sortedBy { it.number }
			.orEmpty()

		return manga.copy(
			title = data.getStringOrNull("title") ?: manga.title,
			coverUrl = data.getStringOrNull("cover") ?: manga.coverUrl,
			largeCoverUrl = data.getStringOrNull("big_cover")
				?: data.getStringOrNull("cover")
				?: manga.largeCoverUrl
				?: manga.coverUrl,
			description = data.getStringOrNull("sinopsis") ?: manga.description,
			authors = setOfNotNull(
				data.getStringOrNull("author").realName(),
				data.getStringOrNull("artist").realName(),
			).ifEmpty { manga.authors },
			tags = (data.optJSONArray("genre").toTags() + data.optJSONArray("genres").toTags())
				.ifEmpty { manga.tags },
			state = parseState(data.getStringOrNull("status")) ?: manga.state,
			rating = data.getStringOrNull("rating")?.toFloatOrNull()?.div(10f) ?: manga.rating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.removeSuffix("/").substringAfterLast('/')
		val data = webClient.httpGet("$apiUrl/readingPage/$slug", apiHeaders).parseJson()
			.optJSONObject("data")
			?: return emptyList()
		if (data.getStringOrNull("redirect_link") != null) {
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

	/** Creator fields carry placeholders like "-" when nobody is credited. */
	private fun String?.realName(): String? = this?.trim()
		?.takeIf { it.isNotEmpty() && it.lowercase(Locale.ENGLISH) !in CREATOR_PLACEHOLDERS }

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


		private val CREATOR_PLACEHOLDERS = setOf("-", "--", "?", "n/a", "na", "unknown", "null")

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
