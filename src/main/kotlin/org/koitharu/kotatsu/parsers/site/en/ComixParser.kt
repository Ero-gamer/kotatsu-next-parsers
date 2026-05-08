package org.koitharu.kotatsu.parsers.site.en

import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.net.URLEncoder
import java.util.*

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
        listOf(
            SortOrder.RELEVANCE,
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL
        )
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return setOf(
            // Genres
            MangaTag(key = "6", title = "Action", source = source),
            MangaTag(key = "7", title = "Adventure", source = source),
            MangaTag(key = "8", title = "Boys Love", source = source),
            MangaTag(key = "9", title = "Comedy", source = source),
            MangaTag(key = "10", title = "Crime", source = source),
            MangaTag(key = "11", title = "Drama", source = source),
            MangaTag(key = "12", title = "Fantasy", source = source),
            MangaTag(key = "13", title = "Girls Love", source = source),
            MangaTag(key = "14", title = "Historical", source = source),
            MangaTag(key = "15", title = "Horror", source = source),
            MangaTag(key = "16", title = "Isekai", source = source),
            MangaTag(key = "17", title = "Magical Girls", source = source),
            MangaTag(key = "87267", title = "Mature", source = source),
            MangaTag(key = "18", title = "Mecha", source = source),
            MangaTag(key = "19", title = "Medical", source = source),
            MangaTag(key = "20", title = "Mystery", source = source),
            MangaTag(key = "21", title = "Philosophical", source = source),
            MangaTag(key = "22", title = "Psychological", source = source),
            MangaTag(key = "23", title = "Romance", source = source),
            MangaTag(key = "24", title = "Sci-Fi", source = source),
            MangaTag(key = "25", title = "Slice of Life", source = source),
            MangaTag(key = "26", title = "Sports", source = source),
            MangaTag(key = "27", title = "Superhero", source = source),
            MangaTag(key = "28", title = "Thriller", source = source),
            MangaTag(key = "29", title = "Tragedy", source = source),
            MangaTag(key = "30", title = "Wuxia", source = source),
            // Themes
            MangaTag(key = "31", title = "Aliens", source = source),
            MangaTag(key = "32", title = "Animals", source = source),
            MangaTag(key = "33", title = "Cooking", source = source),
            MangaTag(key = "34", title = "Crossdressing", source = source),
            MangaTag(key = "35", title = "Delinquents", source = source),
            MangaTag(key = "36", title = "Demons", source = source),
            MangaTag(key = "37", title = "Genderswap", source = source),
            MangaTag(key = "38", title = "Ghosts", source = source),
            MangaTag(key = "39", title = "Gyaru", source = source),
            MangaTag(key = "40", title = "Harem", source = source),
            MangaTag(key = "41", title = "Incest", source = source),
            MangaTag(key = "42", title = "Loli", source = source),
            MangaTag(key = "43", title = "Mafia", source = source),
            MangaTag(key = "44", title = "Magic", source = source),
            MangaTag(key = "45", title = "Martial Arts", source = source),
            MangaTag(key = "46", title = "Military", source = source),
            MangaTag(key = "47", title = "Monster Girls", source = source),
            MangaTag(key = "48", title = "Monsters", source = source),
            MangaTag(key = "49", title = "Music", source = source),
            MangaTag(key = "50", title = "Ninja", source = source),
            MangaTag(key = "51", title = "Office Workers", source = source),
            MangaTag(key = "52", title = "Police", source = source),
            MangaTag(key = "53", title = "Post-Apocalyptic", source = source),
            MangaTag(key = "54", title = "Reincarnation", source = source),
            MangaTag(key = "55", title = "Reverse Harem", source = source),
            MangaTag(key = "56", title = "Samurai", source = source),
            MangaTag(key = "57", title = "School Life", source = source),
            MangaTag(key = "58", title = "Shota", source = source),
            MangaTag(key = "59", title = "Supernatural", source = source),
            MangaTag(key = "60", title = "Survival", source = source),
            MangaTag(key = "61", title = "Time Travel", source = source),
            MangaTag(key = "62", title = "Traditional Games", source = source),
            MangaTag(key = "63", title = "Vampires", source = source),
            MangaTag(key = "64", title = "Video Games", source = source),
            MangaTag(key = "65", title = "Villainess", source = source),
            MangaTag(key = "66", title = "Virtual Reality", source = source),
            MangaTag(key = "67", title = "Zombies", source = source),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append(apiUrl("manga"))
            append("?")
            var firstParam = true
            fun addParam(param: String) {
                if (firstParam) {
                    append(param)
                    firstParam = false
                } else {
                    append("&").append(param)
                }
            }

            // Search keyword if provided
            if (!filter.query.isNullOrEmpty()) {
                addParam("keyword=${filter.query.urlEncoded()}")
            }

            // Use the provided sort order directly
            when (order) {
                SortOrder.RELEVANCE -> addParam("order[relevance]=desc")
                SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
                SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
                SortOrder.NEWEST -> addParam("order[created_at]=desc")
                SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
                else -> addParam("order[chapter_updated_at]=desc")
            }

            // Handle genre filtering
            if (filter.tags.isNotEmpty()) {
                for (tag in filter.tags) {
                    addParam("genres_in[]=${tag.key}")
                }
            }

            // Default exclude adult content
            addParam("genres_ex[]=87264") // Adult
            addParam("genres_ex[]=87266") // Hentai
            addParam("genres_ex[]=87268") // Smut
            addParam("genres_ex[]=87265") // Ecchi
            addParam("limit=$pageSize")
            addParam("page=$page")
        }

        val response = webClient.httpGet(url).parseJson()
        val result = response.getJSONObject("result")
        val items = result.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseMangaFromJson(item)
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.optString("hid").ifBlank { json.optString("hash_id") }
        val title = json.getString("title")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.optJSONObject("poster")
        val coverUrl = poster?.optString("large", "")?.nullIfEmpty()
            ?: poster?.optString("medium", "")?.nullIfEmpty()
            ?: poster?.optString("small", "")?.nullIfEmpty()
        val status = json.optString("status", "")
        val rating = json.optDouble("ratedAvg", Double.NaN)
            .takeUnless { it.isNaN() }
            ?: json.optDouble("rated_avg", 0.0)

        val state = when (status) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            "discontinued" -> MangaState.ABANDONED
            else -> null
        }

        return Manga(
            id = generateUid(hashId),
            url = "/title/$hashId",
            publicUrl = "https://comix.to/title/$hashId",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = if (rating > 0) (rating / 10.0).toFloat() else RATING_UNKNOWN,
            tags = parseTerms(json),
            authors = parseAuthors(json),
            state = state,
            source = source,
            contentRating = if (json.optString("contentRating") in NSFW_RATINGS) ContentRating.ADULT else ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val hashId = manga.url.substringAfter("/title/")
        val chaptersDeferred = async { getChapters(manga) }

        // Get detailed manga info
        val detailUrl = apiUrl("manga/$hashId")
        val response = webClient.httpGet(detailUrl).parseJson()

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedManga = parseMangaFromJson(result)

            return@coroutineScope updatedManga.copy(
                chapters = chaptersDeferred.await(),
            )
        }

        return@coroutineScope manga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val path = "/chapters/$chapterId"
        val chapterUrl = "${apiUrl(path)}?_=${ComixHash.generateHash(path)}"
        val response = webClient.httpGet(chapterUrl).parseJson()
        val pages = response.optJSONObject("result")?.optJSONArray("pages") ?: JSONArray()

        return (0 until pages.length()).map { i ->
            val imageUrl = pages.getJSONObject(i).getString("url")
            MangaPage(
                id = generateUid("$chapterId-$i"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun getChapters(manga: Manga): List<MangaChapter> {
        val hashId = manga.url.substringAfter("/title/")
        val allChapters = mutableListOf<JSONObject>()
        var page = 1

        // Fetch all chapters with pagination
        while (true) {
            val chaptersPath = "/manga/$hashId/chapters"
            val hashToken = ComixHash.generateHash(chaptersPath)
            val chaptersUrl = "${apiUrl(chaptersPath)}?order[number]=desc&limit=100&page=$page&_=$hashToken"
            val response = webClient.httpGet(chaptersUrl).parseJson()
            val result = response.getJSONObject("result")
            val items = result.getJSONArray("items")

            if (items.length() == 0) break

            for (i in 0 until items.length()) {
                allChapters.add(items.getJSONObject(i))
            }

            // Check pagination info to see if we have more pages
            val pagination = result.optJSONObject("pagination") ?: result.optJSONObject("meta")
            val currentPage = pagination?.optIntOrNull("page")
                ?: pagination?.optIntOrNull("current_page")
                ?: page
            val lastPage = pagination?.optIntOrNull("lastPage")
                ?: pagination?.optIntOrNull("last_page")
                ?: 1
            if (currentPage >= lastPage) break

            page++
        }

        // Group chapters by scanlation team
        val chaptersByTeam = mutableMapOf<String, MutableList<JSONObject>>()
        for (chapter in allChapters) {
            val scanlationGroup = chapter.optJSONObject("group") ?: chapter.optJSONObject("scanlation_group")
            val teamName = scanlationGroup?.optString("name", null) ?: "Unknown"
            chaptersByTeam.getOrPut(teamName) { mutableListOf() }.add(chapter)
        }

        // Get all unique chapter numbers
        val allChapterNumbers = allChapters.map { it.getDouble("number").toFloat() }.toSet()

        // Build chapters with branches - each team gets complete chapter list with gaps filled
        val chaptersBuilder = ChaptersListBuilder(allChapters.size * chaptersByTeam.size)

        for ((teamName, teamChapters) in chaptersByTeam) {
            // Map of chapter numbers this team has
            val teamChapterMap = teamChapters.associateBy { it.getDouble("number").toFloat() }

            // For each chapter number, use team's version if available, otherwise find best alternative
            for (chapterNumber in allChapterNumbers) {
                val chapterData = teamChapterMap[chapterNumber]
                    ?: allChapters.find { it.getDouble("number").toFloat() == chapterNumber }
                    ?: continue

                val chapterId = chapterData.getLong("id")
                val number = chapterData.getDouble("number").toFloat()
                val name = chapterData.optString("name", "").nullIfEmpty()
                val scanlationGroup = chapterData.optJSONObject("group") ?: chapterData.optJSONObject("scanlation_group")
                val actualTeamName = scanlationGroup?.optString("name", null)
                    ?: if (chapterData.optBoolean("isOfficial")) "Official" else "Unknown"

                val title = if (name != null) {
                    "Chapter $number: $name"
                } else {
                    "Chapter $number"
                }

                val chapter = MangaChapter(
                    id = generateUid("$teamName-$chapterId"),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/title/$hashId/$chapterId-chapter-${number.toInt()}",
                    uploadDate = parseRelativeDate(chapterData.optString("createdAtFormatted")),
                    source = source,
                    scanlator = actualTeamName,
                    branch = teamName,
                )

                chaptersBuilder.add(chapter)
            }
        }

        return chaptersBuilder.toList().reversed()
    }

    private fun apiUrl(path: String): String = "https://$domain/api/v1/${path.removePrefix("/")}"

    private fun parseTerms(json: JSONObject): Set<MangaTag> {
        val tags = LinkedHashSet<MangaTag>()
        for (key in TERM_KEYS) {
            tags += parseTerms(json.optJSONArray(key))
        }
        return tags
    }

    private fun parseTerms(array: JSONArray?): Set<MangaTag> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNullTo(LinkedHashSet()) { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNullTo null
            val title = item.optString("title").nullIfEmpty()
                ?: item.optString("name").nullIfEmpty()
                ?: return@mapNotNullTo null
            MangaTag(
                key = title,
                title = title,
                source = source,
            )
        }
    }

    private fun parseAuthors(json: JSONObject): Set<String> {
        val authors = json.optJSONArray("authors") ?: json.optJSONArray("author") ?: return emptySet()
        return (0 until authors.length()).mapNotNullTo(LinkedHashSet()) { i ->
            val item = authors.optJSONObject(i) ?: return@mapNotNullTo null
            item.optString("title").nullIfEmpty() ?: item.optString("name").nullIfEmpty()
        }
    }

    private fun parseRelativeDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val match = RELATIVE_DATE_REGEX.find(date.trim().lowercase().removeSuffix(" ago")) ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val calendar = Calendar.getInstance()
        when (match.groupValues[2]) {
            "s", "sec", "secs" -> calendar.add(Calendar.SECOND, -amount)
            "m", "min", "mins" -> calendar.add(Calendar.MINUTE, -amount)
            "h", "hr", "hrs" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
            "d", "day", "days" -> calendar.add(Calendar.DAY_OF_YEAR, -amount)
            "w", "week", "weeks" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            "mo", "mos", "month", "months" -> calendar.add(Calendar.MONTH, -amount)
            "y", "yr", "yrs", "year", "years" -> calendar.add(Calendar.YEAR, -amount)
        }
        return calendar.timeInMillis
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key) else null
    }

    private companion object {
        private val NSFW_RATINGS = setOf("erotica", "pornographic")
        private val TERM_KEYS = arrayOf("genres", "genre", "tags", "theme", "demographics", "demographic", "formats")
        private val RELATIVE_DATE_REGEX = Regex("""^(\d+)\s*(s|m|h|d|w|mo|mos|y|yr|yrs|min|mins|sec|secs|hr|hrs|day|days|week|weeks|month|months|year|years)$""")
    }
}

