package org.koitharu.kotatsu.parsers.site.vi

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.ContentRating
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
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("CUUTRUYEN", "Cứu Truyện", "vi")
internal class CuuTruyenParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CUUTRUYEN, PAGE_SIZE) {

	override val configKeyDomain = ConfigKey.Domain(DOMAIN)

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = availableTags.get().values.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = baseUrl("tim-kiem").toHttpUrl().newBuilder()
			.addQueryParameter("sort", order.toWebsiteSort())
			.addQueryParameter("filter[status]", filter.states.toWebsiteStatus())
			.apply {
				if (filter.tags.isNotEmpty()) {
					addQueryParameter("filter[accept_genres]", filter.tags.joinToString(",") { it.key })
				}
				filter.query?.trim()?.takeIf(String::isNotEmpty)?.let {
					addQueryParameter("keyword", it)
				}
				addQueryParameter("page", page.toString())
			}
			.build()

		return webClient.httpGet(url).parseHtml()
			.select("div.manga-vertical")
			.mapNotNull(::parseMangaCard)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val actualPath = resolveLegacyPath(manga)
		val document = webClient.httpGet(actualPath.toAbsoluteUrl(domain)).parseHtml()
		val details = parseMangaDetails(
			document = document,
			manga = manga.copy(
				url = actualPath,
				publicUrl = actualPath.toAbsoluteUrl(domain),
			),
		)

