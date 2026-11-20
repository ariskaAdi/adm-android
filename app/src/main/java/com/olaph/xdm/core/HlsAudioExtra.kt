package com.olaph.xdm.core

import com.olaph.xdm.engine.UrlUtils

/**
 * For an HLS download whose quality has its sound in a separate playlist, [com.olaph.xdm.data.DownloadEntity.extra]
 * holds that audio playlist's URL, so the video and the audio are fetched and merged into one file. (YouTube rows use
 * `extra` for their plan; the two kinds never mix.)
 */
object HlsAudioExtra {
    private const val PREFIX = "hls-audio:"

    fun encode(audioUrl: String?): String? = audioUrl?.takeIf { UrlUtils.isHttpUrl(it) }?.let { PREFIX + it }

    fun audioUrl(extra: String?): String? = extra?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { UrlUtils.isHttpUrl(it) }
}
