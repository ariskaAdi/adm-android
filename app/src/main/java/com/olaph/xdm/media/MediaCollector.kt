package com.olaph.xdm.media

/** A URL that passed the cheap checks and still has to be verified over the network. */
class MediaCandidate(
    val url: String,
    val key: String,
    val kind: MediaClassifier.Kind,
    /** Frame that asked for it (Referer of the request), when known. */
    val referer: String?,
    /** [MediaCollector.epoch] at the time; results of an earlier page are dropped. */
    val epoch: Int,
)

/**
 * Media found on one browser tab. Pure and thread-safe: it is fed from the WebView's network thread, from
 * the JavaScript bridge, and from the coroutines verifying candidates.
 *
 * A top-level navigation to another page [reset]s it. Segment URLs of a stream are only counted.
 */
class MediaCollector(private val maxCandidates: Int = MAX_CANDIDATES) {
    private val lock = Any()
    private val items = LinkedHashMap<String, DetectedMedia>()
    private val seen = HashSet<String>()
    private val children = HashSet<String>()
    private var seq = 0L
    private var playingKey: String? = null
    /** Length of the `blob:` video that is playing, while the playing item was picked by length. */
    private var playingDurationSec: Double? = null
    private var manifestSeen = false
    private var segments = 0

    var pageUrl: String? = null
        private set
    var epoch: Int = 0
        private set

    /** Number of stream chunks that were skipped for the current page. */
    val groupedSegments: Int get() = synchronized(lock) { segments }

    /** Starts a new page. Returns false (and keeps everything) when [url] is the page that is already shown. */
    fun reset(url: String?): Boolean = synchronized(lock) {
        val key = url?.let { pageKey(it) }
        if (key != null && key == pageUrl) return false
        pageUrl = key
        epoch++
        items.clear(); seen.clear(); children.clear()
        seq = 0; playingKey = null; playingDurationSec = null; manifestSeen = false; segments = 0
        true
    }

    /**
     * Looks at one URL; returns a candidate when it is worth verifying and was not seen before. [fromElement]: the
     * page's own media element plays it, so it is checked even when the URL doesn't look like media (see
     * [MediaClassifier.classifyElementSource]).
     */
    fun offer(url: String, referer: String? = null, fromElement: Boolean = false): MediaCandidate? = synchronized(lock) {
        val kind = if (fromElement) MediaClassifier.classifyElementSource(url) else MediaClassifier.classify(url)
        when (kind) {
            MediaClassifier.Kind.IGNORE -> return null
            MediaClassifier.Kind.SEGMENT -> { segments++; return null }
            MediaClassifier.Kind.HLS, MediaClassifier.Kind.DASH -> manifestSeen = true
            MediaClassifier.Kind.AUDIO_FILE, MediaClassifier.Kind.VIDEO_FILE ->
                if (manifestSeen && MediaClassifier.isChunkLike(url)) { segments++; return null }
            MediaClassifier.Kind.UNKNOWN_MEDIA -> Unit
        }
        val key = MediaClassifier.normalize(url)
        if (key in children || key in seen) return null
        if (seen.size >= maxCandidates) return null
        seen += key
        MediaCandidate(url, key, kind, referer, epoch)
    }

    /** Stores a verified result. Returns true when the list changed (a new item appeared). */
    fun add(candidate: MediaCandidate, analysis: MediaAnalysis.Found): Boolean = synchronized(lock) {
        if (candidate.epoch != epoch) return false
        if (candidate.key in items) return false
        // A master playlist replaces the media playlists of its own variants that were sniffed earlier.
        for (child in analysis.childUrls) {
            val childKey = MediaClassifier.normalize(child)
            if (childKey == candidate.key) continue // a bare media playlist lists itself
            children += childKey
            items.remove(childKey)
        }
        if (analysis.options.isEmpty()) return false
        items[candidate.key] = DetectedMedia(
            key = candidate.key,
            url = candidate.url,
            kind = analysis.kind,
            typeLabel = analysis.typeLabel,
            options = analysis.options,
            seq = ++seq,
            referer = candidate.referer,
        )
        // The playing blob video's manifest may only be checked now: take over the mark if the length fits better.
        playingDurationSec?.let { d ->
            val manifests = items.values.filter { it.kind != MediaKind.FILE }
            closestByDuration(manifests, d)?.let { playingKey = it.key }
        }
        true
    }

