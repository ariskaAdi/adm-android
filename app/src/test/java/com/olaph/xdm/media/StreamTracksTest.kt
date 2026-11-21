package com.olaph.xdm.media

import com.olaph.xdm.media.StreamTracks.Content
import org.junit.Assert.assertEquals
import org.junit.Test

/** ffmpeg-made fixtures (scripts/gen-remux-fixtures.sh, scripts/gen-youtube-fixtures.sh); only their first 64 KB is read. */
class StreamTracksTest {

    private fun head(resource: String, max: Int = 64 * 1024): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(resource)) { "missing fixture $resource" }.use { it.readNBytes(max) }

    @Test fun mpegTsProgramMapTellsPictureAndSoundApart() {
        assertEquals(Content.VIDEO_AND_AUDIO, StreamTracks.fromTs(head("/remux/av.ts")))
        assertEquals(Content.VIDEO_ONLY, StreamTracks.fromTs(head("/remux/video-only.ts")))
        assertEquals(Content.AUDIO_ONLY, StreamTracks.fromTs(head("/remux/audio.ts")))
        assertEquals("MP3 audio counts as sound", Content.VIDEO_AND_AUDIO, StreamTracks.fromTs(head("/remux/mp3.ts")))
    }

    @Test fun fmp4HandlerBoxesTellPictureAndSoundApart() {
        assertEquals(Content.VIDEO_ONLY, StreamTracks.fromMp4(head("/youtube/video-avc.mp4")))
        assertEquals(Content.AUDIO_ONLY, StreamTracks.fromMp4(head("/youtube/audio-aac.m4a")))
    }

    @Test fun aFewBytesOrGarbageAreUnknown() {
        assertEquals(Content.UNKNOWN, StreamTracks.fromTs(ByteArray(100)))
        assertEquals(Content.UNKNOWN, StreamTracks.fromTs("<html>not a stream</html>".toByteArray()))
        assertEquals(Content.UNKNOWN, StreamTracks.fromMp4(ByteArray(0)))
    }
}
