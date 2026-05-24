package org.koitharu.kotatsu.parsers.site.ru

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
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
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale

@MangaSourceParser("TOMILOLIB", "TomiloLib", "ru")
internal class TomiloLib(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.TOMILOLIB, pageSize = PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain("tomilo-lib.ru")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val defaultSortOrder: SortOrder = SortOrder.POPULARITY_WEEK

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isYearSupported = true,
		)

	private val filterOptions = suspendLazy(initializer = ::fetchFilterOptions)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Accept", "application/json")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val builder = request.newBuilder()
			.header("Referer", "https://$domain/")
		if (request.url.isImageUrl()) {
			builder.header("Accept", IMAGE_ACCEPT)
		}
		val updatedRequest = builder.build()
		val response = chain.proceed(updatedRequest)
		val fallbackUrl = updatedRequest.url.toFallbackChapterImageUrl()
			?: updatedRequest.url.toFallbackCoverImageUrl(response.code)
		if (response.isSuccessful || fallbackUrl == null) {
			return response
		}
		response.close()
		return chain.proceed(
			updatedRequest.newBuilder()
				.url(fallbackUrl)
				.build(),
		).let { fallbackResponse ->
			if (fallbackResponse.isSuccessful) {
				return fallbackResponse
			}
			val coverJpgFallbackUrl = fallbackResponse.request.url.toFallbackCoverImageUrl(fallbackResponse.code)
			if (coverJpgFallbackUrl != null && coverJpgFallbackUrl != fallbackResponse.request.url) {
				fallbackResponse.close()
				return chain.proceed(
					updatedRequest.newBuilder()
						.url(coverJpgFallbackUrl)
						.build(),
				)
			}
			val s3PageFallbackUrl = updatedRequest.url.toFallbackS3ChapterPageUrl(fallbackResponse.code)
			if (s3PageFallbackUrl == null) {
				fallbackResponse
			} else {
				fallbackResponse.close()
				chain.proceed(
					updatedRequest.newBuilder()
						.url(s3PageFallbackUrl)
						.build(),
				)
			}
		}
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptions.get()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (filter.contentRating.isNotEmpty() && filter.contentRating.all { it == ContentRating.ADULT }) {
			return emptyList()
		}
		val url = apiUrl("titles").newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", PAGE_SIZE.toString())
			.addQueryParameter("includeAdult", "false")
			.addQueryParameter("sortBy", order.toApiSortBy())
			.addQueryParameter("sortOrder", order.toApiSortOrder())
			.apply {
				filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("search", it) }
				filter.states.mapNotNullTo(LinkedHashSet()) { it.toApiStatus() }.forEach {
					addQueryParameter("status", it)
				}
				filter.types.mapNotNullTo(LinkedHashSet()) { it.toApiType() }.forEach {
					addQueryParameter("type", it)
				}
				if (filter.year != YEAR_UNKNOWN) {
					addQueryParameter("releaseYear", filter.year.toString())
				}
			}
			.build()

		val titles = webClient.httpGet(url, getRequestHeaders())
			.parseJson()
			.optJSONObject("data")
			?.optJSONArray("titles")
			?: JSONArray()

		val allowedRatings = filter.contentRating.takeIf { it.isNotEmpty() }
		return (0 until titles.length()).mapNotNull { i ->
			parseManga(titles.optJSONObject(i), withDetails = false)
		}.filter { manga ->
			allowedRatings == null || manga.contentRating in allowedRatings
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.toAbsoluteUrl(domain).toHttpUrl().pathSegments.lastOrNull()
			?: throw ParseException("Cannot parse title slug", manga.url)
		val url = apiUrl("titles/slug/$slug").newBuilder()
			.addQueryParameter("populateChapters", "true")
			.build()
		val info = webClient.httpGet(url, getRequestHeaders())
			.parseJson()
			.optJSONObject("data")
			?: throw ParseException("Cannot parse title details", manga.url)
		if (info.isAdult()) {
			throw ParseException("Adult titles are not supported", manga.publicUrl)
		}

		val parsed = parseManga(info, withDetails = true) ?: manga
		val chapters = parseChapters(info, slug).takeIf { it.isNotEmpty() }
			?: fetchChapters(info.getStringOrNull("_id") ?: return parsed, slug)
		return parsed.copy(
			id = manga.id,
			url = manga.url,
			publicUrl = manga.publicUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain).toHttpUrl()
		val chapterId = url.pathSegments.lastOrNull()?.takeIf { it.isNotEmpty() }
			?: throw ParseException("Cannot parse chapter id", chapter.url)
		val titleId = url.queryParameter("titleId")?.takeIf { it.isNotEmpty() }
			?: throw ParseException("Cannot parse title id", chapter.url)
		return fetchS3CoverPages(titleId, chapterId, chapter.url)
			.ifEmpty { fetchS3ChapterPages(chapterId, chapter.url) }
	}

	private suspend fun fetchFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentRating = EnumSet.of(
			ContentRating.SAFE,
			ContentRating.SUGGESTIVE,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
	)

	private suspend fun fetchChapters(titleId: String, slug: String): List<MangaChapter> {
		return parseChapters(fetchChapterJson(titleId), titleId, slug)
	}

	private suspend fun fetchChapterJson(titleId: String): JSONArray {
		return webClient.httpGet(
			apiUrl("chapters/title/$titleId").newBuilder()
				.addQueryParameter("page", "1")
				.addQueryParameter("limit", "10000")
				.addQueryParameter("sortOrder", "asc")
				.build(),
			getRequestHeaders(),
		).parseJson()
			.optJSONObject("data")
			?.optJSONArray("chapters")
			?: JSONArray()
	}

	private fun parseManga(info: JSONObject?, withDetails: Boolean): Manga? {
		if (info == null || info.isAdult()) {
			return null
		}
		val id = info.getStringOrNull("_id") ?: return null
		val slug = info.getStringOrNull("slug") ?: return null
		val title = info.getStringOrNull("name") ?: slug
		val url = "/titles/$slug"
		val cover = resolveS3CoverUrl(id)
		val tags = LinkedHashSet<MangaTag>()
		info.optJSONArray("genres")?.addStringsTo(tags)
		info.optJSONArray("tags")?.addStringsTo(tags)
		val authors = LinkedHashSet<String>()
		info.getStringOrNull("author")?.splitTo(authors)
		info.getStringOrNull("artist")?.splitTo(authors)

		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = parseStringSet(info.optJSONArray("altNames")),
			url = url,
			publicUrl = "https://$domain$url",
			rating = info.optDouble("averageRating", 0.0).takeIf { it > 0.0 }?.div(10.0)?.toFloat()
				?: RATING_UNKNOWN,
			contentRating = parseContentRating(info),
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = tags,
			state = parseState(info.getStringOrNull("status")),
			authors = authors,
			description = if (withDetails) info.getStringOrNull("description") else null,
			source = source,
		)
	}

	private fun parseChapters(info: JSONObject, slug: String): List<MangaChapter> {
		val titleId = info.getStringOrNull("_id") ?: return emptyList()
		val chapters = info.optJSONArray("chapters") ?: return emptyList()
		if (chapters.length() == 0 || chapters.opt(0) !is JSONObject) {
			return emptyList()
		}
		return parseChapters(chapters, titleId, slug)
	}

	private fun parseChapters(chapters: JSONArray, titleId: String, slug: String): List<MangaChapter> {
		return (0 until chapters.length()).mapNotNull { i ->
			val ch = chapters.optJSONObject(i) ?: return@mapNotNull null
			if (!ch.optBoolean("isPublished", true)) {
				return@mapNotNull null
			}
			val chapterId = ch.getStringOrNull("_id") ?: return@mapNotNull null
			val number = ch.optDouble("chapterNumber", 0.0).toFloat()
			val url = "/titles/$slug/chapter/$chapterId?titleId=$titleId"
			MangaChapter(
				id = generateUid(url),
				title = buildChapterTitle(number, ch.getStringOrNull("name")),
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = parseDate(ch.getStringOrNull("releaseDate") ?: ch.getStringOrNull("createdAt")),
				branch = null,
				source = source,
			)
		}
	}

	private fun parsePages(pages: JSONArray?, chapterUrl: String, titleId: String): List<MangaPage> {
		if (pages == null || pages.length() == 0) {
			return emptyList()
		}
		return (0 until pages.length()).mapNotNull { i ->
			val path = pages.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val imageUrl = resolvePageUrl(path, titleId)
			MangaPage(
				id = generateUid("$chapterUrl#$i"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun JSONArray.addStringsTo(destination: MutableSet<MangaTag>) {
		for (i in 0 until length()) {
			val value = optString(i).trim()
			if (value.isNotEmpty() && !value.isAdultGenre()) {
				destination += MangaTag(
					key = value,
					title = value,
					source = source,
				)
			}
		}
	}

	private fun parseStringSet(items: JSONArray?): Set<String> {
		if (items == null) {
			return emptySet()
		}
		val result = LinkedHashSet<String>(items.length())
		for (i in 0 until items.length()) {
			val value = items.optString(i).trim()
			if (value.isNotEmpty()) {
				result += value
			}
		}
		return result
	}

	private fun JSONObject.isAdult(): Boolean {
		return optBoolean("isAdult", false) || optInt("ageLimit", 0) >= 18
	}

	private fun parseContentRating(info: JSONObject): ContentRating {
		val ageLimit = info.optInt("ageLimit", 0)
		return when {
			info.optBoolean("isAdult", false) || ageLimit >= 18 -> ContentRating.ADULT
			ageLimit >= 16 -> ContentRating.SUGGESTIVE
			else -> ContentRating.SAFE
		}
	}

	private fun parseState(raw: String?): MangaState? = when (raw?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"pause" -> MangaState.PAUSED
		"cancelled" -> MangaState.ABANDONED
		else -> null
	}

	private fun buildChapterTitle(number: Float, name: String?): String? {
		val title = name?.trim().orEmpty()
		if (title.isEmpty()) {
			return if (number > 0f) "Глава ${number.toCleanString()}" else null
		}
		return title
	}

	private fun parseDate(raw: String?): Long {
		val value = raw?.trim().orEmpty()
		if (value.isEmpty()) {
			return 0L
		}
		for (pattern in DATE_PATTERNS) {
			val parsed = SimpleDateFormat(pattern, Locale.ROOT).parseSafe(value)
			if (parsed > 0L) {
				return parsed
			}
		}
		return 0L
	}

	private fun String.splitTo(destination: MutableSet<String>) {
		split(',', ';', '/')
			.map { it.trim() }
			.filterTo(destination) { it.isNotEmpty() }
	}

	private fun String.isAdultGenre(): Boolean {
		return lowercase(Locale.ROOT) in ADULT_GENRES
	}

	private fun resolveCoverUrl(path: String): String {
		return when {
			path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true) -> path
			path.startsWith("/uploads/titles/") -> path.toAbsoluteUrl(domain)
			path.startsWith("/titles/") -> "/uploads$path".toAbsoluteUrl(domain)
			else -> path.toAbsoluteUrl(domain)
		}
	}

	private suspend fun fetchS3CoverPages(titleId: String, chapterId: String, chapterUrl: String): List<MangaPage> {
		val result = ArrayList<MangaPage>()
		for (page in 1..MAX_S3_COVER_PAGES) {
			val imageUrl = "$CDN_URL/titles/$titleId/chapters/$chapterId/cover_$page.jpg"
			val exists = runCatching {
				webClient.httpHead(imageUrl).close()
			}.isSuccess
			if (!exists) {
				break
			}
			result += MangaPage(
				id = generateUid("$chapterUrl#s3-cover-$page"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
		return result
	}

	private suspend fun fetchS3ChapterPages(chapterId: String, chapterUrl: String): List<MangaPage> {
		val result = ArrayList<MangaPage>()
		for (page in 1..MAX_S3_COVER_PAGES) {
			val imageUrl = "$CDN_URL/chapters/$chapterId/${page.toString().padStart(3, '0')}.jpeg"
			val exists = runCatching {
				webClient.httpHead(imageUrl).close()
			}.isSuccess
			if (!exists) {
				break
			}
			result += MangaPage(
				id = generateUid("$chapterUrl#s3-chapter-$page"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
		return result
	}

	private fun resolveNextImageCoverUrl(path: String): String? {
		val cdnPath = when {
			path.startsWith("/uploads/titles/") -> path.removePrefix("/uploads")
			path.startsWith("/titles/") -> path
			else -> return null
		}
		return "https://$domain/_next/image".toHttpUrl().newBuilder()
			.addQueryParameter("url", "$CDN_URL$cdnPath")
			.addQueryParameter("w", "640")
			.addQueryParameter("q", "85")
			.build()
			.toString()
	}

	private fun resolveS3CoverUrl(titleId: String): String {
		return "$CDN_URL/titles/$titleId/cover.jpeg"
	}

	private fun resolvePageUrl(path: String, titleId: String): String {
		return (path.toUploadsChapterPath(titleId)?.toAbsoluteUrl(domain)
			?: path.toAbsoluteUrl(domain)).toRelativeUrl(domain)
	}

	private fun String.toUploadsChapterPath(titleId: String): String? {
		val pagePath = if (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)) {
			toHttpUrl().encodedPath
		} else {
			substringBefore('?')
		}
		val segments = pagePath.trim('/').split('/')
		val chaptersIndex = segments.indexOf("chapters")
		if (chaptersIndex < 0 || chaptersIndex + 2 >= segments.size) {
			return null
		}
		val chapterId = segments[chaptersIndex + 1]
		val pageName = segments.last()
		return "/uploads/titles/$titleId/chapters/$chapterId/$pageName"
	}

	private fun HttpUrl.isImageUrl(): Boolean {
		return when {
			host == domain && encodedPath == "/_next/image" -> true
			host == domain && encodedPath.startsWith("/uploads/titles/") -> true
			host == domain && encodedPath.startsWith("/uploads/chapters/") -> true
			host == "s3.regru.cloud" && encodedPath.startsWith("/tomilolib/titles/") -> true
			else -> false
		}
	}

	private fun HttpUrl.toFallbackChapterImageUrl(): HttpUrl? {
		if (host != domain || !encodedPath.startsWith("/uploads/titles/")) {
			return null
		}
		val segments = encodedPath.trim('/').split('/')
		val chaptersIndex = segments.indexOf("chapters")
		if (chaptersIndex < 0 || chaptersIndex + 2 >= segments.size) {
			return null
		}
		val chapterId = segments[chaptersIndex + 1]
		val pageName = segments.last()
		return newBuilder()
			.encodedPath("/uploads/chapters/$chapterId/$pageName")
			.query(null)
			.build()
	}

	private fun HttpUrl.toFallbackS3ChapterPageUrl(responseCode: Int): HttpUrl? {
		if (responseCode != 404) {
			return null
		}
		val segments = encodedPath.trim('/').split('/')
		val titleId: String
		val chapterId: String
		val pageName: String
		when {
			host == domain && encodedPath.startsWith("/uploads/titles/") -> {
				val chaptersIndex = segments.indexOf("chapters")
				if (chaptersIndex < 0 || chaptersIndex + 2 >= segments.size) return null
				titleId = segments.getOrNull(2) ?: return null
				chapterId = segments[chaptersIndex + 1]
				pageName = segments.last()
			}
			host == domain && encodedPath.startsWith("/uploads/chapters/") -> {
				if (segments.size < 4) return null
				chapterId = segments.getOrNull(2) ?: return null
				pageName = segments.last()
				titleId = requestPathTitleId() ?: return null
			}
			host == "s3.regru.cloud" && encodedPath.startsWith("/tomilolib/titles/") -> {
				val chaptersIndex = segments.indexOf("chapters")
				if (chaptersIndex < 0 || chaptersIndex + 2 >= segments.size) return null
				titleId = segments.getOrNull(2) ?: return null
				chapterId = segments[chaptersIndex + 1]
				pageName = segments.last()
			}
			else -> return null
		}
		val pageNumber = pageName.substringBeforeLast('.')
			.substringAfterLast('_')
			.toIntOrNull()
			?: return null
		return "$CDN_URL/titles/$titleId/chapters/$chapterId/cover_$pageNumber.jpg".toHttpUrl()
	}

	private fun HttpUrl.requestPathTitleId(): String? {
		return queryParameter("titleId")?.takeIf { it.isNotEmpty() }
	}

	private fun HttpUrl.toFallbackCoverImageUrl(responseCode: Int): HttpUrl? {
		if (responseCode != 403 && responseCode != 404) {
			return null
		}
		val titleId = when {
			host == domain && encodedPath.startsWith("/uploads/titles/") -> {
				encodedPath.trim('/').split('/').getOrNull(2)
			}
			host == domain && encodedPath == "/_next/image" -> {
				queryParameter("url")?.toHttpUrlOrNull()?.takeIf {
					it.host == "s3.regru.cloud" && it.encodedPath.startsWith("/tomilolib/titles/")
				}?.encodedPath?.trim('/')?.split('/')?.getOrNull(2)
			}
			host == "s3.regru.cloud" && encodedPath.startsWith("/tomilolib/titles/") -> {
				encodedPath.trim('/').split('/').getOrNull(2)
			}
			else -> null
		}?.takeIf { it.isNotEmpty() } ?: return null
		val extension = if (host == "s3.regru.cloud" && encodedPath.endsWith("/cover.jpeg")) {
			"jpg"
		} else {
			"jpeg"
		}
		return "$CDN_URL/titles/$titleId/cover.$extension".toHttpUrl()
	}

	private fun apiUrl(path: String) = "https://$domain/api/$path".toHttpUrl()

	private fun SortOrder.toApiSortBy(): String = when (this) {
		SortOrder.POPULARITY_TODAY -> "dayViews"
		SortOrder.POPULARITY_MONTH -> "monthViews"
		SortOrder.POPULARITY, SortOrder.POPULARITY_WEEK -> "weekViews"
		SortOrder.UPDATED -> "updatedAt"
		SortOrder.NEWEST -> "createdAt"
		SortOrder.RATING -> "averageRating"
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "name"
		else -> "weekViews"
	}

	private fun SortOrder.toApiSortOrder(): String = when (this) {
		SortOrder.ALPHABETICAL -> "asc"
		else -> "desc"
	}

	private fun MangaState.toApiStatus(): String? = when (this) {
		MangaState.ONGOING -> "ongoing"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "pause"
		MangaState.ABANDONED -> "cancelled"
		else -> null
	}

	private fun ContentType.toApiType(): String? = when (this) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		ContentType.COMICS -> "comic"
		else -> null
	}

	private fun Float.toCleanString(): String {
		return if (this % 1f == 0f) {
			toInt().toString()
		} else {
			toString()
		}
	}

	private companion object {
		private const val PAGE_SIZE = 24
		private const val MAX_S3_COVER_PAGES = 300
		private const val CDN_URL = "https://s3.regru.cloud/tomilolib"
		private const val IMAGE_ACCEPT = "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5"
		private val DATE_PATTERNS = listOf(
			"yyyy-MM-dd'T'HH:mm:ss.SSSX",
			"yyyy-MM-dd'T'HH:mm:ssX",
		)
		private val ADULT_GENRES = setOf(
			"изнасилование",
			"секс",
			"хентай",
			"эротика",
			"этти",
		)
	}
}
