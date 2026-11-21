package com.olaph.xdm.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCollectorTest {

    private val page = "https://site.example.com/watch/1"

    private fun file(url: String, size: Long = 5_000_000) = MediaAnalysis.Found(
        MediaKind.FILE, "MP4", listOf(MediaOption(url = url, label = "MP4", sizeBytes = size)),
    )

    private fun hls(master: String, vararg variants: String) = MediaAnalysis.Found(
        MediaKind.HLS, "HLS",
        variants.map { MediaOption(url = it, label = "v", hls = true) },
    )

    private fun MediaCollector.offerAndAdd(url: String, analysis: MediaAnalysis.Found): Boolean {
        val c = offer(url) ?: return false
        return add(c, analysis)
    }

    @Test fun newUrlsBecomeCandidatesOnlyOnce() {
        val c = MediaCollector().also { it.reset(page) }
        assertNotNull(c.offer("https://cdn.example.com/a.mp4?t=1"))
        assertNull(c.offer("https://cdn.example.com/a.mp4?t=1"))
        assertNull("fragment does not make it new", c.offer("https://cdn.example.com/a.mp4?t=1#x"))
        assertNotNull("a different token is a different URL", c.offer("https://cdn.example.com/a.mp4?t=2"))
    }

    @Test fun nonMediaNeverBecomesACandidate() {
        val c = MediaCollector().also { it.reset(page) }
        assertNull(c.offer("https://cdn.example.com/app.js"))
        assertNull(c.offer("https://cdn.example.com/pixel.gif?x=a.mp4"))
        assertNull(c.offer("https://x.googlevideo.com/videoplayback?mime=video%2Fmp4"))
        assertTrue(c.isEmpty())
    }

    @Test fun segmentsAreCountedNotListedOnceAManifestWasSeen() {
        val c = MediaCollector().also { it.reset(page) }
        assertNotNull(c.offer("https://cdn.example.com/hls/master.m3u8"))
        repeat(300) { assertNull(c.offer("https://cdn.example.com/hls/seg-$it.ts")) }
        repeat(50) { assertNull(c.offer("https://cdn.example.com/hls/frag-$it.aac")) }
        repeat(50) { assertNull(c.offer("https://cdn.example.com/hls/chunk_$it.m4s")) }
        assertEquals(400, c.groupedSegments)
        assertTrue(c.isEmpty())
        // a real standalone file on the same page is still found
        assertNotNull(c.offer("https://cdn.example.com/download/holiday.mp4"))
    }

    @Test fun aacIsAnAudioFileUntilAManifestAppears() {
        val c = MediaCollector().also { it.reset(page) }
        assertNotNull(c.offer("https://cdn.example.com/song.aac"))
        assertNotNull(c.offer("https://cdn.example.com/hls/index.m3u8"))
        assertNull(c.offer("https://cdn.example.com/hls/seg1.aac"))
    }

    @Test fun masterPlaylistAbsorbsItsVariantPlaylists() {
        val c = MediaCollector().also { it.reset(page) }
        // the player fetches a variant playlist before the master was analysed
        assertTrue(c.offerAndAdd("https://cdn.example.com/hls/720/index.m3u8", hls("-", "https://cdn.example.com/hls/720/index.m3u8")))
        val master = c.offer("https://cdn.example.com/hls/master.m3u8")!!
        assertTrue(
            c.add(
                master,
                hls("m", "https://cdn.example.com/hls/720/index.m3u8", "https://cdn.example.com/hls/360/index.m3u8"),
            ),
        )
        assertEquals(listOf("https://cdn.example.com/hls/master.m3u8"), c.snapshot().map { it.url })
        // and later requests for the children are not offered again
        assertNull(c.offer("https://cdn.example.com/hls/360/index.m3u8"))
        assertNull(c.offer("https://cdn.example.com/hls/720/index.m3u8"))
    }

    @Test fun navigatingToAnotherPageResetsEverything() {
        val c = MediaCollector().also { it.reset(page) }
        assertTrue(c.offerAndAdd("https://cdn.example.com/a.mp4", file("https://cdn.example.com/a.mp4")))
        val stale = c.offer("https://cdn.example.com/slow.mp4")!!
        val epoch = c.epoch

        assertFalse("same page (fragment only) keeps the list", c.reset("$page#comments"))
        assertEquals(1, c.snapshot().size)

        assertTrue(c.reset("https://site.example.com/watch/2"))
        assertTrue(c.snapshot().isEmpty())
        assertEquals(epoch + 1, c.epoch)
        // a probe that finishes after navigation is dropped
        assertFalse(c.add(stale, file("https://cdn.example.com/slow.mp4")))
        assertTrue(c.snapshot().isEmpty())
        // the same URL can be found again on the new page
        assertNotNull(c.offer("https://cdn.example.com/a.mp4"))
        assertEquals(0, c.groupedSegments)
    }

    @Test fun playingItemMovesToTheTopAndNewerComesFirst() {
        val c = MediaCollector().also { it.reset(page) }
        c.offerAndAdd("https://cdn.example.com/1.mp4", file("https://cdn.example.com/1.mp4"))
        c.offerAndAdd("https://cdn.example.com/2.mp4", file("https://cdn.example.com/2.mp4"))
        c.offerAndAdd("https://cdn.example.com/3.mp4", file("https://cdn.example.com/3.mp4"))
        assertEquals(listOf("3", "2", "1"), c.snapshot().map { it.url.substringAfterLast('/').substringBefore('.') })

        c.markPlaying("https://cdn.example.com/1.mp4#frag")
        val snap = c.snapshot()
        assertEquals("1.mp4", snap.first().url.substringAfterLast('/'))
        assertTrue(snap.first().playing)
        assertEquals(1, snap.count { it.playing })
    }

    @Test fun playEventBeforeTheProbeFinishesStillMarksTheItem() {
        val c = MediaCollector().also { it.reset(page) }
        c.markPlaying("https://cdn.example.com/late.mp4")
        c.offerAndAdd("https://cdn.example.com/early.mp4", file("https://cdn.example.com/early.mp4"))
        c.offerAndAdd("https://cdn.example.com/late.mp4", file("https://cdn.example.com/late.mp4"))
        assertTrue(c.snapshot().first().playing)
        assertTrue(c.snapshot().first().url.endsWith("late.mp4"))
    }

    @Test fun blobPlaybackMarksTheNewestManifest() {
        val c = MediaCollector().also { it.reset(page) }
        c.offerAndAdd("https://cdn.example.com/1.mp4", file("https://cdn.example.com/1.mp4"))
        c.offerAndAdd("https://cdn.example.com/hls/index.m3u8", hls("-", "https://cdn.example.com/hls/index.m3u8"))
        c.offerAndAdd("https://cdn.example.com/2.mp4", file("https://cdn.example.com/2.mp4"))
        c.markPlayingManifest()
        val top = c.snapshot().first()
        assertTrue(top.playing)
        assertEquals(MediaKind.HLS, top.kind)
    }

    private fun hlsOf(seconds: Double, variant: String) = MediaAnalysis.Found(
        MediaKind.HLS, "HLS", listOf(MediaOption(url = variant, label = "720p", hls = true, durationSec = seconds)),
    )

    @Test fun blobPlaybackPicksTheManifestOfTheSameLengthNotTheNewest() {
        // A feed (X) plays a 45 s video and has already loaded the next one, a 12 s clip.
        val c = MediaCollector().also { it.reset(page) }
        c.offerAndAdd("https://video.example.com/a/master.m3u8", hlsOf(45.2, "https://video.example.com/a/720.m3u8"))
        c.offerAndAdd("https://video.example.com/b/master.m3u8", hlsOf(12.0, "https://video.example.com/b/720.m3u8"))
        c.markPlayingManifest(44.9)
        val top = c.snapshot().first()
        assertTrue(top.playing)
        assertEquals("https://video.example.com/a/master.m3u8", top.url)
        assertEquals(45.2, top.durationSec, 0.001)
    }

    @Test fun aManifestCheckedAfterThePlayEventTakesOverTheMarkWhenItsLengthFits() {
        val c = MediaCollector().also { it.reset(page) }
        c.offerAndAdd("https://video.example.com/next/master.m3u8", hlsOf(12.0, "https://video.example.com/next/720.m3u8"))
        c.markPlayingManifest(30.0) // nothing that long yet: the newest is the guess
        assertEquals("https://video.example.com/next/master.m3u8", c.snapshot().first { it.playing }.url)
        c.offerAndAdd("https://video.example.com/this/master.m3u8", hlsOf(30.4, "https://video.example.com/this/720.m3u8"))
        assertEquals("https://video.example.com/this/master.m3u8", c.snapshot().first { it.playing }.url)
    }

    @Test fun withoutAMatchingLengthTheNewestManifestIsStillTheGuess() {
        val c = MediaCollector().also { it.reset(page) }
        c.offerAndAdd("https://video.example.com/a/master.m3u8", hlsOf(45.0, "https://video.example.com/a/720.m3u8"))
        c.offerAndAdd("https://video.example.com/b/master.m3u8", hlsOf(12.0, "https://video.example.com/b/720.m3u8"))
        c.markPlayingManifest(90.0)
        assertEquals("https://video.example.com/b/master.m3u8", c.snapshot().first { it.playing }.url)
        c.markPlayingManifest() // length unknown
        assertEquals("https://video.example.com/b/master.m3u8", c.snapshot().first { it.playing }.url)
    }

    @Test fun aRealStreamUrlFromThePlayerWinsOverTheLength() {
        val c = MediaCollector().also { it.reset(page) }
        c.offerAndAdd("https://cdn.example.com/1.mp4", file("https://cdn.example.com/1.mp4"))
        c.markPlayingManifest(10.0)
        c.markPlaying("https://cdn.example.com/1.mp4")
        c.offerAndAdd("https://video.example.com/a/master.m3u8", hlsOf(10.0, "https://video.example.com/a/720.m3u8"))
        assertEquals("a later manifest of that length doesn't steal the mark", "https://cdn.example.com/1.mp4", c.snapshot().first { it.playing }.url)
    }

    @Test fun aBareLinkIsACandidateOnlyWhenAMediaElementOfThePagePlaysIt() {
        val c = MediaCollector().also { it.reset(page) }
        val bare = "https://media.example.com/v/8f3a9c2e?token=abc"
        assertNull("a network request alone is ignored", c.offer(bare))
        val candidate = c.offer(bare, fromElement = true)
        assertNotNull("the page's <video> plays it, so it is checked", candidate)
        assertEquals(MediaClassifier.Kind.UNKNOWN_MEDIA, candidate!!.kind)
        assertNull("and only once", c.offer(bare, fromElement = true))
    }

    @Test fun unsupportedItemsAreListedBelowDownloadableOnes() {
        val c = MediaCollector().also { it.reset(page) }
        val dash = MediaAnalysis.Found(
            MediaKind.DASH, "DASH",
            listOf(MediaOption(url = "https://cdn.example.com/m.mpd", label = "DASH", unsupportedReason = "DASH not supported yet")),
        )
        c.offerAndAdd("https://cdn.example.com/m.mpd", dash)
        c.offerAndAdd("https://cdn.example.com/a.mp4", file("https://cdn.example.com/a.mp4"))
        val snap = c.snapshot()
        assertEquals(2, snap.size)
        assertTrue(snap[0].supported)
        assertFalse(snap[1].supported)
    }

    @Test fun candidateCapProtectsAgainstSpamPages() {
        val c = MediaCollector(maxCandidates = 5).also { it.reset(page) }
        val offered = (1..20).count { c.offer("https://cdn.example.com/$it.mp4") != null }
        assertEquals(5, offered)
    }

    @Test fun releasedCandidatesCanBeOfferedAgain() {
        val c = MediaCollector().also { it.reset(page) }
        val first = c.offer("https://cdn.example.com/flaky.mp4")!!
        assertNull(c.offer("https://cdn.example.com/flaky.mp4"))
        c.release(first)
        assertNotNull(c.offer("https://cdn.example.com/flaky.mp4"))
    }

    @Test fun refererOfTheRequestingFrameIsKept() {
        val c = MediaCollector().also { it.reset(page) }
        val cand = c.offer("https://cdn.example.com/a.mp4", referer = "https://player.example.org/embed/9")!!
        c.add(cand, file("https://cdn.example.com/a.mp4"))
        assertEquals("https://player.example.org/embed/9", c.snapshot().single().referer)
    }
}
