package org.koitharu.kotatsu.parsers.site.mangareader.en

import okhttp3.HttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.LinkResolver
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty

@MangaSourceParser("RAVENSCANS", "RavenScans", "en")
internal class RavenScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.RAVENSCANS, "ravenscans.org", pageSize = 10, searchPageSize = 10) {
	// The site lists and links titles under /series; /manga is a 404 here.
	override val listUrl = "/series"
	override val datePattern = "MMM d, yyyy"
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	// The base implementation hardcodes /manga/, which would resolve every
	// shared link to a 404 here — and to an id that never matches the one the
	// listing produced from the site's own href.
	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val mangaSlug = link.pathSegments.getOrNull(1)?.nullIfEmpty() ?: return null
		val url = "$listUrl/$mangaSlug/"
		return resolver.resolveManga(this, url, generateUid(url))
	}
}
