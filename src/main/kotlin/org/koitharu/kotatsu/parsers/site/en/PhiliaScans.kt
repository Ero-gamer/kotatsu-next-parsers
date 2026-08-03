package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The site used to run on Madara/WordPress and was scraped as HTML. It now
 * serves everything from a JSON API under `/api`, and delivers chapter images
 * encrypted (and sometimes tile-scrambled) — see [intercept].
 */
@MangaSourceParser("PHILIASCANS", "Philia Scans", "en")
internal class PhiliaScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.PHILIASCANS, pageSize = 20), Interceptor {

	override val configKeyDomain = ConfigKey.Domain("philiascans.org")

	private val apiUrl get() = "https://$domain/api"

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_WEEK,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
	)

	private var tagsCache: Set<MangaTag>? = null

	private suspend fun fetchTags(): Set<MangaTag> {
		tagsCache?.let { return it }
		val array = webClient.httpGet("$apiUrl/genres").parseJsonArray()
		val tags = array.mapJSONNotNull { item ->
			val key = item.optString("slug").nullIfEmpty() ?: return@mapJSONNotNull null
			val title = item.optString("name").nullIfEmpty() ?: return@mapJSONNotNull null
			MangaTag(key = key, title = title, source = source)
		}.toSet()
		tagsCache = tags
		return tags
	}

	// ============================== List ==============================

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "$apiUrl/manga".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())
			addQueryParameter("perPage", pageSize.toString())
			filter.query?.nullIfEmpty()?.let { addQueryParameter("q", it) }

			// "Recently updated" is the API default and is selected by sending no
			// `orderby` at all, so only the other orders name a value.
			val (orderBy, direction) = when (order) {
				SortOrder.UPDATED -> null to "desc"
				SortOrder.POPULARITY -> "views" to "desc"
				SortOrder.POPULARITY_WEEK -> "trending" to "desc"
				SortOrder.RATING -> "rating" to "desc"
				SortOrder.NEWEST -> "added" to "desc"
				SortOrder.ALPHABETICAL -> "title" to "asc"
				SortOrder.ALPHABETICAL_DESC -> "title" to "desc"
				else -> null to "desc"
			}
			orderBy?.let { addQueryParameter("orderby", it) }
			addQueryParameter("order", direction)

			// Repeating a parameter widens the result set — the API treats
			// multiple values as OR, not AND.
			filter.tags.forEach { addQueryParameter("genres", it.key) }
			filter.states.forEach { state ->
				stateToApi(state)?.let { addQueryParameter("statuses", it) }
			}
			filter.types.forEach { type ->
				typeToApi(type)?.let { addQueryParameter("types", it) }
			}
		}.build()

		val items = webClient.httpGet(url).parseJson().optJSONArray("items") ?: return emptyList()
		return items.mapJSONNotNull { item ->
			val slug = item.optString("slug").nullIfEmpty() ?: return@mapJSONNotNull null
			Manga(
				id = generateUid(slug),
				url = "/series/$slug",
				publicUrl = "https://$domain/series/$slug",
				title = item.optString("title").nullIfEmpty() ?: return@mapJSONNotNull null,
				altTitles = emptySet(),
				// The API mixes absolute and root-relative media urls.
				coverUrl = item.optString("coverImageUrl").nullIfEmpty()?.toAbsoluteUrl(domain),
				largeCoverUrl = null,
				authors = emptySet(),
				description = null,
				tags = item.optJSONArray("genres")?.toTags().orEmpty(),
				state = parseState(item.optString("status")),
				rating = item.optString("ratingAvg").toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
				contentRating = parseContentRating(item.optString("contentRating")),
				source = source,
			)
		}
	}

	// ============================= Details =============================

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.slug
		val details = webClient.httpGet("$apiUrl/manga/$slug").parseJson()
		val chapters = webClient.httpGet("$apiUrl/manga/$slug/chapters").parseJson()
			.optJSONArray("items")
			.parseChapters(slug)

		return manga.copy(
			title = details.optString("title").nullIfEmpty() ?: manga.title,
			altTitles = details.optJSONArray("alternativeTitles")?.let { array ->
				(0 until array.length()).mapNotNullTo(LinkedHashSet()) { array.optString(it).nullIfEmpty() }
			}.orEmpty(),
			coverUrl = details.optString("coverImageUrl").nullIfEmpty()?.toAbsoluteUrl(domain)
				?: manga.coverUrl,
			description = details.optString("synopsis").nullIfEmpty(),
			tags = details.optJSONArray("genres")?.toTags().orEmpty(),
			authors = buildSet {
				addAll(details.optJSONArray("authors").toNames())
				addAll(details.optJSONArray("artists").toNames())
			},
			state = parseState(details.optString("status")),
			contentRating = parseContentRating(details.optString("contentRating")),
			chapters = chapters,
		)
	}

	private fun JSONArray?.parseChapters(mangaSlug: String): List<MangaChapter> {
		if (this == null) return emptyList()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		// The API lists newest first; Kotatsu wants oldest first.
		return mapJSONNotNull { item ->
			val slug = item.optString("slug").nullIfEmpty() ?: return@mapJSONNotNull null
			val number = item.optString("number")
			// A chapter that costs coins and has not been bought cannot be read.
			val isLocked = !item.optBoolean("purchased") && item.optInt("coinPrice") != 0
			val rawTitle = item.optString("title").nullIfEmpty()
				?.takeIf { it != "null" && it != number }
			val name = if (rawTitle != null) "Chapter $number - $rawTitle" else "Chapter $number"
			MangaChapter(
				id = generateUid("$mangaSlug/$slug"),
				title = if (isLocked) "🔒 $name" else name,
				number = number.toFloatOrNull() ?: 0f,
				volume = 0,
				url = "/series/$mangaSlug/$slug",
				scanlator = item.optJSONObject("team")?.optString("name")?.nullIfEmpty(),
				uploadDate = dateFormat.parseSafe(item.optString("publishedAt")),
				branch = null,
				source = source,
			)
		}.reversed()
	}

	// ============================== Pages ==============================

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val segments = chapter.url.trim('/').split('/')
		if (segments.size < 3) {
			throw ParseException("Unexpected chapter url: ${chapter.url}", chapter.url)
		}
		val mangaSlug = segments[1]
		val chapterSlug = segments[2]

		val viewer = webClient.httpGet("$apiUrl/manga/$mangaSlug/chapters/$chapterSlug").parseJson()
		if (!viewer.optBoolean("hasAccess", true)) {
			throw ParseException(
				"This chapter has to be unlocked with coins. Sign in through the browser and buy it first.",
				chapter.url,
			)
		}
		val chapterJson = viewer.optJSONObject("chapter")
			?: throw ParseException("No chapter data in response", chapter.url)
		val chapterId = chapterJson.optLong("id")
		val isScrambled = chapterJson.optBoolean("scrambled")

		// Every image is encrypted with a key that is only handed out to a
		// short-lived reader token, so fetch that first.
		val token = webClient.httpPost("$apiUrl/reader/access-token".toHttpUrl(), JSONObject(), readerHeaders())
			.parseJson().optString("token").nullIfEmpty()
			?: throw ParseException("Could not obtain a reader access token", chapter.url)
		val headers = readerHeaders().newBuilder()
			.add(HEADER_READER_TOKEN, token)
			.build()

		val keys = webClient.httpGet("$apiUrl/chapters/$chapterId/page-keys", headers).parseJson()
		val chapterKeyB64 = keys.optString("chapterKeyB64")
		val gridSize = keys.optInt("gridSize", 1)

		// When the server keeps the real key server-side it is split in two
		// halves that have to be XORed back together.
		var payloadA: String? = null
		var payloadB: String? = null
		if (keys.optBoolean("sessionDefault")) {
			val open = webClient.httpPost("$apiUrl/chapters/$chapterId/open".toHttpUrl(), JSONObject(), headers)
				.parseJson()
			payloadA = open.optString("payloadA").nullIfEmpty()
			val sessionId = open.optString("sessionId").nullIfEmpty()
			if (sessionId != null) {
				payloadB = runCatchingCancellable {
					webClient.httpGet("$apiUrl/chapters/$chapterId/get-drm?session=$sessionId", headers)
						.parseJson().optString("payloadB").nullIfEmpty()
				}.getOrNull()
			}
		}

		val pages = chapterJson.optJSONArray("pages") ?: return emptyList()
		return (0 until pages.length())
			.mapNotNull { pages.optJSONObject(it) }
			.sortedBy { it.optInt("position") }
			.mapIndexed { index, page ->
				val url = page.optString("url").toAbsoluteUrl(domain)
				// Everything the interceptor needs to decrypt this exact page,
				// carried in the fragment so it never reaches the server.
				val fragment = listOf(
					if (isScrambled) "1" else "0",
					page.optString("mime").nullIfEmpty() ?: "image/webp",
					chapterKeyB64,
					gridSize.toString(),
					payloadA.orEmpty(),
					payloadB.orEmpty(),
					index.toString(),
				).joinToString(";")
				MangaPage(
					id = generateUid(url),
					url = "$url#$fragment",
					preview = null,
					source = source,
				)
			}
	}

	// ========================= Image decryption =========================

	/**
	 * Chapter images are served encrypted. A one-byte-pair magic prefix picks
	 * the cipher; without it the payload uses the original HMAC keystream. The
	 * legacy keystream variant may additionally have its tiles shuffled, which
	 * only then has to be undone on the decoded bitmap.
	 */
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		val fragment = request.url.fragment
		if (fragment.isNullOrEmpty() || !response.isSuccessful) {
			return response
		}
		val parts = fragment.split(';')
		if (parts.size < 7) {
			return response
		}
		val isScrambled = parts[0] == "1"
		val mimeType = parts[1]
		val gridSize = parts[3].toIntOrNull() ?: return response
		val pageIndex = parts[6].toIntOrNull() ?: return response
		val chapterKey = resolveChapterKey(parts[2], parts[4], parts[5]) ?: return response

		val body = response.body ?: return response
		val raw = body.bytes()
		if (raw.size < 6) {
			return response
		}

		val scheme = when {
			raw[0] == MAGIC_HIGH && raw[1] == MAGIC_AES -> "aesctr:"
			raw[0] == MAGIC_HIGH && raw[1] == MAGIC_CHACHA -> "chacha"
			raw[0] == MAGIC_HIGH && raw[1] == MAGIC_AES4 -> "aesctr4:"
			else -> null
		}
		val offset = if (scheme != null) 2 else 0
		val header = ByteBuffer.wrap(raw, offset, 4).order(ByteOrder.BIG_ENDIAN)
		val originalWidth = header.short.toInt() and 0xFFFF
		val originalHeight = header.short.toInt() and 0xFFFF
		val payload = raw.copyOfRange(offset + 4, raw.size)

		val plain = when (scheme) {
			"aesctr:", "aesctr4:" -> aesCtrDecrypt(payload, chapterKey, pageIndex, scheme)
			"chacha" -> chaCha20(payload, hmacSha256(chapterKey, "cc:$pageIndex"))
			else -> xorKeystream(payload, chapterKey, pageIndex)
		}

		val contentType = mimeType.toMediaTypeOrNull() ?: body.contentType()
		val decrypted = response.newBuilder()
			.body(plain.toResponseBody(contentType))
			.build()

		// Only the legacy keystream format ever shuffles tiles; the newer
		// ciphers deliver the image already in order.
		if (!isScrambled || scheme != null || gridSize < 2) {
			return decrypted
		}
		return context.redrawImageResponse(decrypted) { bitmap ->
			unscramble(bitmap, chapterKey, pageIndex, gridSize, originalWidth, originalHeight)
		}
	}

	private fun resolveChapterKey(chapterKeyB64: String, payloadA: String, payloadB: String): ByteArray? {
		if (payloadA.isNotBlank() && payloadB.isNotBlank()) {
			val a = runCatching { context.decodeBase64(payloadA) }.getOrNull()
			val b = runCatching { context.decodeBase64(payloadB) }.getOrNull()
			if (a != null && b != null && a.size >= 32 && b.size >= 32) {
				return ByteArray(32) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
			}
		}
		return runCatching { context.decodeBase64(chapterKeyB64) }.getOrNull()?.takeIf { it.isNotEmpty() }
	}

	private fun hmacSha256(key: ByteArray, message: String): ByteArray {
		val mac = Mac.getInstance("HmacSHA256")
		mac.init(SecretKeySpec(key, "HmacSHA256"))
		return mac.doFinal(message.toByteArray(Charsets.UTF_8))
	}

	private fun aesCtrDecrypt(data: ByteArray, chapterKey: ByteArray, pageIndex: Int, prefix: String): ByteArray {
		val derived = hmacSha256(chapterKey, "$prefix$pageIndex")
		val cipher = Cipher.getInstance("AES/CTR/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), IvParameterSpec(ByteArray(16)))
		return cipher.doFinal(data)
	}

	/** XORs the payload with HMAC blocks keyed on the page and block index. */
	private fun xorKeystream(data: ByteArray, chapterKey: ByteArray, pageIndex: Int): ByteArray {
		val result = data.copyOf()
		var blockIndex = 0
		var blockOffset = 0
		var block = hmacSha256(chapterKey, "page:$pageIndex:$blockIndex")
		for (i in result.indices) {
			if (blockOffset == block.size) {
				blockIndex++
				block = hmacSha256(chapterKey, "page:$pageIndex:$blockIndex")
				blockOffset = 0
			}
			result[i] = (result[i].toInt() xor block[blockOffset].toInt()).toByte()
			blockOffset++
		}
		return result
	}

	private fun chaCha20(data: ByteArray, key: ByteArray): ByteArray {
		val result = data.copyOf()
		val nonce = ByteArray(12)
		var counter = 0
		var block = chaCha20Block(key, nonce, counter)
		var offset = 0
		for (i in result.indices) {
			if (offset == block.size) {
				counter++
				block = chaCha20Block(key, nonce, counter)
				offset = 0
			}
			result[i] = (result[i].toInt() xor block[offset].toInt()).toByte()
			offset++
		}
		return result
	}

	private fun chaCha20Block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
		val state = IntArray(16)
		state[0] = 0x61707865
		state[1] = 0x3320646e
		state[2] = 0x79622d32
		state[3] = 0x6b206574
		for (i in 0 until 8) {
			state[4 + i] = key.readIntLe(i * 4)
		}
		state[12] = counter
		state[13] = nonce.readIntLe(0)
		state[14] = nonce.readIntLe(4)
		state[15] = nonce.readIntLe(8)

		val working = state.copyOf()
		repeat(10) {
			working.quarterRound(0, 4, 8, 12)
			working.quarterRound(1, 5, 9, 13)
			working.quarterRound(2, 6, 10, 14)
			working.quarterRound(3, 7, 11, 15)
			working.quarterRound(0, 5, 10, 15)
			working.quarterRound(1, 6, 11, 12)
			working.quarterRound(2, 7, 8, 13)
			working.quarterRound(3, 4, 9, 14)
		}

		val block = ByteArray(64)
		for (i in 0 until 16) {
			block.writeIntLe(i * 4, working[i] + state[i])
		}
		return block
	}

	private fun IntArray.quarterRound(a: Int, b: Int, c: Int, d: Int) {
		this[a] += this[b]
		this[d] = Integer.rotateLeft(this[d] xor this[a], 16)
		this[c] += this[d]
		this[b] = Integer.rotateLeft(this[b] xor this[c], 12)
		this[a] += this[b]
		this[d] = Integer.rotateLeft(this[d] xor this[a], 8)
		this[c] += this[d]
		this[b] = Integer.rotateLeft(this[b] xor this[c], 7)
	}

	private fun ByteArray.readIntLe(offset: Int): Int = (this[offset].toInt() and 0xFF) or
		((this[offset + 1].toInt() and 0xFF) shl 8) or
		((this[offset + 2].toInt() and 0xFF) shl 16) or
		((this[offset + 3].toInt() and 0xFF) shl 24)

	private fun ByteArray.writeIntLe(offset: Int, value: Int) {
		this[offset] = value.toByte()
		this[offset + 1] = (value ushr 8).toByte()
		this[offset + 2] = (value ushr 16).toByte()
		this[offset + 3] = (value ushr 24).toByte()
	}

	/**
	 * Rebuilds the tile order. The shuffle is a Fisher-Yates driven by 32-bit
	 * words taken from HMAC blocks, so replaying it gives the mapping that has
	 * to be inverted.
	 */
	private fun unscramble(
		source: Bitmap,
		chapterKey: ByteArray,
		pageIndex: Int,
		gridSize: Int,
		originalWidth: Int,
		originalHeight: Int,
	): Bitmap {
		val tileCount = gridSize * gridSize
		val order = IntArray(tileCount) { it }
		val tilesSignature = hmacSha256(chapterKey, "tiles:$pageIndex")

		var counter = 0
		var wordIndex = 8
		var randomBlock = ByteArray(0)
		fun nextRandom(): Long {
			if (wordIndex >= 8) {
				randomBlock = hmacSha256(tilesSignature, "perm:${counter++}")
				wordIndex = 0
			}
			val value = ByteBuffer.wrap(randomBlock).order(ByteOrder.LITTLE_ENDIAN).getInt(wordIndex * 4)
			wordIndex++
			return value.toLong() and 0xFFFFFFFFL
		}
		for (i in tileCount - 1 downTo 1) {
			val j = (nextRandom() % (i + 1)).toInt()
			val tmp = order[i]
			order[i] = order[j]
			order[j] = tmp
		}

		val inverse = IntArray(tileCount)
		for (i in 0 until tileCount) {
			inverse[order[i]] = i
		}

		val tileWidth = source.width / gridSize
		val tileHeight = source.height / gridSize
		val width = if (originalWidth > 0) originalWidth else source.width
		val height = if (originalHeight > 0) originalHeight else source.height
		val output = context.createBitmap(width, height)
		for (target in 0 until tileCount) {
			val from = inverse[target]
			val srcX = (from % gridSize) * tileWidth
			val srcY = (from / gridSize) * tileHeight
			val dstX = (target % gridSize) * tileWidth
			val dstY = (target / gridSize) * tileHeight
			output.drawBitmap(
				source,
				Rect(srcX, srcY, srcX + tileWidth, srcY + tileHeight),
				Rect(dstX, dstY, dstX + tileWidth, dstY + tileHeight),
			)
		}
		return output
	}

	// ============================= Utilities =============================

	private val Manga.slug: String get() = url.trim('/').substringAfterLast('/')

	private fun readerHeaders() = getRequestHeaders().newBuilder()
		.set("Accept", "application/json")
		.set("Referer", "https://$domain/")
		.set("X-Requested-With", "XMLHttpRequest")
		.build()

	private fun JSONArray.toTags(): Set<MangaTag> = mapJSONNotNull { item ->
		val key = item.optString("slug").nullIfEmpty() ?: return@mapJSONNotNull null
		val title = item.optString("name").nullIfEmpty() ?: return@mapJSONNotNull null
		MangaTag(key = key, title = title, source = source)
	}.toSet()

	private fun JSONArray?.toNames(): Set<String> {
		if (this == null) return emptySet()
		return mapJSONNotNull { it.optString("name").nullIfEmpty() }.toSet()
	}

	private fun parseState(value: String?): MangaState? = when (value?.uppercase(Locale.ROOT)) {
		"ON_GOING", "ONGOING", "RELEASING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"ON_HOLD", "HIATUS" -> MangaState.PAUSED
		"CANCELED", "CANCELLED", "DROPPED" -> MangaState.ABANDONED
		else -> null
	}

	private fun stateToApi(state: MangaState): String? = when (state) {
		MangaState.ONGOING -> "on_going"
		MangaState.FINISHED -> "completed"
		MangaState.PAUSED -> "on_hold"
		MangaState.ABANDONED -> "canceled"
		else -> null
	}

	private fun typeToApi(type: ContentType): String? = when (type) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		ContentType.COMICS -> "comic"
		else -> null
	}

	private fun parseContentRating(value: String?): ContentRating? = when (value?.lowercase(Locale.ROOT)) {
		"safe" -> ContentRating.SAFE
		"suggestive" -> ContentRating.SUGGESTIVE
		"adult", "erotica", "pornographic" -> ContentRating.ADULT
		else -> null
	}

	private companion object {
		private const val HEADER_READER_TOKEN = "X-Reader-Access-Token"

		// Scheme markers on the encrypted payload: 0xFF02 AES-CTR,
		// 0xFF03 ChaCha20, 0xFF04 AES-CTR with a separate key derivation.
		private const val MAGIC_HIGH = 0xFF.toByte()
		private const val MAGIC_AES = 0x02.toByte()
		private const val MAGIC_CHACHA = 0x03.toByte()
		private const val MAGIC_AES4 = 0x04.toByte()
	}
}
