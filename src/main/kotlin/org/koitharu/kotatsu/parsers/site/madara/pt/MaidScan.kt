package org.koitharu.kotatsu.parsers.site.madara.pt

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("MAIDSCAN", "MaidScan", "pt")
internal class MaidScan(context: MangaLoaderContext) : PagedMangaParser(
	context,
	source = MangaParserSource.MAIDSCAN,
	pageSize = 24,
	searchPageSize = 15,
) {
	// empreguetes.xyz no longer completes a TLS handshake; the site is served
	// from verdinha.wtf, which is also where the CDN moved to — both
	// cdn.sussytoons.site and cdn.sussytoons.wtf now answer with a redirect to
	// HTML instead of the image.
	override val configKeyDomain = ConfigKey.Domain("verdinha.wtf", "empreguetes.xyz")
	private val apiUrl = "https://api.verdinha.wtf"
	private val cdnUrl = "https://cdn.verdinha.wtf"
	private val scanId = 3

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_TODAY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.POPULARITY_MONTH,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = fetchAvailableTags(),
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHUA,
				ContentType.MANHWA,
				ContentType.HENTAI,
			),
		)
	}

	private val apiHeaders: Headers
		get() = Headers.Builder()
			.add("Referer", "https://$domain/")
			.add("scan-id", scanId.toString())
			.build()

	// `cap_criado_em` is ISO-8601 in UTC, e.g. "2026-07-19T19:57:52.501Z".
	private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildSearchUrl(page, filter, order)

		val response = webClient.httpGet(url, apiHeaders).parseJson()
		val results = response.optJSONArray("obras") ?: return emptyList()
		return results.mapJSON { parseMangaFromJson(it) }
	}

	private fun buildSearchUrl(page: Int, filter: MangaListFilter, order: SortOrder): HttpUrl {
		val builder = "$apiUrl/obras/search".toHttpUrl().newBuilder()
			.addQueryParameter("pagina", page.toString())
			.addQueryParameter("limite", pageSize.toString())
			.addQueryParameter("todos_generos", "1")

		// Add search query
		if (!filter.query.isNullOrEmpty()) {
			builder.addQueryParameter("obr_nome", filter.query)
		}

		// Add sorting
		when (order) {
			SortOrder.UPDATED -> {
				builder.addQueryParameter("orderBy", "ultima_atualizacao")
				builder.addQueryParameter("orderDirection", "DESC")
			}
			SortOrder.POPULARITY -> {
				builder.addQueryParameter("orderBy", "media_rating")
				builder.addQueryParameter("orderDirection", "DESC")
			}
			else -> {
				builder.addQueryParameter("orderBy", "ultima_atualizacao")
				builder.addQueryParameter("orderDirection", "DESC")
			}
		}

		// Add tags
		filter.tags.forEach { tag ->
			builder.addQueryParameter("tags[]", tag.key)
		}

		// Add format (content type)
		filter.types.firstOrNull()?.let { contentType ->
			val type = when (contentType) {
				ContentType.MANHWA -> "1"
				ContentType.MANHUA -> "2"
				ContentType.MANGA -> "3"
				ContentType.HENTAI -> "5"
				else -> null
			}
			type?.let { builder.addQueryParameter("formt_id", it) }
		}

		// Add status
		filter.states.firstOrNull()?.let { state ->
			val statusId = when (state) {
				MangaState.ONGOING -> "1"
				MangaState.FINISHED -> "2"
				MangaState.PAUSED -> "3"
				MangaState.ABANDONED -> "4"
				else -> null
			}
			statusId?.let { builder.addQueryParameter("stt_id", it) }
		}

		return builder.build()
	}

	private fun parseMangaFromJson(json: JSONObject): Manga {
		val id = json.getInt("obr_id")
		val name = json.getString("obr_nome")
		val slug = json.optString("obr_slug", "").ifEmpty {
			name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
		}
		val coverPath = json.optString("obr_imagem", "").takeIf { it != "null" && it.isNotEmpty() } ?: ""

		val coverUrl = when {
			coverPath.isEmpty() -> null
			coverPath.startsWith("http") -> coverPath
			coverPath.startsWith("wp-content") -> "$cdnUrl/$coverPath"
			else -> "$cdnUrl/scans/$scanId/obras/$id/$coverPath"
		}

		// Get genre information
		val genero = json.optJSONObject("genero")
		val genreName = genero?.optString("gen_nome", "") ?: ""
		val isNsfw = genreName.equals("hentai", ignoreCase = true)

		val rating = json.optDouble("media_rating", 0.0).let {
			if (it > 0) (it / 5.0).toFloat() else RATING_UNKNOWN
		}

		return Manga(
			id = generateUid(id.toLong()),
			title = name,
			url = "/obras/$slug",
			publicUrl = "https://$domain/obras/$slug",
			coverUrl = coverUrl,
			source = source,
			rating = rating,
			altTitles = emptySet(),
			contentRating = if (isNsfw) ContentRating.ADULT else ContentRating.SAFE,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			largeCoverUrl = null,
			description = null,
			chapters = null,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val mangaSlug = manga.url.substringAfter("/obras/")
		val mangaJson = webClient.httpGet("$apiUrl/obras/$mangaSlug", apiHeaders).parseJson()

		val obraId = mangaJson.getInt("obr_id")

		val description = mangaJson.optString("obr_descricao")
			.replace(Regex("</?strong>"), "")
			.replace("\\/", "/")
			.replace(Regex("\\s+"), " ")
			.trim()

		val status = mangaJson.optJSONObject("status")
			?.optString("stt_nome")
			?.let { parseStatus(it) }

		val tags = mangaJson.optJSONArray("tags")?.mapJSON { tagJson ->
			val tagName = tagJson.optString("tag_nome").ifEmpty {
				tagJson.optString("nome", "")
			}
			val tagId = tagJson.optInt("tag_id").takeIf { it != 0 }
				?: tagJson.optInt("id", 0)
			MangaTag(
				key = tagId.toString(),
				title = tagName.toTitleCase(),
				source = source,
			)
		}?.toSet() ?: emptySet()

		val chapters = mangaJson.optJSONArray("capitulos")?.mapJSON { chapterJson ->
			parseChapter(chapterJson, obraId)
		} ?: emptyList()

		return manga.copy(
			title = mangaJson.optString("obr_nome", manga.title),
			description = description,
			state = status,
			tags = tags,
			chapters = chapters,
		)
	}

	private fun parseChapter(json: JSONObject, obraId: Int): MangaChapter {
		val chapterId = json.getInt("cap_id")
		val chapterName = json.getString("cap_nome")
		val chapterNumber = json.optDouble("cap_numero").toFloat()
		// Chapters that have not been unlocked yet still show up in the list.
		val isLocked = !json.optBoolean("cap_liberado", true)

		return MangaChapter(
			id = generateUid(chapterId.toLong()),
			title = if (isLocked) "🔒 $chapterName" else chapterName,
			number = chapterNumber,
			url = "/capitulo/$chapterId/$obraId",
			uploadDate = chapterDateFormat.parseSafe(json.optString("cap_criado_em")),
			source = source,
			volume = 0,
			scanlator = null,
			branch = null,
		)
	}

	private fun parseStatus(status: String): MangaState? = when (status.lowercase()) {
		"ativo", "em andamento" -> MangaState.ONGOING
		"completo", "concluído" -> MangaState.FINISHED
		"hiato", "pausado" -> MangaState.PAUSED
		"cancelado", "abandonado" -> MangaState.ABANDONED
		else -> null
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val urlParts = chapter.url.substringAfter("/capitulo/").split("/")
		val chapterId = urlParts[0]
		val obraId = urlParts.getOrNull(1)?.toIntOrNull() ?: 0

		// Fetch chapter data from API
		val chapterData = webClient.httpGet("$apiUrl/capitulos/$chapterId", apiHeaders).parseJson()

		val capNumero = chapterData.optInt("cap_numero")

		// Parse pages from the response
		val pagesArray = chapterData.optJSONArray("cap_paginas")
			?: throw Exception("No pages found in chapter")

		return pagesArray.mapJSONNotNull { pageJson ->
			val pageSrc = pageJson.optString("src")

			if (pageSrc.isEmpty()) return@mapJSONNotNull null

			// Each page carries a ready-made `path` relative to the CDN root
			// ("scans/3/obras/15083/capitulos/1/33353.png"); only fall back to
			// rebuilding it from the parts when that is missing.
			val pagePath = pageJson.optString("path").takeIf { it.isNotEmpty() && it != "null" }

			val imageUrl = when {
				// Already a full URL
				pageSrc.startsWith("http") -> pageSrc
				pagePath != null -> "$cdnUrl/${pagePath.trimStart('/')}"
				// WordPress manga path, looks like: "manga_.../hash/001.webp"
				pageSrc.startsWith("manga_") -> "$cdnUrl/wp-content/uploads/WP-manga/data/$pageSrc"
				// WordPress legacy path: "wp-content/uploads/..."
				pageSrc.startsWith("wp-content") -> "$cdnUrl/$pageSrc"
				obraId > 0 && capNumero > 0 -> "$cdnUrl/scans/$scanId/obras/$obraId/capitulos/$capNumero/$pageSrc"
				else -> "$cdnUrl/$pageSrc"
			}

			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				source = source,
				preview = null,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = "$apiUrl/tags".toHttpUrl().newBuilder()
			.build()

		val response = webClient.httpGet(url, apiHeaders).parseJson()
		// The payload keys this list "tags"; "resultados" never existed and left
		// the tag filter permanently empty.
		val tagsArray = response.optJSONArray("tags") ?: return emptySet()

		return tagsArray.mapJSON { tagJson ->
			val tagName = tagJson.optString("tag_nome").ifEmpty {
				tagJson.optString("nome", "")
			}
			val tagId = tagJson.optInt("tag_id").takeIf { it != 0 }
				?: tagJson.optInt("id", 0)
			MangaTag(
				key = tagId.toString(),
				title = tagName.toTitleCase(),
				source = source,
			)
		}.toSet()
	}
}
