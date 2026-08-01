package org.koitharu.kotatsu.parsers.site.ru

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.test_util.mangaOf

internal class DesuMeParserTest {

    @Test
    fun migrateLegacyApiMangaUrl() = runTest {
        val parser = MangaLoaderContextMock.newParserInstance(MangaParserSource.DESUME)
        val legacyManga = mangaOf(MangaParserSource.DESUME, "https://desu.uno/manga/api/2602").copy(
            id = parser.generateUid(2602L),
        )

        val details = parser.getDetails(legacyManga)

        assertEquals(legacyManga.id, details.id)
        assertFalse("/manga/api/" in details.url)
        assertTrue(details.url.endsWith(".2602/"))
        assertTrue(details.publicUrl.endsWith(".2602/"))
        assertFalse(details.chapters.isNullOrEmpty())
    }
}
