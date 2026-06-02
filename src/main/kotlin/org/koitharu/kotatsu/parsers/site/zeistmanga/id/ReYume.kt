package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import java.text.SimpleDateFormat

@MangaSourceParser("REYUME", "ReYume", "id")
internal class ReYume(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.REYUME, "www.re-yume.my.id") {

	override val selectTags = "a[rel=tag]"

	override val filterCapabilities = org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true
	)

	override suspend fun getListPage(page: Int, order: org.koitharu.kotatsu.parsers.model.SortOrder, filter: org.koitharu.kotatsu.parsers.model.MangaListFilter): List<org.koitharu.kotatsu.parsers.model.Manga> {
		val startIndex = maxMangaResults * (page - 1) + 1
		val url = buildString {
			append("https://")
			append(domain)
			append("/feeds/posts/default/-/")
			when {
				!filter.query.isNullOrEmpty() -> {
					append("Series")
					append("?alt=json&orderby=published&max-results=")
					append((maxMangaResults + 1).toString())
					append("&start-index=")
					append(startIndex.toString())
					append("&q=label:Series+")
					append(filter.query.urlEncoded())
				}
				else -> {
					if (filter.tags.isNotEmpty()) {
						append(filter.tags.joinToString("/") { it.key.urlEncoded() })
					} else {
						append("Series")
					}
					append("?alt=json&orderby=published&max-results=")
					append((maxMangaResults + 1).toString())
					append("&start-index=")
					append(startIndex.toString())
				}
			}
		}

		val json = webClient.httpGet(url).parseJson().getJSONObject("feed")
		val list = if (json.toString().contains("\"entry\":")) {
			parseMangaList(json.getJSONArray("entry"))
		} else {
			emptyList()
		}
		return if (list.size > maxMangaResults) list.dropLast(1) else list
	}

	override suspend fun getDetails(manga: org.koitharu.kotatsu.parsers.model.Manga): org.koitharu.kotatsu.parsers.model.Manga {
		val baseDetails = super.getDetails(manga)
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		
		val title = if (baseDetails.title == "Unknown manga" || baseDetails.title.isEmpty()) {
			doc.selectFirst("h1#post-title")?.text() ?: baseDetails.title
		} else {
			baseDetails.title
		}

		var desc = baseDetails.description
		if (desc.isNullOrEmpty()) {
			desc = doc.getElementById("syn_bod")?.text() ?: ""
		}

		var author = baseDetails.authors
		if (author.isEmpty()) {
			val authorText = doc.getElementById("tauther")?.text() ?: doc.getElementById("tauthers")?.text()
			if (!authorText.isNullOrEmpty()) {
				author = setOf(authorText)
			}
		}

		var state = baseDetails.state
		if (state == null) {
			val stateText = doc.select("span.capitalize").map { it.text().lowercase() }.firstOrNull { 
				it in ongoing || it in finished || it in abandoned || it in paused 
			}
			if (stateText != null) {
				state = when (stateText) {
					in ongoing -> org.koitharu.kotatsu.parsers.model.MangaState.ONGOING
					in finished -> org.koitharu.kotatsu.parsers.model.MangaState.FINISHED
					in abandoned -> org.koitharu.kotatsu.parsers.model.MangaState.ABANDONED
					in paused -> org.koitharu.kotatsu.parsers.model.MangaState.PAUSED
					else -> null
				}
			}
		}
		
		return baseDetails.copy(
			title = title,
			description = desc,
			authors = author,
			state = state
		)
	}

	override fun parseMangaList(json: JSONArray): List<org.koitharu.kotatsu.parsers.model.Manga> {
		return super.parseMangaList(json).map { 
			val cleanUrl = it.url.substringBefore("?m=1").substringBefore("&m=1")
			val cleanPublic = it.publicUrl.substringBefore("?m=1").substringBefore("&m=1")
			it.copy(
				url = cleanUrl,
				publicUrl = cleanPublic,
				id = generateUid(cleanUrl)
			)
		}
	}

	override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		val label = doc.selectFirst(".chapter_get")?.attr("data-labelchapter")
			?: doc.selectFirst(".libraryAdd")?.attr("data-title")
			?: return super.loadChapters(mangaUrl, doc)

		val url = buildString {
			append("https://")
			append(domain)
			append("/feeds/posts/default/-/")
			append(label.urlEncoded())
			append("?alt=json&orderby=published&max-results=9999")
		}
		
		val json = webClient.httpGet(url).parseJson().getJSONObject("feed").getJSONArray("entry").asTypedList<JSONObject>().reversed()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val mangaTitle = doc.selectFirst("h1#post-title")?.text().orEmpty()
		
		return json.mapIndexedNotNull { i, j ->
			val name = j.getJSONObject("title").getString("\$t")
			val chapterName = if (mangaTitle.isNotEmpty() && name.contains(mangaTitle, ignoreCase = true)) {
				name.replace(mangaTitle, "", ignoreCase = true).trim().trim('-').trim().takeIf { it.isNotEmpty() } ?: name
			} else {
				name
			}
			val href = j.getJSONArray("link").asTypedList<JSONObject>().first { it.getString("rel") == "alternate" }.getString("href")
			val dateText = j.getJSONObject("published").getString("\$t").substringBefore("T")
			val slug = mangaUrl.substringAfterLast('/')
			val slugChapter = href.substringAfterLast('/')
			if (slug == slugChapter) return@mapIndexedNotNull null
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = chapterName,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = dateFormat.parseSafe(dateText),
				scanlator = null,
				source = source,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		val script = doc.selectFirst("script:containsData(filterGenre =)")?.data()
		if (script != null) {
			val genres = script.substringAfter("filterGenre = [").substringBefore("]")
				.replace("'", "").replace("\"", "").split(",")
			return genres.mapToSet {
				val tag = it.trim()
				MangaTag(key = tag, title = tag, source = source)
			}
		}
		return super.fetchAvailableTags()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select(selectPage)
			.filter { el -> el.parents().any { it.className().contains("separator") } }
			.map { img ->
				MangaPage(
					id = generateUid(img.requireSrc()),
					url = img.requireSrc(),
					preview = null,
					source = source,
				)
			}
	}
}
