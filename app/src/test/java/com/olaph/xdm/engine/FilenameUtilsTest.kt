package com.olaph.xdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameUtilsTest {

    // --- Content-Disposition ---

    @Test fun quotedFilename() {
        assertEquals("report.pdf", FilenameUtils.fromContentDisposition("attachment; filename=\"report.pdf\""))
    }

    @Test fun unquotedFilename() {
        assertEquals("a.zip", FilenameUtils.fromContentDisposition("attachment; filename=a.zip"))
    }

    @Test fun semicolonInsideQuotes() {
        assertEquals("a;b.zip", FilenameUtils.fromContentDisposition("attachment; filename=\"a;b.zip\"; size=3"))
    }

    @Test fun escapedQuote() {
        assertEquals("he said \"hi\".txt", FilenameUtils.fromContentDisposition("attachment; filename=\"he said \\\"hi\\\".txt\""))
    }

    @Test fun rfc5987Utf8() {
        assertEquals(
            "€ rates.pdf",
            FilenameUtils.fromContentDisposition("attachment; filename*=UTF-8''%E2%82%AC%20rates.pdf"),
        )
    }

    @Test fun rfc5987WinsOverPlainFilename() {
        val h = "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"
        assertEquals("résumé.pdf", FilenameUtils.fromContentDisposition(h))
    }

    @Test fun rfc5987Latin1() {
        assertEquals("é.txt", FilenameUtils.fromContentDisposition("attachment; filename*=ISO-8859-1'en'%E9.txt"))
    }

    @Test fun rfc5987WithLanguageTagAndSpacesAroundEquals() {
        assertEquals("x y.bin", FilenameUtils.fromContentDisposition("attachment ; filename* = utf-8'de'x%20y.bin"))
    }

    @Test fun percentEncodedPlainFilename() {
        assertEquals("my file.zip", FilenameUtils.fromContentDisposition("attachment; filename=\"my%20file.zip\""))
    }

    @Test fun plusIsNotDecodedAsSpace() {
        assertEquals("a+b.zip", FilenameUtils.fromContentDisposition("attachment; filename=\"a+b.zip\""))
    }

    @Test fun noFilename() {
        assertNull(FilenameUtils.fromContentDisposition("inline"))
        assertNull(FilenameUtils.fromContentDisposition(null))
        assertNull(FilenameUtils.fromContentDisposition(""))
        assertNull(FilenameUtils.fromContentDisposition("attachment; filename=\"\""))
    }

    // --- URL ---

    @Test fun nameFromUrlPath() {
        assertEquals("file.zip", FilenameUtils.fromUrl("https://example.com/a/b/file.zip?token=1#x"))
    }

    @Test fun nameFromUrlIsDecoded() {
        assertEquals("my file (1).mp4", FilenameUtils.fromUrl("https://example.com/my%20file%20(1).mp4"))
    }

    @Test fun hostOnlyUrlHasNoName() {
        assertNull(FilenameUtils.fromUrl("https://example.com"))
        assertNull(FilenameUtils.fromUrl("https://example.com/"))
    }

    @Test fun trailingSlashUsesLastSegment() {
        assertEquals("dir", FilenameUtils.fromUrl("https://example.com/dir/"))
    }

    // --- resolve ---

    @Test fun resolvePrefersContentDisposition() {
        assertEquals(
            "real.zip",
            FilenameUtils.resolve("attachment; filename=real.zip", "https://x.org/download.php", "application/zip"),
        )
    }

    @Test fun resolveFallsBackToUrl() {
        assertEquals("movie.mkv", FilenameUtils.resolve(null, "https://x.org/v/movie.mkv", "video/x-matroska"))
    }

    @Test fun resolveFallsBackToMime() {
        assertEquals("download.pdf", FilenameUtils.resolve(null, "https://x.org/", "application/pdf"))
    }

    @Test fun resolveAddsExtensionFromMime() {
        assertEquals("get.zip", FilenameUtils.resolve(null, "https://x.org/get", "application/zip"))
    }

    @Test fun resolveWithNothing() {
        assertEquals("download", FilenameUtils.resolve(null, "https://x.org/", null))
        assertEquals("download", FilenameUtils.resolve(null, "https://x.org/", "application/octet-stream"))
    }

    // --- sanitize ---

    @Test fun sanitizeStripsPathTraversal() {
        assertEquals("passwd", FilenameUtils.sanitize("../../etc/passwd"))
        assertEquals("evil.exe", FilenameUtils.sanitize("..\\..\\evil.exe"))
    }

    @Test fun sanitizeReplacesIllegalCharacters() {
        assertEquals("a_b_c_d_e_f_g_h.txt", FilenameUtils.sanitize("a:b*c?d\"e<f>g|h.txt"))
        assertEquals("a_b.txt", FilenameUtils.sanitize("a\u0000b.txt"))
    }

    @Test fun sanitizeTrimsDotsAndSpaces() {
        assertEquals("name", FilenameUtils.sanitize("  ..name.. "))
        assertEquals("download", FilenameUtils.sanitize(".."))
        assertEquals("download", FilenameUtils.sanitize("   "))
        assertEquals("download", FilenameUtils.sanitize(""))
    }

    @Test fun sanitizeCollapsesWhitespace() {
        assertEquals("a b c.txt", FilenameUtils.sanitize("a   b \t c.txt"))
    }

    @Test fun sanitizeLimitsLengthKeepingExtension() {
        val n = FilenameUtils.sanitize("x".repeat(400) + ".mp4")
        assertTrue(n.length <= 180)
        assertTrue(n.endsWith(".mp4"))
    }

    @Test fun sanitizeKeepsUnicode() {
        assertEquals("日本語 🎵.mp3", FilenameUtils.sanitize("日本語 🎵.mp3"))
    }

    // --- collisions ---

    @Test fun uniqueNameNoCollision() {
        assertEquals("a.zip", FilenameUtils.uniqueName("a.zip") { false })
    }

    @Test fun uniqueNameAppendsCounter() {
        val taken = setOf("a.zip", "a (1).zip", "a (2).zip")
        assertEquals("a (3).zip", FilenameUtils.uniqueName("a.zip") { it in taken })
    }

    @Test fun uniqueNameWithoutExtension() {
        assertEquals("README (1)", FilenameUtils.uniqueName("README") { it == "README" })
    }

    @Test fun uniqueNameKeepsTarGz() {
        assertEquals("src (1).tar.gz", FilenameUtils.uniqueName("src.tar.gz") { it == "src.tar.gz" })
    }

    @Test fun uniqueNameDotInBaseName() {
        assertEquals("v1.2 final (1).zip", FilenameUtils.uniqueName("v1.2 final.zip") { it == "v1.2 final.zip" })
    }

    @Test fun splitExtensionIgnoresLeadingDotAndSentences() {
        assertEquals(".bashrc" to "", FilenameUtils.splitExtension(".bashrc"))
        assertEquals("Mr. Smith report" to "", FilenameUtils.splitExtension("Mr. Smith report"))
    }

    // --- automatic names for links whose own name says nothing ---

    private val now = java.time.LocalDateTime.of(2026, 11, 21, 14, 30, 5)

    @Test fun stockServerNamesAreGeneric() {
        for (n in listOf(
            "index.m3u8", "master.m3u8", "playlist.m3u8", "chunklist_w1234567.m3u8", "index_0_av.m3u8", "prog_index.m3u8",
            "videoplayback", "download", "video.mp4", "master-720p.m3u8", "1080p.mp4", "1234567890.mp4", "x.mp4",
            "a1b2c3d4e5f60718293a4b5c6d7e8f90.mp4", "3f2504e0-4f89-11d3-9a0c-0305e82c3301.ts",
        )) {
            assertTrue("$n is generic", FilenameUtils.isGenericName(n))
        }
    }

    @Test fun realNamesAreKept() {
        for (n in listOf(
            "ubuntu-24.04.3-desktop-amd64.iso", "Big Buck Bunny.mp4", "report.pdf", "IMG_20240101_123456.jpg",
            "Some Title (720p).mp4", "index of songs.mp3", "video tutorial kotlin.mp4", "app-release.apk",
        )) {
            assertFalse("$n is kept", FilenameUtils.isGenericName(n))
        }
    }

    @Test fun autoNameIsSiteThenDateAndTimeWithTheOriginalExtension() {
        assertEquals("x.com_20261121_143005.m3u8", FilenameUtils.meaningfulName("index.m3u8", "https://www.x.com/user/status/1", now))
        assertEquals("cdn.example.org_20261121_143005.mp4", FilenameUtils.meaningfulName("videoplayback.mp4", "https://cdn.example.org:8443/v?id=1", now))
        assertEquals("download_20261121_143005", FilenameUtils.meaningfulName("download", null, now))
        assertEquals("a real name stays", "Big Buck Bunny.mp4", FilenameUtils.meaningfulName("Big Buck Bunny.mp4", "https://x.com/", now))
    }

    @Test fun autoNamesDifferFromOneSecondToTheNext() {
        val a = FilenameUtils.meaningfulName("index.m3u8", "https://x.com/a", now)
        val b = FilenameUtils.meaningfulName("index.m3u8", "https://x.com/b", now.plusSeconds(1))
        assertTrue("$a != $b", a != b)
    }

    @Test fun hostOfHandlesOddUrls() {
        assertEquals("example.com", FilenameUtils.hostOf("https://user:pw@WWW.Example.com:8080/a?b#c"))
        assertNull(FilenameUtils.hostOf("not a url"))
        assertNull(FilenameUtils.hostOf(null))
    }
}