private object ComixHash {
    private val KEYS = arrayOf(
        "JxTcdyiA5GZxnbrmthXBQfU2IMTKcY1+3nNhbq98Sgo=",
        "3PordjODbhqla382Cxapmo/1JiABJQcjiJj1+48gTJ4=",
        "OaKvnI5ARA==",
        "MHNBHYWA7lvy867fXgvGcJwWDk79KqUJUVFsh3RwnnI=",
        "8i0Cru/VJBSVB2Y1GcMDVpzx2WepOcfnWdd81yxICl4=",
        "Fyskubz8VvA=",
        "B46L1x+UeWP+19cRpQ+OZvdLAK9EHID8g3mSgn57tew=",
        "DTSTmUt6LpDUw9r1lSQqyb3YlFTzruT8tk8wUGkwehQ=",
        "vY/meeI=",
        "7xWfIF5THL5LAnRgAARg+4mjWHPU9n3PQwvzbaMNi+Q=",
        "bewtiTuV+HJk56xxkf2iCljLgruCpBmN9BgE8i6gc9M=",
        "/Xcb2zAu8AU=",
        "WgeCQ3T8R51uTwVSiVa7Zy0dN6JOg6Z5JleMS+HV8Aw=",
        "yXayUVFrrcW56jQCEfZzuCidjpnWKjTDUNT7XeX9i7k=",
        "tSLco2w=",
    )

