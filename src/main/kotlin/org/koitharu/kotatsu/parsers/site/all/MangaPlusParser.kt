package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

/**
 * Responses are protobuf. The api also used to honour a `format=json` query
 * parameter, which every endpoint now answers with 403 — that is what broke the
 * source, not the endpoints themselves. Field numbers below come from the
 * protocol definition; see [ProtoMessage].
 */
internal abstract class MangaPlusParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val sourceLang: String,
) : SinglePageMangaParser(context, source), Interceptor {

	private val apiUrl = "https://jumpg-webapi.tokyo-cdn.com/api"
	override val configKeyDomain = ConfigKey.Domain("mangaplus.shueisha.co.jp")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			// TAG only ever reaches a parser through this flag. The site narrows
			// by a single genre, so any extra tag is ignored.
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = runCatchingCancellable { allTitlesV3Cache.get().second }
			.getOrDefault(emptySet()),
	)

	/** Short form used by `lang`/`clang`; the payloads use a numeric enum. */
	private val langCode: String
		get() = when (sourceLang) {
			"SPANISH" -> "esp"
			"FRENCH" -> "fra"
			"INDONESIAN" -> "ind"
			"PORTUGUESE_BR" -> "ptb"
			"RUSSIAN" -> "rus"
			"THAI" -> "tha"
			"VIETNAMESE" -> "vie"
			"GERMAN" -> "deu"
			else -> "eng"
		}

	private val langId: Int
		get() = when (sourceLang) {
			"SPANISH" -> 1
			"FRENCH" -> 2
			"INDONESIAN" -> 3
			"PORTUGUESE_BR" -> 4
			"RUSSIAN" -> 5
			"THAI" -> 6
			"GERMAN" -> 7
			"VIETNAMESE" -> 9
			else -> 0
		}

	private val branchName: String
		get() = when (sourceLang) {
			"PORTUGUESE_BR" -> "Portuguese (Brazil)"
			else -> sourceLang.lowercase().toTitleCase()
		}

	private val extraHeaders = Headers.headersOf("Session-Token", UUID.randomUUID().toString())

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		return when {
			filter.tags.isNotEmpty() -> getListByTag(filter.tags, filter.query)

			filter.query.isNullOrEmpty() -> when (order) {
				SortOrder.POPULARITY -> getPopularList()
				SortOrder.UPDATED -> getLatestList()
				else -> getAllTitleList()
			}

			else -> getAllTitleList(filter.query)
		}
	}

	private suspend fun getPopularList(): List<Manga> {
		// Ranked titles come grouped into chart sections rather than one flat
		// list, and every section repeats a work in each language.
		return apiCall("/title_list/rankingV2?lang=$langCode&type=hottest&clang=$langCode")
			.message(FIELD_TITLE_RANKING_VIEW)
			?.messages(3)
			?.flatMap { it.messages(2) }
			.orEmpty()
			.toMangaList()
	}

	private suspend fun getLatestList(): List<Manga> {
		val latestTitles = apiCall("/web/web_homeV4?lang=$langCode&clang=$langCode")
			.message(FIELD_WEB_HOME_VIEW)
			?.messages(2)
			?.flatMap { group -> group.messages(2) }
			?.mapNotNull { updated -> updated.message(3)?.message(1) }
			.orEmpty()

		// The feed reports whichever language published the update, so each work
		// is traced back through the all-titles groups — a group holds the same
		// work in every language — to the edition this source reads.
		val groups = allTitleGroupsCache.get()
		return latestTitles.mapNotNull { latest ->
			val titleId = latest.int(FIELD_TITLE_ID) ?: return@mapNotNull null
			groups.firstOrNull { group -> group.any { it.int(FIELD_TITLE_ID) == titleId } }
				?.firstOrNull { it.langId == langId }
		}.distinctBy { it.int(FIELD_TITLE_ID) }
			.toMangaList()
	}

	// since search is local, save network calls on related manga call
	private val allTitleGroupsCache = suspendLazy {
		apiCall("/title_list/allV2")
			.message(FIELD_ALL_TITLES_VIEW)
			?.messages(1)
			?.map { it.messages(2) }
			.orEmpty()
	}

	private val allTitleCache = suspendLazy { allTitleGroupsCache.get().flatten() }

	/** `all_v3` is the only endpoint that reports genres. */
	private val allTitlesV3Cache = suspendLazy {
		val view = apiCall("/title_list/all_v3?type=serializing&lang=$langCode&clang=$langCode")
			.message(FIELD_ALL_TITLES_VIEW_V3)
		val tags = view?.messages(2).orEmpty().mapNotNullTo(LinkedHashSet()) { tag ->
			val name = tag.string(1)?.nullIfEmpty() ?: return@mapNotNullTo null
			val slug = tag.string(2)?.nullIfEmpty() ?: return@mapNotNullTo null
			MangaTag(key = slug, title = name, source = source)
		}
		view?.messages(3).orEmpty() to tags
	}

	private suspend fun getAllTitleList(query: String? = null): List<Manga> {
		return allTitleCache.get().toMangaList(query)
	}

	private suspend fun getListByTag(tags: Set<MangaTag>, query: String?): List<Manga> {
		val slugs = tags.mapTo(HashSet(tags.size)) { it.key }
		return allTitlesV3Cache.get().first
			.filter { entry -> entry.messages(3).any { it.string(2) in slugs } }
			.mapNotNull { it.message(2) }
			.toMangaList(query)
	}

	private val ProtoMessage.langId: Int get() = int(FIELD_TITLE_LANGUAGE) ?: 0

	private fun ProtoMessage.authorName(): String = string(FIELD_TITLE_AUTHOR)
		.orEmpty()
		.split('/')
		.joinToString(transform = String::trim)

	private fun List<ProtoMessage>.toMangaList(query: String? = null): List<Manga> {
		return mapNotNull {
			if (it.langId != langId) {
				return@mapNotNull null
			}
			val titleId = it.int(FIELD_TITLE_ID) ?: return@mapNotNull null
			val name = it.string(FIELD_TITLE_NAME)?.nullIfEmpty() ?: return@mapNotNull null
			val author = it.authorName()

			// filter out any other title or author which doesn't match search input
			if (query != null && !(name.contains(query, true) || author.contains(query, true))) {
				return@mapNotNull null
			}

			Manga(
				id = generateUid(titleId.toString()),
				url = titleId.toString(),
				publicUrl = "/titles/$titleId".toAbsoluteUrl(domain),
				title = name,
				coverUrl = it.string(FIELD_TITLE_PORTRAIT).orEmpty(),
				altTitles = emptySet(),
				authors = setOfNotNull(author.nullIfEmpty()),
				contentRating = null,
				rating = RATING_UNKNOWN,
				state = null,
				source = source,
				tags = emptySet(),
			)
		}.distinctBy(Manga::id)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		// A manga resolved from a shared link carries the whole path
		// ("/titles/100020") rather than the bare id this endpoint wants.
		val titleId = manga.url.removeSuffix("/").substringAfterLast('/')
		val detail = apiCall("/title_detailV3?title_id=$titleId&clang=$langCode")
			.message(FIELD_TITLE_DETAIL_VIEW)
			?: throw ParseException("No details for title ${manga.url}", manga.publicUrl)
		val title = detail.message(1)
			?: throw ParseException("No title in details", manga.publicUrl)

		val overview = detail.string(3).orEmpty()
		val viewingPeriod = detail.string(7).orEmpty()
		val nonAppearance = detail.string(8).orEmpty()
		val genres = detail.messages(31)

		val isOneShot = genres.any { it.string(2) == "one-shot" }
		val completed = isOneShot ||
			nonAppearance.contains(COMPLETED_REGEX) ||
			viewingPeriod.contains("latest 0 chapters")
		val hiatus = nonAppearance.contains(HIATUS_REGEX)

		return manga.copy(
			title = title.string(FIELD_TITLE_NAME)?.nullIfEmpty() ?: manga.title,
			publicUrl = "/titles/${title.int(FIELD_TITLE_ID) ?: manga.url}".toAbsoluteUrl(domain),
			coverUrl = title.string(FIELD_TITLE_PORTRAIT)?.nullIfEmpty() ?: manga.coverUrl,
			authors = setOfNotNull(title.authorName().nullIfEmpty()),
			description = listOf(overview, viewingPeriod.takeUnless { completed }.orEmpty())
				.filter { it.isNotEmpty() }
				.joinToString("\n\n"),
			tags = genres.mapNotNullTo(LinkedHashSet()) { tag ->
				val name = tag.string(1)?.nullIfEmpty() ?: return@mapNotNullTo null
				val slug = tag.string(2)?.nullIfEmpty() ?: name
				MangaTag(key = slug, title = name, source = source)
			},
			chapters = parseChapters(detail.messages(28)),
			state = when {
				completed -> MangaState.FINISHED
				hiatus -> MangaState.PAUSED
				else -> MangaState.ONGOING
			},
		)
	}

	private fun parseChapters(chapterListGroup: List<ProtoMessage>): List<MangaChapter> {
		// A group splits its chapters across three lists — first (2), middle (3)
		// and last (4) — and the outer two are capped at three entries each.
		// Reading only those caps every title at six chapters and silently drops
		// everything the middle list holds.
		return chapterListGroup
			.flatMap { it.messages(2) + it.messages(3) + it.messages(4) }
			.mapChapters { _, chapter ->
				val chapterId = chapter.int(2)?.toString() ?: return@mapChapters null
				// An expired chapter drops its subtitle and can no longer be read.
				val subtitle = chapter.string(4)?.nullIfEmpty() ?: return@mapChapters null

				MangaChapter(
					id = generateUid(chapterId),
					url = chapterId,
					title = subtitle,
					number = chapter.string(3).orEmpty()
						.substringAfter("#")
						.toFloatOrNull() ?: -1f,
					volume = 0,
					uploadDate = (chapter.long(6) ?: 0L) * 1000L,
					branch = branchName,
					scanlator = null,
					source = source,
				)
			}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val viewer = apiCall(
			"/manga_viewer_v3?chapter_id=${chapter.url}&split=yes&img_quality=super_high&clang=$langCode",
		).message(FIELD_MANGA_VIEWER)
			?: throw ParseException("No viewer data for chapter ${chapter.url}", chapter.url)

		// Images are served only to the viewer session that asked for them; the
		// token has to travel back out as a request header (see [intercept]).
		val viewToken = viewer.string(19)

		return viewer.messages(1).mapNotNull { page ->
			val mangaPage = page.message(1) ?: return@mapNotNull null
			val url = mangaPage.string(1)?.nullIfEmpty() ?: return@mapNotNull null
			val encryptionKey = mangaPage.string(5)?.nullIfEmpty()
			MangaPage(
				id = generateUid(url),
				url = url + buildPageFragment(encryptionKey, viewToken),
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * Both values ride along in the fragment, which is never sent to the server.
	 * A fragment holding nothing but hex is still read as a bare encryption key
	 * so pages stored before the token existed keep working.
	 */
	private fun buildPageFragment(encryptionKey: String?, viewToken: String?): String {
		val parts = buildList {
			encryptionKey?.let { add("$FRAGMENT_KEY=$it") }
			viewToken?.nullIfEmpty()?.let { add("$FRAGMENT_TOKEN=${it.urlEncoded()}") }
		}
		return if (parts.isEmpty()) "" else "#" + parts.joinToString("&")
	}

	private fun String.fragmentValue(name: String): String? {
		if ('=' !in this) {
			return if (name == FRAGMENT_KEY) this else null
		}
		return split('&')
			.map { it.split('=', limit = 2) }
			.firstOrNull { it.size == 2 && it[0] == name }
			?.get(1)
			?.urlDecode()
			?.nullIfEmpty()
	}

	// image descrambling
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val fragment = request.url.fragment

		if (fragment.isNullOrEmpty()) {
			return chain.proceed(request)
		}

		val viewToken = fragment.fragmentValue(FRAGMENT_TOKEN)
		val response = chain.proceed(
			if (viewToken != null) {
				request.newBuilder().header(HEADER_VIEW_TOKEN, viewToken).build()
			} else {
				request
			},
		)

		val encryptionKey = fragment.fragmentValue(FRAGMENT_KEY)
		if (encryptionKey.isNullOrEmpty()) {
			return response
		}

		return response.map { responseBody ->
			val contentType = response.headers["Content-Type"] ?: "image/jpeg"
			val image = responseBody.bytes().decodeXorCipher(encryptionKey)
			image.toResponseBody(contentType.toMediaTypeOrNull())
		}
	}

	private fun ByteArray.decodeXorCipher(key: String): ByteArray {
		val keyStream = key.chunked(2).map { it.toInt(16) }

		return mapIndexed { i, byte -> byte.toInt() xor keyStream[i % keyStream.size] }
			.map(Int::toByte)
			.toByteArray()
	}

	/** Returns the `success` message, or throws with the popup the api supplies. */
	private suspend fun apiCall(url: String): ProtoMessage {
		val response = webClient.httpGet("$apiUrl$url".toHttpUrl(), extraHeaders)
		val body = response.requireBody().bytes()
		val root = ProtoMessage.parse(body)

		root.message(FIELD_SUCCESS)?.let { return it }

		val error = root.message(FIELD_ERROR)
		// Field 2 is the English popup, field 3 the Spanish one.
		val popup = error?.message(if (langId == 1) 3 else 2) ?: error?.message(2)
		val subject = popup?.string(1)
		val message = when {
			subject == "Not Found" && url.contains("manga_viewer") -> "This chapter has expired"
			else -> popup?.string(2)?.nullIfEmpty() ?: "Unknown Error"
		}
		throw ParseException(message, "$apiUrl$url")
	}

	private companion object {
		private const val HEADER_VIEW_TOKEN = "Plus-Vw-Token"
		private const val FRAGMENT_KEY = "key"
		private const val FRAGMENT_TOKEN = "vt"

		// MangaPlusResponse
		private const val FIELD_SUCCESS = 1
		private const val FIELD_ERROR = 2

		// SuccessResult
		private const val FIELD_TITLE_DETAIL_VIEW = 8
		private const val FIELD_MANGA_VIEWER = 10
		private const val FIELD_ALL_TITLES_VIEW = 25
		private const val FIELD_ALL_TITLES_VIEW_V3 = 35
		private const val FIELD_TITLE_RANKING_VIEW = 37
		private const val FIELD_WEB_HOME_VIEW = 38

		// Title
		private const val FIELD_TITLE_ID = 1
		private const val FIELD_TITLE_NAME = 2
		private const val FIELD_TITLE_AUTHOR = 3
		private const val FIELD_TITLE_PORTRAIT = 4
		private const val FIELD_TITLE_LANGUAGE = 7

		private val COMPLETED_REGEX = "completado|completed?|completo".toRegex(RegexOption.IGNORE_CASE)
		private val HIATUS_REGEX = "on a hiatus".toRegex(RegexOption.IGNORE_CASE)
	}

	@MangaSourceParser("MANGAPLUSPARSER_EN", "MANGA Plus English", "en")
	class English(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_EN,
		"ENGLISH",
	)

	@MangaSourceParser("MANGAPLUSPARSER_ES", "MANGA Plus Spanish", "es")
	class Spanish(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_ES,
		"SPANISH",
	)

	@MangaSourceParser("MANGAPLUSPARSER_FR", "MANGA Plus French", "fr")
	class French(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_FR,
		"FRENCH",
	)

	@MangaSourceParser("MANGAPLUSPARSER_ID", "MANGA Plus Indonesian", "id")
	class Indonesian(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_ID,
		"INDONESIAN",
	)

	@MangaSourceParser("MANGAPLUSPARSER_PTBR", "MANGA Plus Portuguese (Brazil)", "pt")
	class Portuguese(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_PTBR,
		"PORTUGUESE_BR",
	)

	@MangaSourceParser("MANGAPLUSPARSER_RU", "MANGA Plus Russian", "ru")
	class Russian(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_RU,
		"RUSSIAN",
	)

	@MangaSourceParser("MANGAPLUSPARSER_TH", "MANGA Plus Thai", "th")
	class Thai(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_TH,
		"THAI",
	)

	@MangaSourceParser("MANGAPLUSPARSER_VI", "MANGA Plus Vietnamese", "vi")
	class Vietnamese(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_VI,
		"VIETNAMESE",
	)

	@MangaSourceParser("MANGAPLUSPARSER_DE", "MANGA Plus German", "de")
	class German(context: MangaLoaderContext) : MangaPlusParser(
		context,
		MangaParserSource.MANGAPLUSPARSER_DE,
		"GERMAN",
	)
}
