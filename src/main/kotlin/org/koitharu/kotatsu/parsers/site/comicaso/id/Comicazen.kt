package org.koitharu.kotatsu.parsers.site.comicaso.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.comicaso.ComicasoParser

@MangaSourceParser("COMICAZEN", "Comicazen", "id")
internal class Comicazen(context: MangaLoaderContext) :
	ComicasoParser(context, MangaParserSource.COMICAZEN, "comicazen.com", pageSize = 16) {

	override suspend fun getDetails(manga: Manga): Manga {
		val details = super.getDetails(manga)
		return details.copy(chapters = details.chapters?.reversed())
	}
}