    /** The check failed for a transient reason (timeout...): allow the URL to be offered again. */
    fun release(candidate: MediaCandidate): Unit = synchronized(lock) {
        if (candidate.epoch == epoch) seen -= candidate.key
    }

    /** The page's player started [url] (from a `play` event). Unknown URLs are remembered until they show up. */
    fun markPlaying(url: String?): Unit = synchronized(lock) {
        playingKey = url?.takeIf { it.isNotBlank() }?.let { MediaClassifier.normalize(it) }
        playingDurationSec = null
    }

    /**
     * Player on a `blob:` source (MSE), whose stream URL the page doesn't show. With the element's [durationSec]
     * (when the page knows it), the manifest of the same length is the one playing: feeds such as X load the next
     * video ahead, so the newest manifest is often the wrong one. Without a match the newest manifest is the guess.
     * The duration is remembered, so a manifest whose check finishes after the `play` event is still recognised.
     */
    fun markPlayingManifest(durationSec: Double = -1.0): Unit = synchronized(lock) {
        playingDurationSec = durationSec.takeIf { it > 0 && it.isFinite() }
        val manifests = items.values.filter { it.kind != MediaKind.FILE }
        playingKey = playingDurationSec?.let { d -> closestByDuration(manifests, d) }?.key
            ?: manifests.maxByOrNull { it.seq }?.key
            ?: playingKey
    }

    /** The item whose length is within [DURATION_TOLERANCE_SEC] (or 3 %) of [d]; the newest of equally close ones. */
    private fun closestByDuration(candidates: Collection<DetectedMedia>, d: Double): DetectedMedia? =
        candidates
            .filter { it.durationSec > 0 && kotlin.math.abs(it.durationSec - d) <= maxOf(DURATION_TOLERANCE_SEC, d * 0.03) }
            .minWithOrNull(compareBy<DetectedMedia> { kotlin.math.abs(it.durationSec - d) }.thenByDescending { it.seq })

    fun isEmpty(): Boolean = synchronized(lock) { items.isEmpty() }

    /** Playing item first, then downloadable ones, newest first. Picture-only streams come paired with their sound. */
    fun snapshot(): List<DetectedMedia> = synchronized(lock) {
        val sounds = items.values.filter { it.kind == MediaKind.HLS && it.options.size == 1 && it.options[0].audioOnly && it.options[0].supported }
        items.values
            .map { pairWithSound(it, sounds) }
            .map { it.copy(playing = it.key == playingKey) }
            .sortedWith(compareByDescending<DetectedMedia> { it.playing }.thenByDescending { it.supported }.thenByDescending { it.seq })
    }

    /**
     * A bare HLS stream with picture but no sound (its master playlist was never seen, so nothing named its audio)
     * gets the sound stream found on the same page: the one of the same length (within [DURATION_TOLERANCE_SEC] or
     * 3 %), or, when lengths are unknown, the only one there is. Downloading it then merges both into one file.
     */
    private fun pairWithSound(media: DetectedMedia, sounds: List<DetectedMedia>): DetectedMedia {
        val option = media.options.singleOrNull() ?: return media
        if (media.kind != MediaKind.HLS || !option.videoOnly || option.audioUrl != null || sounds.isEmpty()) return media
        val d = option.durationSec
        val sound = if (d > 0) {
            sounds.filter { it.durationSec > 0 && kotlin.math.abs(it.durationSec - d) <= maxOf(DURATION_TOLERANCE_SEC, d * 0.03) }
                .minWithOrNull(compareBy<DetectedMedia> { kotlin.math.abs(it.durationSec - d) }.thenByDescending { it.seq })
        } else {
            sounds.singleOrNull()
        } ?: return media
        val detail = option.detail?.replace(HlsOptions.VIDEO_ONLY_NO_SOUND, HlsOptions.VIDEO_PLUS_AUDIO) ?: HlsOptions.VIDEO_PLUS_AUDIO
        return media.copy(options = listOf(option.copy(audioUrl = sound.options[0].url, videoOnly = false, detail = detail)))
    }

    private fun pageKey(url: String) = url.substringBefore('#')

    companion object {
        const val MAX_CANDIDATES = 150

        /** How far a manifest's length may be from the playing element's (segment rounding, player padding). */
        const val DURATION_TOLERANCE_SEC = 1.5
    }
}
