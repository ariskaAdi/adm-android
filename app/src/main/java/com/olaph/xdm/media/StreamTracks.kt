package com.olaph.xdm.media

/**
 * What a stream carries, read from its first bytes: the `hdlr` boxes of an fMP4 init section, or the program map
 * (PMT) of an MPEG-TS segment. Used for HLS media playlists seen without their master playlist, where the playlist
 * itself doesn't say whether the stream has sound. Pure; nothing here touches the network.
 */
object StreamTracks {

    enum class Content { VIDEO_AND_AUDIO, VIDEO_ONLY, AUDIO_ONLY, UNKNOWN }

    private fun of(video: Boolean, audio: Boolean) = when {
        video && audio -> Content.VIDEO_AND_AUDIO
        video -> Content.VIDEO_ONLY
        audio -> Content.AUDIO_ONLY
        else -> Content.UNKNOWN
    }

    /** From an fMP4 init section (`ftyp` + `moov`): each track's `hdlr` box names it "vide" or "soun". */
    fun fromMp4(bytes: ByteArray, length: Int = bytes.size): Content {
        var video = false
        var audio = false
        // Box layout: size(4) "hdlr"(4) version+flags(4) pre_defined(4) handler_type(4)
        var i = 4
        while (i + 16 <= length) {
            if (bytes[i] == 'h'.code.toByte() && bytes[i + 1] == 'd'.code.toByte() &&
                bytes[i + 2] == 'l'.code.toByte() && bytes[i + 3] == 'r'.code.toByte()
            ) {
                when (String(bytes, i + 12, 4, Charsets.ISO_8859_1)) {
                    "vide" -> video = true
                    "soun" -> audio = true
                }
                i += 16
            } else {
                i++
            }
        }
        return of(video, audio)
    }

    private const val PACKET = 188

    // ISO 13818-1 / ATSC stream types. 0x06 (private data, e.g. DVB AC-3) is ambiguous and left out.
    private val VIDEO_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24, 0x42, 0xEA)
    private val AUDIO_TYPES = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87)

    /** From the start of an MPEG-TS segment: the stream types its PMT lists. */
    fun fromTs(bytes: ByteArray, length: Int = bytes.size): Content {
        val sync = (0 until minOf(PACKET, length)).firstOrNull { k ->
            k + PACKET * 3 <= length && (0 until 3).all { bytes[k + it * PACKET] == 0x47.toByte() }
        } ?: return Content.UNKNOWN
        var pmtPid = -1
        var p = sync
        while (p + PACKET <= length) {
            if (bytes[p] != 0x47.toByte()) break
            val b1 = bytes[p + 1].toInt() and 0xFF
            val pusi = b1 and 0x40 != 0
            val pid = ((b1 and 0x1F) shl 8) or (bytes[p + 2].toInt() and 0xFF)
            val afc = (bytes[p + 3].toInt() shr 4) and 3
            if (pusi && afc and 1 != 0 && (pid == 0 || pid == pmtPid)) {
                var payload = p + 4 + if (afc and 2 != 0) 1 + (bytes[p + 4].toInt() and 0xFF) else 0
                if (payload < p + PACKET) {
                    payload += 1 + (bytes[payload].toInt() and 0xFF) // pointer field
                    val end = p + PACKET
                    if (pid == 0) {
                        pmtPid = patFirstPmt(bytes, payload, end)
                    } else {
                        return pmtContent(bytes, payload, end)
                    }
                }
            }
            p += PACKET
        }
        return Content.UNKNOWN
    }

    /** PID of the first program's PMT in a PAT section starting at [s], or -1. */
    private fun patFirstPmt(b: ByteArray, s: Int, end: Int): Int {
        if (s + 12 > end || (b[s].toInt() and 0xFF) != 0x00) return -1
        val sectionEnd = minOf(end, s + 3 + (((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)) - 4)
        var e = s + 8
        while (e + 4 <= sectionEnd) {
            val program = ((b[e].toInt() and 0xFF) shl 8) or (b[e + 1].toInt() and 0xFF)
            val pid = ((b[e + 2].toInt() and 0x1F) shl 8) or (b[e + 3].toInt() and 0xFF)
            if (program != 0) return pid // program 0 is the network PID
            e += 4
        }
        return -1
    }

    private fun pmtContent(b: ByteArray, s: Int, end: Int): Content {
        if (s + 12 > end || (b[s].toInt() and 0xFF) != 0x02) return Content.UNKNOWN
        val sectionEnd = minOf(end, s + 3 + (((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)) - 4)
        val programInfo = ((b[s + 10].toInt() and 0x0F) shl 8) or (b[s + 11].toInt() and 0xFF)
        var e = s + 12 + programInfo
        var video = false
        var audio = false
        while (e + 5 <= sectionEnd) {
            val type = b[e].toInt() and 0xFF
            if (type in VIDEO_TYPES) video = true
            if (type in AUDIO_TYPES) audio = true
            e += 5 + (((b[e + 3].toInt() and 0x0F) shl 8) or (b[e + 4].toInt() and 0xFF))
        }
        return of(video, audio)
    }
}
