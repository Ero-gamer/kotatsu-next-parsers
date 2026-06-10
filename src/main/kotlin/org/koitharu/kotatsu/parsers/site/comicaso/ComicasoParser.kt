package org.koitharu.kotatsu.parsers.site.comicaso

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

internal abstract class ComicasoParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override val sourceLocale: Locale = Locale("id")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	// Cache untuk index.json
	private var mangaListCache: JSONArray? = null
	private val mutex = Mutex()

	// Fetch dan cache index.json
	private suspend fun getMangaIndex(): JSONArray = mutex.withLock {
		mangaListCache ?: run {
			val url = "https://$domain/wp-content/static/manga/index.json"
			val arr = webClient.httpGet(url).parseJsonArray()
			mangaListCache = arr
			arr
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val index = getMangaIndex()
		val tagSet = LinkedHashSet<String>()
		for (i in 0 until index.length()) {
			val jo = index.getJSONObject(i)
			val genres = jo.optJSONArray("genres") ?: continue
			for (j in 0 until genres.length()) {
				tagSet.add(genres.getString(j))
			}
		}
		return tagSet.mapToSet { genre ->
			MangaTag(
				key = genre.lowercase(sourceLocale),
				title = genre.toTitleCase(sourceLocale),
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		var list = getMangaIndex().toMangaDtoList()

		// Filter by query
		if (!filter.query.isNullOrEmpty()) {
			val q = filter.query.lowercase(sourceLocale)
			list = list.filter { it.title.lowercase(sourceLocale).contains(q) }
		}

		// Filter by tag
		filter.tags.oneOrThrowIfMany()?.let { tag ->
			list = list.filter { dto ->
				dto.genres.any { it.lowercase(sourceLocale) == tag.key }
			}
		}

		// Filter by state
		filter.states.oneOrThrowIfMany()?.let { state ->
			val statusStr = when (state) {
				MangaState.ONGOING -> "on-going"
				MangaState.FINISHED -> "end"
				else -> null
			}
			if (statusStr != null) {
				list = list.filter { it.status == statusStr }
			}
		}

		// Filter by type
		filter.types.oneOrThrowIfMany()?.let { type ->
			val typeStr = when (type) {
				ContentType.MANGA -> "manga"
				ContentType.MANHWA -> "manhwa"
				ContentType.MANHUA -> "manhua"
				else -> null
			}
			if (typeStr != null) {
				list = list.filter { it.type?.lowercase(sourceLocale) == typeStr }
			}
		}

		// Sort
		list = when (order) {
			SortOrder.UPDATED -> list.sortedByDescending { it.updatedAt ?: it.mangaDate ?: 0L }
			SortOrder.ALPHABETICAL -> list.sortedBy { it.title.lowercase(sourceLocale) }
			else -> list // POPULARITY: urutan default dari index.json
		}

		// Pagination manual
		val start = (page - 1) * pageSize
		if (start >= list.size) return emptyList()
		val end = minOf(start + pageSize, list.size)

		return list.subList(start, end).map { it.toManga() }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/komik/").removeSuffix("/")
		val url = "https://$domain/wp-content/static/manga/$slug.json"
		val json = webClient.httpGet(url).parseJson()

		val title = json.getString("title")
		val synopsis = json.optString("synopsis").takeIf { it.isNotBlank() }
		val alternative = json.optString("alternative").takeIf { it.isNotBlank() }
		val thumbnail = json.optString("thumbnail").takeIf { it.isNotBlank() }
		val author = json.optString("author").takeIf { it.isNotBlank() }
		val artist = json.optString("artist").takeIf { it.isNotBlank() }

		val description = buildString {
			if (synopsis != null) append(synopsis)
			if (alternative != null) {
				if (isNotEmpty()) append("\n\n")
				append("Alternative: $alternative")
			}
		}.trim().takeIf { it.isNotEmpty() }

		val state = when (json.optString("status")) {
			"on-going" -> MangaState.ONGOING
			"end" -> MangaState.FINISHED
			else -> null
		}

		val genresArray = json.optJSONArray("genres")
		val tags = if (genresArray != null) {
			val result = LinkedHashSet<MangaTag>(genresArray.length())
			for (i in 0 until genresArray.length()) {
				val genre = genresArray.getString(i)
				result.add(
					MangaTag(
						key = genre.lowercase(sourceLocale),
						title = genre.toTitleCase(sourceLocale),
						source = source,
					),
				)
			}
			result as Set<MangaTag>
		} else {
			emptySet()
		}

		val authors = setOfNotNull(
			author,
			artist?.takeIf { it != author },
		)

		val chaptersArray = json.optJSONArray("chapters") ?: JSONArray()
		val chapters = ArrayList<MangaChapter>(chaptersArray.length())
		for (i in 0 until chaptersArray.length()) {
			val ch = chaptersArray.getJSONObject(i)
			val chSlug = ch.getString("slug")
			val chTitle = ch.getString("title")
			val chDate = ch.optLong("date").takeIf { it != 0L }?.times(1000L) ?: 0L
			chapters.add(
				MangaChapter(
					id = generateUid("/komik/$slug/$chSlug/"),
					title = chTitle,
					number = extractChapterNumber(chTitle),
					volume = 0,
					url = "/komik/$slug/$chSlug/",
					scanlator = null,
					uploadDate = chDate,
					branch = null,
					source = source,
				),
			)
		}
        
		chapters.sortBy { it.number }

		return manga.copy(
			title = title,
			description = description,
			coverUrl = thumbnail ?: manga.coverUrl,
			tags = tags,
			state = state,
			authors = authors,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("img.mjv2-page-image").map { img ->
			val url = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	// Helper: konversi JSONArray dari index.json ke list DTO internal
	private fun JSONArray.toMangaDtoList(): List<MangaIndexDto> {
		val result = ArrayList<MangaIndexDto>(length())
		for (i in 0 until length()) {
			val jo = getJSONObject(i)
			val genresArr = jo.optJSONArray("genres")
			val genres = if (genresArr != null) {
				val list = ArrayList<String>(genresArr.length())
				for (j in 0 until genresArr.length()) list.add(genresArr.getString(j))
				list as List<String>
			} else {
				emptyList()
			}
			result.add(
				MangaIndexDto(
					slug = jo.getString("slug"),
					title = jo.getString("title"),
					thumbnail = jo.optString("thumbnail").takeIf { it.isNotBlank() },
					status = jo.optString("status").takeIf { it.isNotBlank() },
					type = jo.optString("type").takeIf { it.isNotBlank() },
					genres = genres,
					mangaDate = jo.optLong("manga_date").takeIf { it != 0L },
					updatedAt = jo.optLong("updated_at").takeIf { it != 0L },
				),
			)
		}
		return result
	}

	private fun extractChapterNumber(title: String): Float {
		return Regex("""[\d]+(?:[.,]\d+)?""").find(title)
			?.value?.replace(',', '.')?.toFloatOrNull() ?: 0f
	}

	private data class MangaIndexDto(
		val slug: String,
		val title: String,
		val thumbnail: String?,
		val status: String?,
		val type: String?,
		val genres: List<String>,
		val mangaDate: Long?,
		val updatedAt: Long?,
	)

	private fun MangaIndexDto.toManga(): Manga = Manga(
		id = generateUid("/komik/$slug/"),
		url = "/komik/$slug/",
		title = title,
		altTitles = emptySet(),
		publicUrl = "https://$domain/komik/$slug/",
		rating = RATING_UNKNOWN,
		contentRating = if (isNsfwSource) ContentRating.ADULT else null,
		coverUrl = thumbnail ?: "",
		tags = emptySet(),
		state = when (status) {
			"on-going" -> MangaState.ONGOING
			"end" -> MangaState.FINISHED
			else -> null
		},
		authors = emptySet(),
		source = source,
	)
}