    private fun getKeyBytes(index: Int): IntArray {
        val b64 = KEYS.getOrNull(index) ?: return IntArray(0)
        return try {
            Base64.getDecoder().decode(b64)
                .map { (it.toInt() and 0xFF) }
                .toIntArray()
        } catch (_: Exception) {
            IntArray(0)
        }
    }

    private fun rc4(key: IntArray, data: IntArray): IntArray {
        if (key.isEmpty()) return data
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.size]) % 256
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }
        var i = 0
        j = 0
        val out = IntArray(data.size)
        for (k in data.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
            out[k] = data[k] xor s[(s[i] + s[j]) % 256]
        }
        return out
    }

    private fun getMutKey(mk: IntArray, idx: Int): Int =
        if (mk.isNotEmpty() && (idx % 32) < mk.size) mk[idx % 32] else 0

    private fun opShiftRight7Left1(e: Int): Int = ((e ushr 7) or (e shl 1)) and 255
    private fun opShiftLeft1Right7(e: Int): Int = ((e shl 1) or (e ushr 7)) and 255
    private fun opShiftRight2Left6(e: Int): Int = ((e ushr 2) or (e shl 6)) and 255
    private fun opShiftLeft4Right4(e: Int): Int = ((e shl 4) or (e ushr 4)) and 255
    private fun opShiftRight4Left4(e: Int): Int = ((e ushr 4) or (e shl 4)) and 255

    private fun mutate(data: IntArray, mutKey: IntArray, prefKey: IntArray, prefKeyLimit: Int, round: Int): IntArray {
        val out = mutableListOf<Int>()
        for (i in data.indices) {
            if (i < prefKeyLimit && i < prefKey.size) out.add(prefKey[i])
            var v = data[i] xor getMutKey(mutKey, i)
            v = when (round) {
                1 -> when (i % 10) {
                    0 -> opShiftRight7Left1(v)
                    1 -> v xor 37
                    2 -> v xor 81
                    3 -> v xor 147
                    4 -> opShiftRight2Left6(v)
                    5, 8 -> opShiftRight4Left4(v)
                    6 -> v xor 218
                    7 -> (v + 159) and 255
                    9 -> v xor 180
                    else -> v
                }
                2 -> when (i % 10) {
                    0, 9 -> v xor 180
                    1 -> opShiftLeft1Right7(v)
                    2 -> v xor 147
                    3 -> opShiftRight7Left1(v)
                    4 -> opShiftRight2Left6(v)
                    5 -> opShiftRight4Left4(v)
                    6, 8 -> (v + 159) and 255
                    7 -> (v + 34) and 255
                    else -> v
                }
                3 -> when (i % 10) {
                    0 -> v xor 81
                    1 -> opShiftRight4Left4(v)
                    2, 9 -> opShiftLeft4Right4(v)
                    3 -> v xor 37
                    4 -> (v + 159) and 255
                    5 -> opShiftLeft1Right7(v)
                    6 -> v xor 180
                    7 -> (v + 34) and 255
                    8 -> opShiftRight2Left6(v)
                    else -> v
                }
                4 -> when (i % 10) {
                    0, 7 -> v xor 218
                    1, 4 -> opShiftLeft1Right7(v)
                    2 -> opShiftRight7Left1(v)
                    3 -> (v + 159) and 255
                    5, 8 -> v xor 180
                    6 -> v xor 147
                    9 -> v xor 37
                    else -> v
                }
                5 -> when (i % 10) {
                    0 -> opShiftLeft4Right4(v)
                    1, 3 -> v xor 147
                    2 -> (v + 34) and 255
                    4, 9 -> v xor 218
                    5, 7 -> opShiftLeft1Right7(v)
                    6 -> v xor 180
                    8 -> opShiftRight2Left6(v)
                    else -> v
                }
                else -> v
            }
            out.add(v and 255)
        }
        return out.toIntArray()
    }

    private fun round1(data: IntArray): IntArray {
        val mut = mutate(data, getKeyBytes(1), getKeyBytes(2), 7, 1)
        return rc4(getKeyBytes(0), mut)
    }

    private fun round2(data: IntArray): IntArray {
        val mut = mutate(data, getKeyBytes(4), getKeyBytes(5), 8, 2)
        return rc4(getKeyBytes(3), mut)
    }

    private fun round3(data: IntArray): IntArray {
        val mut = mutate(data, getKeyBytes(7), getKeyBytes(8), 5, 3)
        return rc4(getKeyBytes(6), mut)
    }

    private fun round4(data: IntArray): IntArray {
        val mut = mutate(data, getKeyBytes(10), getKeyBytes(11), 8, 4)
        return rc4(getKeyBytes(9), mut)
    }

    private fun round5(data: IntArray): IntArray {
        val mut = mutate(data, getKeyBytes(13), getKeyBytes(14), 5, 5)
        return rc4(getKeyBytes(12), mut)
    }

    fun generateHash(path: String): String {
        val encoded = URLEncoder.encode(path, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

        val initialBytes = encoded.toByteArray(Charsets.US_ASCII)
            .map { it.toInt() and 0xFF }
            .toIntArray()

        val r1 = round1(initialBytes)
        val r2 = round2(r1)
        val r3 = round3(r2)
        val r4 = round4(r3)
        val r5 = round5(r4)

        val finalBytes = ByteArray(r5.size) { r5[it].toByte() }

        return Base64.getUrlEncoder().withoutPadding().encodeToString(finalBytes)
    }
}
