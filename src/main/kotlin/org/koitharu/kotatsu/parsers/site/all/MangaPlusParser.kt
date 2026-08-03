package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

internal abstract class MangaPlusParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	private val sourceLang: String,
) : SinglePageMangaParser(context, source), Interceptor {

	private val apiUrl = "https://jumpg-webapi.tokyo-cdn.com/api"
	override val configKeyDomain = ConfigKey.Domain("mangaplus.shueisha.co.jp")

	private companion object {
		private const val HEADER_VIEW_TOKEN = "Plus-Vw-Token"
		private const val FRAGMENT_KEY = "key"
		private const val FRAGMENT_TOKEN = "vt"
		private const val DEFAULT_TITLE_TYPE = "serializing"
	}

	/**
	 * The newer endpoints take the short form of the language in `lang`/`clang`,
	 * while the payloads keep naming it with the long enum form.
	 */
	private val langCode: String
		get() = when (sourceLang) {
			"ENGLISH" -> "eng"
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
			// TAG is only ever handed to a parser through this flag; the site
			// itself narrows by one genre at a time, so extras are ignored.
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = runCatchingCancellable { allTitlesV3Cache.get().second }
			.getOrDefault(emptySet()),
	)

	/**
	 * `all_v3` is the only endpoint that reports genres. It returns both the
	 * catalogue of tags and, per title, the genres it belongs to.
	 */
	private val allTitlesV3Cache = suspendLazy {
		val json = apiCall("/title_list/all_v3?type=$DEFAULT_TITLE_TYPE&lang=$langCode&clang=$langCode")
			.getJSONObject("allTitlesViewV3")

		val tags = json.optJSONArray("tags")?.asTypedList<JSONObject>().orEmpty()
			.mapNotNullTo(LinkedHashSet()) { tag ->
				val slug = tag.getStringOrNull("slug")?.nullIfEmpty() ?: return@mapNotNullTo null
				val name = tag.getStringOrNull("name")?.nullIfEmpty() ?: return@mapNotNullTo null
				MangaTag(key = slug, title = name, source = source)
			}
		val entries = json.optJSONArray("titles")?.asTypedList<JSONObject>().orEmpty()
		entries to tags
	}

	private suspend fun getListByTag(tags: Set<MangaTag>, query: String?): List<Manga> {
		val slugs = tags.mapTo(HashSet(tags.size)) { it.key }
		return allTitlesV3Cache.get().first
			.filter { entry ->
				entry.optJSONArray("genres")?.asTypedList<JSONObject>().orEmpty()
					.any { it.getStringOrNull("slug") in slugs }
			}
			.mapNotNull { it.optJSONObject("title") }
			.toMangaList(query)
	}

	private val extraHeaders = Headers.headersOf("Session-Token", UUID.randomUUID().toString())

	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		return when {
			filter.tags.isNotEmpty() -> getListByTag(filter.tags, filter.query)

			filter.query.isNullOrEmpty() -> {
				when (order) {
					SortOrder.POPULARITY -> getPopularList()
					SortOrder.UPDATED -> getLatestList()
					else -> getAllTitleList()
				}
			}

			else -> getAllTitleList(filter.query)
		}
	}

	private suspend fun getPopularList(): List<Manga> {
		val json = apiCall("/title_list/rankingV2?lang=$langCode&type=hottest&clang=$langCode")

		// Ranked titles arrive grouped into chart sections rather than as one
		// flat list, and each section repeats a work in every language.
		return json.getJSONObject("titleRankingView")
			.getJSONArray("rankedTitles")
			.mapJSON { it.optJSONArray("titles")?.asTypedList<JSONObject>().orEmpty() }
			.flatten()
			.toMangaList()
	}

	private suspend fun getLatestList(): List<Manga> {
		val json = apiCall("/web/web_homeV4?lang=$langCode&clang=$langCode")

		val latestTitles = json.getJSONObject("webHomeView")
			.getJSONArray("groups")
			.mapJSON { it.optJSONArray("titles")?.asTypedList<JSONObject>().orEmpty() }
			.flatten()
			.mapNotNull { it.optJSONObject("latestChapter")?.optJSONObject("title") }

		// The home feed reports whichever language published the update, so each
		// work is traced back through the all-titles groups — a group holds the
		// same work in every language — to the edition this source reads.
		val groups = allTitleGroupsCache.get()
		return latestTitles.mapNotNull { latest ->
			val titleId = latest.optInt("titleId", 0)
			groups.firstOrNull { group -> group.any { it.optInt("titleId", 0) == titleId } }
				?.firstOrNull { it.getStringOrNull("language").orDefaultLang() == sourceLang }
		}.distinctBy { it.optInt("titleId", 0) }
			.toMangaList()
	}

	// since search is local, save network calls on related manga call
	private val allTitleGroupsCache = suspendLazy {
		apiCall("/title_list/allV2")
			.getJSONObject("allTitlesViewV2")
			.getJSONArray("AllTitlesGroup")
			.mapJSON { it.optJSONArray("titles")?.asTypedList<JSONObject>().orEmpty() }
	}

	private val allTitleCache = suspendLazy {
		allTitleGroupsCache.get().flatten()
	}

	private fun String?.orDefaultLang(): String = this ?: "ENGLISH"

	private suspend fun getAllTitleList(query: String? = null): List<Manga> {
		return allTitleCache.get().toMangaList(query)
	}

	private fun List<JSONObject>.toMangaList(query: String? = null): List<Manga> {
		return mapNotNull {
			val language = it.getStringOrNull("language") ?: "ENGLISH"

			if (language != sourceLang) {
				return@mapNotNull null
			}

			val name = it.getString("name")
			val author = it.getString("author")
				.split('/')
				.joinToString(transform = String::trim)

			// filter out any other title or author which doesn't match search input
			if (query != null && !(name.contains(query, true) || author.contains(query, true))) {
				return@mapNotNull null
			}

			val titleId = it.getInt("titleId").toString()

			Manga(
				id = generateUid(titleId),
				url = titleId,
				publicUrl = "/titles/$titleId".toAbsoluteUrl(domain),
				title = name,
				coverUrl = it.getString("portraitImageUrl"),
				altTitles = emptySet(),
				authors = setOf(author),
				contentRating = null,
				rating = RATING_UNKNOWN,
				state = null,
				source = source,
				tags = emptySet(),
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val json = apiCall("/title_detailV3?title_id=${manga.url}&clang=$langCode")
			.getJSONObject("titleDetailView")
		val title = json.getJSONObject("title")

		val completed = json.getJSONObject("titleLabels")
			.getString("releaseSchedule").let {
				it == "DISABLED" || it == "COMPLETED"
			}

		val hiatus = json.getStringOrNull("nonAppearanceInfo")?.contains("on a hiatus") == true
		val author = title.getString("author")
			.split("/").joinToString(transform = String::trim)

		return manga.copy(
			title = title.getString("name"),
			publicUrl = "/titles/${title.getInt("titleId")}".toAbsoluteUrl(domain),
			coverUrl = title.getString("portraitImageUrl"),
			authors = setOf(author),
			description = buildString {
				json.getString("overview").let(::append)
				json.getStringOrNull("viewingPeriodDescription")
					?.takeIf { !completed }
					?.let { append("<br><br>", it) }
			},
			chapters = parseChapters(
				json.getJSONArray("chapterListGroup"),
				title.getStringOrNull("language") ?: "ENGLISH",
			),
			state = when {
				completed -> MangaState.FINISHED
				hiatus -> MangaState.PAUSED
				else -> MangaState.ONGOING
			},
		)
	}

	private fun parseChapters(chapterListGroup: JSONArray, language: String): List<MangaChapter> {
		val chapterList = chapterListGroup
			.asTypedList<JSONObject>()
			.flatMap {
				it.optJSONArray("firstChapterList")?.asTypedList<JSONObject>().orEmpty() +
					it.optJSONArray("lastChapterList")?.asTypedList<JSONObject>().orEmpty()
			}

		return chapterList.mapChapters { _, chapter ->
			val chapterId = chapter.getInt("chapterId").toString()
			val subtitle = chapter.getStringOrNull("subTitle") ?: return@mapChapters null

			MangaChapter(
				id = generateUid(chapterId),
				url = chapterId,
				title = subtitle,
				number = chapter.getString("name")
					.substringAfter("#")
					.toFloatOrNull() ?: -1f,
				volume = 0,
				uploadDate = chapter.getInt("startTimeStamp") * 1000L,
				branch = when (language) {
					"PORTUGUESE_BR" -> "Portuguese (Brazil)"
					else -> language.lowercase().toTitleCase()
				},
				scanlator = null,
				source = source,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val mangaViewer = apiCall(
			"/manga_viewer_v3?chapter_id=${chapter.url}&split=yes&img_quality=super_high&clang=$langCode",
		).getJSONObject("mangaViewer")
		val pages = mangaViewer.getJSONArray("pages")
		// Images are served only to the viewer session that requested them; the
		// token has to travel back out as a request header (see [intercept]).
		val viewToken = mangaViewer.getStringOrNull("viewToken")

		return pages.mapJSONNotNull {
			val mangaPage = it.optJSONObject("mangaPage")
				?: return@mapJSONNotNull null
			val url = mangaPage.getString("imageUrl")
			val encryptionKey = mangaPage.getStringOrNull("encryptionKey")
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
		val keyStream = key.chunked(2)
			.map { it.toInt(16) }

		return mapIndexed { i, byte -> byte.toInt() xor keyStream[i % keyStream.size] }
			.map(Int::toByte)
			.toByteArray()
	}

	private suspend fun apiCall(url: String): JSONObject {
		val newUrl = "$apiUrl$url".toHttpUrl().newBuilder()
			.addQueryParameter("format", "json")
			.build()
		val response = webClient.httpGet(newUrl, extraHeaders).parseJson()

		val success = response.optJSONObject("success")

		return checkNotNull(success) {
			val error = response.getJSONObject("error")
			val reason = error.getJSONArray("popups")
				.asTypedList<JSONObject>()
				.firstOrNull { it.getStringOrNull("language") == null }

			if (reason?.getStringOrNull("subject") == "Not Found" && url.contains("manga_viewer")) {
				"This chapter has expired"
			} else {
				reason?.getStringOrNull("body") ?: "Unknown Error"
			}
		}
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