		// Manga.url and Manga.id are stable fields. Keep the legacy values when migrating an old API entry.
		return if (actualPath == manga.url) {
			details
		} else {
			details.copy(id = manga.id, url = manga.url)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val document = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val imageUrls = document.select("div.text-center > img.max-w-full")
			.mapNotNull { it.extractImageUrl() }
		if (imageUrls.isEmpty()) {
			throw ParseException("Could not find image data", chapter.url.toAbsoluteUrl(domain))
		}
		return imageUrls.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> {
		val actualPath = resolveLegacyPath(seed)
		val document = webClient.httpGet(actualPath.toAbsoluteUrl(domain)).parseHtml()
		val relatedSection = document.select("h5")
			.firstOrNull { it.text().trim() == "Có thể bạn thích" }
			?.parent()
			?: return emptyList()

		return relatedSection.select("div.flex.gap-2.w-full")
			.mapNotNull(::parseRelatedCard)
			.distinctBy { it.url }
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		if (link.host != domain || link.pathSegments.firstOrNull() != "truyen") return null
		val slug = link.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
		val path = "/truyen/$slug"
		return getDetails(mangaSeed(path, slug))
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request().newBuilder()
			.header("User-Agent", config[userAgentKey])
			.build()
		val response = chain.proceed(request)
		if (request.url.host != domain) return response

		val html = response.peekBody(Long.MAX_VALUE).string()
		if (!html.isPasswordGate()) return response

		response.close()
		submitPassword(chain, html)
		val retry = chain.proceed(request)
		if (retry.peekBody(Long.MAX_VALUE).string().isPasswordGate()) {
			retry.close()
			throw IOException("CuuTruyen website password was rejected")
		}
		return retry
	}

	private val availableTags = suspendLazy(soft = true) {
		val result = LinkedHashMap<String, MangaTag>()
		val document = webClient.httpGet(baseUrl("tim-kiem")).parseHtml()
		for (element in document.select("label")) {
			val id = GENRE_ID_REGEX.matchEntire(element.attr("@click"))
				?.groupValues
				?.getOrNull(1)
				?: continue
			val name = element.text().trim().takeIf(String::isNotEmpty) ?: continue
			result.putIfAbsent(
				id,
				MangaTag(
					key = id,
					title = name.toTitleCase(sourceLocale),
					source = source,
				),
			)
		}
		result
	}

	private fun parseMangaCard(element: Element): Manga? {
		val link = element.selectFirst("div.p-2 a") ?: return null
		val path = link.attrAsRelativeUrl("href").takeIf(String::isNotBlank) ?: return null
		val title = link.text().trim().takeIf(String::isNotEmpty) ?: return null
		return mangaSeed(path, title).copy(
			coverUrl = element.selectFirst("div.cover")?.extractBackgroundImage(),
		)
	}

	private fun parseRelatedCard(element: Element): Manga? {
		val link = element.selectFirst("a[href*=/truyen/]") ?: return null
		val path = link.attrAsRelativeUrl("href").takeIf(String::isNotBlank) ?: return null
		val title = link.text().trim().takeIf(String::isNotEmpty) ?: return null
		return mangaSeed(path, title).copy(
			coverUrl = element.selectFirst("div.cover-sm")?.extractBackgroundImage(),
		)
	}

	private fun parseMangaDetails(document: Document, manga: Manga): Manga {
		val titleElement = document.selectFirst("span.grow.text-lg")
			?: throw ParseException("Cannot find manga title", manga.publicUrl)
		val detailsSection = titleElement.parent()?.parent() ?: document
		val title = titleElement.text().trim()
		val genres = detailsSection.select("a[href*=/the-loai/]").mapTo(LinkedHashSet()) { element ->
			MangaTag(
				key = element.attr("href").substringAfterLast('/').substringBefore('?'),
				title = element.text().trim().toTitleCase(sourceLocale),
				source = source,
			)
		}
		val alternative = detailsSection.select("span")
			.firstOrNull { it.text().trim().startsWith("Tên khác:") }
			?.nextElementSibling()
			?.text()
			?.trim()
			?.nullIfEmpty()
		val statusText = detailsSection.selectFirst(
			"a[href*='filter[status]'] span, a[href*='filter%5Bstatus%5D'] span",
		)?.text().orEmpty()
		val state = when {
			statusText.contains("Đã hoàn thành", ignoreCase = true) -> MangaState.FINISHED
			statusText.contains("Đang tiến hành", ignoreCase = true) -> MangaState.ONGOING
			else -> null
		}
		val author = detailsSection.selectFirst("a[href*=/tac-gia/]")?.text()?.trim()?.nullIfEmpty()
		val scanlator = detailsSection.selectFirst("a[href*=/nhom-dich/]")?.text()?.trim()?.nullIfEmpty()
		val description = detailsSection.selectFirst("div.mg-plot")
			?.select("p")
			?.map { it.text().trim() }
			?.filter { it.isNotEmpty() && it != "Tóm tắt" }
			?.distinct()
			?.joinToString("\n")
			?.nullIfEmpty()
		val chapters = parseChapterList(document, scanlator)
		val adultTags = setOf("nsfw", "tinh-duc", "smut", "18-plus")

		return manga.copy(
			title = title,
			altTitles = alternative
				?.lineSequence()
				?.map(String::trim)
				?.filter { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
				?.toCollection(LinkedHashSet())
				.orEmpty(),
			publicUrl = manga.url.toAbsoluteUrl(domain),
			coverUrl = detailsSection.selectFirst("div.cover-frame div.cover, div.cover-frame")
				?.extractBackgroundImage()
				?: manga.coverUrl,
			tags = genres,
			state = state,
			authors = setOfNotNull(author),
			contentRating = if (genres.any { it.key in adultTags }) ContentRating.ADULT else null,
			description = description,
			chapters = chapters,
		)
	}

	private fun parseChapterList(document: Document, scanlator: String?): List<MangaChapter> =
		document.select("ul.overflow-y-auto a[href*=/truyen/]")
			.mapNotNull { chapterElement ->
				val chapterTitle = chapterElement.selectFirst(
					"div.grow span.text-ellipsis, div.grow span.truncate",
				)?.text()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
				val path = chapterElement.attrAsRelativeUrl("href").takeIf(String::isNotBlank)
					?: return@mapNotNull null
				MangaChapter(
					id = generateUid(path),
					title = chapterTitle,
					number = extractChapterNumber(chapterTitle),
					volume = 0,
					url = path,
					scanlator = scanlator,
					uploadDate = parseChapterDate(
						chapterElement.selectFirst("span.timeago[datetime]")?.attr("datetime"),
					),
					branch = null,
					source = source,
				)
			}
			.distinctBy { it.url }
			.asReversed()

	private suspend fun resolveLegacyPath(manga: Manga): String {
		if (!manga.url.startsWith(LEGACY_MANGA_PREFIX)) return manga.url
		val id = manga.url.substringAfterLast('/').toLongOrNull()
			?: throw ParseException("Invalid legacy CuuTruyen URL", manga.url)
		val legacyTitle = runCatching {
			webClient.httpGet("https://$LEGACY_DOMAIN$LEGACY_MANGA_PREFIX$id")
				.parseJson()
				.getJSONObject("data")
				.getString("name")
		}.getOrNull() ?: manga.title
		val searchUrl = baseUrl("tim-kiem").toHttpUrl().newBuilder()
			.addQueryParameter("keyword", legacyTitle)
			.addQueryParameter("sort", "-updated_at")
			.addQueryParameter("filter[status]", "2,1")
			.addQueryParameter("page", "1")
			.build()
		val matches = webClient.httpGet(searchUrl).parseHtml()
			.select("div.manga-vertical")
			.mapNotNull(::parseMangaCard)
		val match = matches.firstOrNull { it.title.trim().equals(legacyTitle.trim(), ignoreCase = true) }
			?: matches.firstOrNull()
		return match?.url
			?: throw ParseException("Cannot map legacy CuuTruyen manga to the new website", searchUrl.toString())
	}

	private fun submitPassword(chain: Interceptor.Chain, html: String) {
		val wireData = WIRE_INITIAL_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
			?.let { Parser.unescapeEntities(it, true) }
			?: throw IOException("Gate: wire:initial-data not found")
		val csrfToken = LIVEWIRE_TOKEN_REGEX.find(html)?.groupValues?.getOrNull(1)
			?: throw IOException("Gate: CSRF token not found")
		val fingerprint = wireData.extractJsonObject("fingerprint")
		val serverMemo = wireData.extractJsonObject("serverMemo")
		val payload = "{\"fingerprint\":" + fingerprint +
			",\"serverMemo\":" + serverMemo +
			",\"updates\":[{\"type\":\"syncInput\",\"payload\":{\"id\":\"s1\",\"name\":\"password\",\"value\":\"" +
			WEBSITE_PASSWORD +
			"\"}},{\"type\":\"callMethod\",\"payload\":{\"id\":\"c1\",\"method\":\"submit\",\"params\":[]}}]}"
		val headers = Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.add("Content-Type", JSON_MEDIA_TYPE.toString())
			.add("X-CSRF-TOKEN", csrfToken)
			.add("X-Livewire", "true")
			.add("Accept", "text/html, application/xhtml+xml")
			.add("Referer", baseUrl())
			.build()
		val request = Request.Builder()
			.url(baseUrl("livewire/message/enter-secret"))
			.headers(headers)
			.post(payload.toRequestBody(JSON_MEDIA_TYPE))
			.build()
		chain.proceed(request).use { response ->
			if (!response.isSuccessful) {
				val message = response.peekBody(512).string().replace(Regex("""\s+"""), " ").trim()
				throw IOException("Gate password request failed with HTTP " + response.code + ": " + message)
			}
		}
	}

	private fun String.extractJsonObject(property: String): String {
		val key = "\"" + property + "\""
		val keyIndex = indexOf(key)
		if (keyIndex == -1) throw IOException("Gate: " + property + " not found")
		val colonIndex = indexOf(':', keyIndex + key.length)
		if (colonIndex == -1) throw IOException("Gate: malformed " + property)
		var objectStart = colonIndex + 1
		while (objectStart < length && this[objectStart].isWhitespace()) objectStart++
		if (getOrNull(objectStart) != '{') throw IOException("Gate: malformed " + property)

		var depth = 0
		var inString = false
		var escaped = false
		for (index in objectStart until length) {
			val char = this[index]
			if (inString) {
				when {
					escaped -> escaped = false
					char == '\\' -> escaped = true
					char == '"' -> inString = false
				}
				continue
			}
			when (char) {
				'"' -> inString = true
				'{' -> depth++
				'}' -> {
					depth--
					if (depth == 0) return substring(objectStart, index + 1)
				}
			}
		}
		throw IOException("Gate: unterminated " + property)
	}

	private fun String.isPasswordGate(): Boolean =
		contains("wire:initial-data") && contains("enter-secret")

	private fun Element.extractImageUrl(): String? {
		for (attribute in IMAGE_ATTRIBUTES) {
			val url = absUrl(attribute).trim()
			if (url.isNotEmpty()) return url
		}
		return null
	}

	private fun Element.extractBackgroundImage(): String? =
		BACKGROUND_IMAGE_REGEX.find(attr("style"))
			?.groupValues
			?.getOrNull(1)
			?.trim()
			?.takeIf(String::isNotEmpty)
			?.toAbsoluteUrl(domain)

	private fun parseChapterDate(value: String?): Long = synchronized(chapterDateFormat) {
		chapterDateFormat.parseSafe(value)
	}

	private fun extractChapterNumber(title: String): Float =
		CHAPTER_NUMBER_REGEX.find(title)
			?.value
			?.replace(',', '.')
			?.toFloatOrNull()
			?: 0f

	private fun Set<MangaState>.toWebsiteStatus(): String = when {
		size != 1 -> "2,1"
		MangaState.ONGOING in this -> "2"
		MangaState.FINISHED in this -> "1"
		else -> "2,1"
	}

	private fun SortOrder.toWebsiteSort(): String = when (this) {
		SortOrder.NEWEST -> "-created_at"
		SortOrder.NEWEST_ASC -> "created_at"
		SortOrder.POPULARITY -> "-views"
		SortOrder.ALPHABETICAL -> "name"
		SortOrder.ALPHABETICAL_DESC -> "-name"
		else -> "-updated_at"
	}

	private fun mangaSeed(path: String, title: String): Manga = Manga(
		id = generateUid(path),
		url = path,
		publicUrl = path.toAbsoluteUrl(domain),
		title = title,
		altTitles = emptySet(),
		coverUrl = null,
		largeCoverUrl = null,
		authors = emptySet(),
		tags = emptySet(),
		state = null,
		description = null,
		contentRating = null,
		source = source,
		rating = RATING_UNKNOWN,
	)

	private fun baseUrl(path: String = ""): String =
		"https://$domain/${path.trimStart('/')}"

	private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
		timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
	}

	private companion object {
		const val DOMAIN = "cuutruyen.moe"
		const val LEGACY_DOMAIN = "cuutruyen.net"
		const val LEGACY_MANGA_PREFIX = "/api/v2/mangas/"
		const val PAGE_SIZE = 60
		const val WEBSITE_PASSWORD = "5"

		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
		val IMAGE_ATTRIBUTES = arrayOf("src", "data-src", "data-original", "data-lazy-src")
		val BACKGROUND_IMAGE_REGEX = Regex("""background-image:\s*url\(['"]?(.*?)['"]?\)""")
		val GENRE_ID_REGEX = Regex("""toggleGenre\('(\d+)'\)""")
		val WIRE_INITIAL_DATA_REGEX = Regex("""wire:initial-data="([^"]+)"""")
		val LIVEWIRE_TOKEN_REGEX = Regex("""livewire_token\s*=\s*'([^']+)'""")
		val CHAPTER_NUMBER_REGEX = Regex("""\d+(?:[.,]\d+)?""")
	}
}
