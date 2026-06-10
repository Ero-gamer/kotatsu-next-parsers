package org.koitharu.kotatsu.parsers.site.madara.fr

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
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
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.selectLast
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale
import java.util.Base64

@MangaSourceParser("RAIJINSCANS", "RaijinScans", "fr")
internal class RaijinScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.RAIJINSCANS, "raijin-scans.fr", 21) {

	private val descriptionScriptRegex = """content\.innerHTML = `([\s\S]+?)`;""".toRegex()
	override val datePattern = "dd/MM/yyyy"
	override val withoutAjax = true
	override val listUrl = ""
	override val tagPrefix = "genre/"
	override val selectBodyPage = "div.protected-image-data"
	override val selectChapter = "ul.scroll-sm li.item"
	override val selectDate = "span:nth-of-type(2)"
	override val selectPage = "div.protected-image-data"
	override val selectGenre = "div.genre-list div.genre-link"
	override val selectDesc = "div.description-content"
	override val selectState = "div.stat-item:has(span:contains(État du titre)) span.manga"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isYearSupported = true,
			isSearchWithFiltersSupported = true,
		)

	private lateinit var tagMap: Map<String, String>

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val availableTags = fetchAvailableTags()
		tagMap = availableTags.associateBy({ it.title }, { it.key })

		return MangaListFilterOptions(
			availableTags = availableTags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://$domain")
			if (page > 0) append("/page/${page + 1}")

			append("?post_type=wp-manga&s=")
			filter.query?.let { append(it.urlEncoded()) }

			if (filter.year != YEAR_UNKNOWN) append("&release[]=${filter.year}")
			if (!filter.tags.isEmpty()) {
				append("&genre_mode=and")
				filter.tags.forEach { append("&genre[]=${it.key}") }
			}

			filter.states.forEach {
				val status = when (it) {
					MangaState.ONGOING -> "on-going"
					MangaState.FINISHED -> "end"
					else -> ""
				}
				if (status.isNotEmpty()) append("&status[]=$status")
			}

			val sortOrder = when (order) {
				SortOrder.POPULARITY -> "most_viewed"
				SortOrder.UPDATED -> "recently_added"
				SortOrder.ALPHABETICAL -> "title_az"
				else -> "recently_added"
			}
			if (sortOrder.isNotEmpty()) append("&sort=$sortOrder")
		}

		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}


	override fun parseMangaList(doc: Document): List<Manga> {
		val elements = doc.select("div.original.card-lg div.unit")
		return elements.map { element ->
			val linkElement =
				element.selectFirst("a.c-title") ?: element.selectFirst("div.info > a") ?: element.selectFirst("a")
				?: error("link not found")

			val href = linkElement.attrAsRelativeUrl("href")
			val title = linkElement.text()
			val cover = element.selectLast("div.poster-image-wrapper > img")?.src()

			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = cover,
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = ContentRating.SAFE,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val title = doc.selectFirst("h1.serie-title")?.text() ?: manga.title
		val cover = doc.selectFirst("img.cover")?.src() ?: manga.coverUrl

		val author = doc.selectFirst("div.stat-item:has(span:contains(Auteur)) span.stat-value")?.text()

		val scriptDescription = doc.select("script:containsData(content.innerHTML)")
			.firstNotNullOfOrNull { descriptionScriptRegex.find(it.data())?.groupValues?.get(1)?.trim() }
		val description = scriptDescription ?: doc.selectFirst(selectDesc)?.text()

		// Ensure tagMap is initialized
		if (!::tagMap.isInitialized) {
			val availableTags = fetchAvailableTags()
			tagMap = availableTags.associateBy({ it.title }, { it.key })
		}

		val genres = doc.select(selectGenre).mapNotNullToSet { a ->
			val genreTitle = a.text().trim().toTitleCase()

			if (genreTitle.isBlank()) {
				null // Skip empty genres
			} else {
				val genreId = tagMap[genreTitle]

				if (genreId != null) {
					MangaTag(
						key = genreId,
						title = genreTitle,
						source = source,
					)
				} else {
					// Genre not found in filter options, skip it gracefully
					null
				}
			}
		}

		val state = doc.selectFirst(selectState)?.text()?.lowercase()?.let { stateText ->
			when {
				"en cours" in stateText -> MangaState.ONGOING
				"terminé" in stateText -> MangaState.FINISHED
				else -> null
			}
		}

		val rating = doc.select(".vote-count").textOrNull()?.toFloat()?.div(10f) ?: RATING_UNKNOWN

		return manga.copy(
			title = title,
			coverUrl = cover,
			authors = setOfNotNull(author),
			description = description,
			tags = genres,
			state = state,
			chapters = getChapters(manga, doc),
			rating = rating,
		)
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		return doc.select(selectChapter).mapChapters(reversed = true) { i, element ->
			val link = element.selectFirstOrThrow("a")
			val href = link.attrAsRelativeUrl("href")
			val name = link.attr("title").trim()
			val dateText = link.selectFirst(selectDate)?.text()

			MangaChapter(
				id = generateUid(href),
				title = name,
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = parseRelativeDate(dateText ?: ""),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		decodeRjAjaxConfig(doc)?.let { config ->
			val headers = getRequestHeaders().newBuilder()
				.set("Referer", chapterUrl)
				.set("X-Requested-With", "XMLHttpRequest")
				.build()
			val response = webClient.httpPost(config.ajaxUrl.toHttpUrl(), config.form, headers).parseJson()
			val pages = response
				.optJSONObject(config.responseKeys.getString(1))
				?.optJSONArray(config.responseKeys.getString(2))
				?: JSONArray()
			val imageKey = config.responseKeys.getString(4)
			return pages.mapJsonPages(imageKey)
		}

		return doc.select(selectPage).map { element ->
			val encodedUrl = element.attr("data-src")
			val imageUrl = String(context.decodeBase64(encodedUrl))

			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun JSONArray.mapJsonPages(imageKey: String): List<MangaPage> {
		return (0 until length()).mapNotNull { i ->
			val imageUrl = optJSONObject(i)?.optString(imageKey)?.takeIf { it.isNotBlank() }
				?: return@mapNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun decodeRjAjaxConfig(doc: Document): RjAjaxConfig? {
		// The reader config is delivered inline as window["rjfr_<hash>"].push({"m":..,"c":..}).
		// "m" is a pipe-separated list of token ids giving the order in which the base64
		// fragments in the "c" map must be concatenated; the result decodes to a JSON object
		// {"m":[..],"d":[..],"l":[..]} where "d" holds the scrambled config and "m"/"l" are
		// two permutations that must both be applied to recover the real field order.
		val payloadJson = doc.select("script")
			.firstNotNullOfOrNull { script -> RJ_PAYLOAD_REGEX.find(script.data())?.value }
			?: return null
		val payload = JSONObject(payloadJson)
		val fragments = payload.getJSONObject("c")
		val base64 = buildString {
			payload.getString("m").split('|').forEach { token -> append(fragments.optString(token)) }
		}
		if (base64.isBlank()) return null

		val inner = JSONObject(decodeBase64Chunk(base64))
		val firstOrder = inner.getJSONArray("m")
		val scrambled = inner.getJSONArray("d")
		val secondOrder = inner.getJSONArray("l")
		// First pass: place each scrambled value at its index in "m".
		val intermediate = arrayOfNulls<Any>(firstOrder.length())
		for (i in 0 until firstOrder.length()) {
			intermediate[firstOrder.getInt(i)] = scrambled.get(i)
		}
		// Second pass: gather through "l" to get the final, fixed field layout.
		val values = arrayOfNulls<Any>(secondOrder.length())
		for (j in 0 until secondOrder.length()) {
			values[j] = intermediate[secondOrder.getInt(j)]
		}

		val fieldNames = values[13] as? JSONArray ?: return null
		val responseKeys = values[14] as? JSONArray ?: return null
		val ajaxUrl = values[0]?.jsonStringOrNull() ?: return null
		val action = values[12]?.jsonStringOrNull() ?: return null
		val empty = values[1]?.jsonStringOrNull().orEmpty()
		val form = LinkedHashMap<String, String>(fieldNames.length() + 1)
		form["action"] = action
		form[fieldNames.getString(0)] = empty
		form[fieldNames.getString(1)] = values[2].jsonStringOrEmpty()
		form[fieldNames.getString(2)] = values[3].jsonStringOrEmpty()
		form[fieldNames.getString(3)] = values[4].jsonStringOrEmpty()
		form[fieldNames.getString(4)] = values[5].jsonStringOrEmpty()
		form[fieldNames.getString(5)] = values[6].jsonStringOrEmpty()
		form[fieldNames.getString(6)] = values[8].jsonStringOrEmpty()
		form[fieldNames.getString(7)] = values[9].jsonStringOrEmpty()
		form[fieldNames.getString(8)] = values[7].jsonStringOrEmpty()
		form[fieldNames.getString(9)] = empty
		return RjAjaxConfig(ajaxUrl, form, responseKeys)
	}

	private fun decodeBase64Chunk(value: String): String {
		val padded = value + "=".repeat((4 - value.length % 4) % 4)
		return String(Base64.getDecoder().decode(padded))
	}

	private fun Any?.jsonStringOrEmpty(): String = jsonStringOrNull().orEmpty()

	private fun Any?.jsonStringOrNull(): String? = when (this) {
		null, JSONObject.NULL -> null
		else -> toString()
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/?post_type=wp-manga&s=").parseHtml()

		return doc.select("ul.dropdown-menu.c1 li input[type=checkbox][name='genre[]']").mapNotNullToSet { input ->
			val value = input.attr("value")
			val label = input.nextElementSibling()?.text()?.trim()

			if (value.isNotEmpty() && !label.isNullOrEmpty()) {
				MangaTag(
					key = value,
					title = label.toTitleCase(),
					source = source,
				)
			} else {
				null
			}
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val lcDate = date.lowercase(Locale.FRENCH).trim()
		val cal = Calendar.getInstance()
		val number = """(\d+)""".toRegex().find(lcDate)?.value?.toIntOrNull()

		return when {
			"aujourd'hui" in lcDate -> cal.timeInMillis
			"hier" in lcDate -> cal.apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
			number != null -> when {
				("h" in lcDate || "heure" in lcDate) && "chapitre" !in lcDate -> cal.apply {
					add(
						Calendar.HOUR_OF_DAY,
						-number,
					)
				}.timeInMillis

				"min" in lcDate -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
				"jour" in lcDate || lcDate.endsWith("j") -> cal.apply {
					add(
						Calendar.DAY_OF_MONTH,
						-number,
					)
				}.timeInMillis

				"semaine" in lcDate -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
				"mois" in lcDate || (lcDate.endsWith("m") && "min" !in lcDate) -> cal.apply {
					add(
						Calendar.MONTH,
						-number,
					)
				}.timeInMillis

				"an" in lcDate -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
				else -> 0L
			}

			else -> parseChapterDate(SimpleDateFormat(datePattern, sourceLocale), date)
		}
	}

	private data class RjAjaxConfig(
		val ajaxUrl: String,
		val form: Map<String, String>,
		val responseKeys: JSONArray,
	)

	private companion object {
		private val RJ_PAYLOAD_REGEX = Regex("""\{"m":"[^"]*","c":\{[^}]*\}\}""")
	}
}
