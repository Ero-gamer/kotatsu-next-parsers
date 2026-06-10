package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers.Companion.toHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVE, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")
    
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)",
        "Referer" to "https://$domain/"
    ).toHeaders()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain/", headers).parseHtml()
        val tags = doc.select("a[href^=/genre/]").mapNotNull {
            val key = it.attr("href").substringAfterLast("/")
            val title = it.selectFirst("span.mx-1")?.text()?.trim() ?: key
            if (key.isNotBlank()) MangaTag(title, key, source) else null
        }.toSet()
        return MangaListFilterOptions(availableTags = tags)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/search?q=${filter.query.urlEncoded()}"
        } else if (filter.tags.isNotEmpty()) {
            val tag = filter.tags.first().key
            "https://$domain/genre/$tag" + if (page > 0) "?page=${page + 1}" else ""
        } else {
            if (page > 0) "https://$domain/?page=${page + 1}" else "https://$domain/"
        }

        val doc = webClient.httpGet(url, headers).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val pageProps = JSONObject(scriptData).optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()

        val mangaArray = pageProps.optJSONArray("res") 
            ?: pageProps.optJSONArray("data") 
            ?: pageProps.optJSONArray("terbaru")
            ?: findMangaArray(pageProps) 
            ?: return emptyList()

        val mangaList = mutableListOf<Manga>()
        for (i in 0 until mangaArray.length()) {
            val jo = mangaArray.getJSONObject(i)
            val id = jo.optString("id", "")
            if (id.isEmpty()) continue

            var coverUrl = jo.optString("cover", "")
            if (coverUrl.isNotEmpty()) {
                val fileName = coverUrl.substringAfterLast("/")
                coverUrl = "https://cdn.thrive.moe/covers/$id/$fileName.256.jpg"
            }

            mangaList.add(Manga(
                id = generateUid(id),
                title = jo.optString("title", "Untitled"),
                altTitles = emptySet(),
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            ))
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, headers).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return manga
        val pageProps = JSONObject(scriptData).optJSONObject("props")?.optJSONObject("pageProps") ?: return manga

        val mangaObj = pageProps.optJSONObject("manga") ?: pageProps.optJSONObject("res") ?: findMangaObject(pageProps) ?: pageProps
        val chaptersArray = pageProps.optJSONArray("chapterlist") ?: pageProps.optJSONArray("chapters") ?: findChaptersArray(pageProps)

        val chapters = mutableListOf<MangaChapter>()
        chaptersArray?.let { arr ->
            for (i in 0 until arr.length()) {
                val ch = arr.getJSONObject(i)
                val chId = ch.optString("chapter_id", "").ifEmpty { ch.optString("id", "") }
                if (chId.isEmpty()) continue
                
                val rawTitle = ch.optString("chapter_title", "").ifEmpty { ch.optString("title", "") }.trim()
                val chNum = ch.optString("chapter_number", "").ifEmpty { ch.optString("number", "") }
                val finalTitle = when {
                    rawTitle.equals("null", ignoreCase = true) || rawTitle.isEmpty() -> "Chapter $chNum"
                    rawTitle.startsWith("Chapter", ignoreCase = true) -> rawTitle
                    else -> "Chapter $chNum - $rawTitle"
                }
                
                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = finalTitle,
                    url = "/read/$chId",
                    number = chNum.toFloatOrNull() ?: 0f,
                    uploadDate = parseDate(ch.optString("created_at", "").ifEmpty { ch.optString("date", "") }),
                    source = source,
                    scanlator = ch.optString("scanlator", ""),
                    branch = null,
                    volume = 0
                ))
            }
        }

        val descId = mangaObj.optString("desc_ID", "")
        val descObjId = mangaObj.optJSONObject("desc")?.optString("id", "") ?: ""
        val descFallback = mangaObj.optString("description", "")
        val description = if (descId.isNotEmpty()) descId else if (descObjId.isNotEmpty()) descObjId else descFallback

        val stateStr = mangaObj.optString("status", "")
        val state = if (stateStr.contains("completed", true) || stateStr.contains("tamat", true)) MangaState.FINISHED else MangaState.ONGOING

        val tags = mutableSetOf<MangaTag>()
        mangaObj.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tName = arr.optString(i, "")
                if (tName.isNotBlank()) tags.add(MangaTag(tName, tName, source))
            }
        }
        
        val authors = mutableSetOf<String>()
        listOf("author", "artist").forEach { key ->
            val value = mangaObj.opt(key)
            if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val a = value.optString(i, "").trim()
                    if (a.isNotBlank()) authors.add(a)
                }
            } else if (value is String && value.isNotBlank()) {
                if (value.startsWith("[") && value.endsWith("]")) {
                    try {
                        val arr = JSONArray(value)
                        for (i in 0 until arr.length()) {
                            val a = arr.optString(i, "").trim()
                            if (a.isNotBlank()) authors.add(a)
                        }
                    } catch (e: Exception) {
                        authors.add(value.trim())
                    }
                } else {
                    authors.add(value.trim())
                }
            }
        }

        var cover = mangaObj.optString("cover", "")
        if (cover.isNotEmpty()) {
            val fileName = cover.substringAfterLast("/")
            val id = manga.url.substringAfterLast("/")
            cover = "https://cdn.thrive.moe/covers/$id/$fileName.256.jpg"
        } else {
            cover = manga.coverUrl ?: ""
        }

        val title = mangaObj.optString("title", "")

        return manga.copy(
            title = if (title.isNotEmpty()) title else manga.title,
            description = description,
            state = state,
            authors = authors,
            tags = tags,
            coverUrl = cover,
            chapters = chapters.sortedBy { it.number } 
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}", headers).parseHtml()
        
        val imgElements = doc.select("div.w-auto.h-auto.flex > img, img.mx-auto.block")
        if (imgElements.isNotEmpty()) {
            return imgElements.mapNotNull { img ->
                val src = img.attr("src")
                if (src.isNotBlank() && src.startsWith("http")) MangaPage(generateUid(src), src, null, source) else null
            }
        }

        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val pageProps = JSONObject(scriptData).optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()
        
        val pagesObj = findPagesObject(pageProps) ?: pageProps
        val prefix = pagesObj.optString("prefix", "")
        val images = pagesObj.optJSONArray("image") ?: pagesObj.optJSONArray("images") ?: pagesObj.optJSONArray("data") ?: return emptyList()

        return (0 until images.length()).map { i ->
            val img = images.getString(i)
            val imageUrl = if (img.startsWith("http")) img else "https://cdn.thrive.moe/data/$prefix/$img"
            MangaPage(generateUid(imageUrl), imageUrl, null, source)
        }
    }

    private fun findMangaArray(obj: JSONObject): JSONArray? {
        for (key in obj.keys()) {
            val value = obj.opt(key)
            if (value is JSONArray && value.length() > 0) {
                val first = value.optJSONObject(0)
                if (first != null && first.has("id") && first.has("title")) return value
            } else if (value is JSONObject) {
                val nested = findMangaArray(value)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun findChaptersArray(obj: JSONObject): JSONArray? {
        for (key in obj.keys()) {
            val value = obj.opt(key)
            if (value is JSONArray && value.length() > 0) {
                val first = value.optJSONObject(0)
                if (first != null && (first.has("chapter_id") || first.has("chapter_number") || first.has("id"))) return value
            } else if (value is JSONObject) {
                val nested = findChaptersArray(value)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun findMangaObject(obj: JSONObject): JSONObject? {
        for (key in obj.keys()) {
            val value = obj.opt(key)
            if (value is JSONObject) {
                if (value.has("desc_ID") || value.has("description") || value.has("status") || value.has("title")) return value
                val nested = findMangaObject(value)
                if (nested != null) return nested
            }
        }
        return null
    }
    
    private fun findPagesObject(obj: JSONObject): JSONObject? {
        if ((obj.has("image") || obj.has("images") || obj.has("data")) && obj.has("prefix")) return obj
        for (key in obj.keys()) {
            val value = obj.opt(key)
            if (value is JSONObject) {
                val nested = findPagesObject(value)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            sdf.parse(dateStr.substringBefore("."))?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
