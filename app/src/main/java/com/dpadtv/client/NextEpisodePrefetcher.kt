package com.dpadtv.client

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException

/** One optional next-episode warm-up. Uses the playback cache, without another decoder/player. */
class NextEpisodePrefetcher(
    private val cacheFactory: CacheDataSource.Factory,
    private val manifestFactory: DataSource.Factory,
) {
    private var remainingBytes = 4 * 1024 * 1024
    private val buffer = ByteArray(32 * 1024)
    private val warmed = HashSet<Triple<String, Long, Long>>()
    @Volatile private var cancelled = false

    /** Run once on an IO worker. HLS targets an opening prefix of roughly 12 seconds, within the byte budget. */
    fun cache(url: String, videoHeight: Int) {
        checkCancelled()
        if (Util.inferContentType(Uri.parse(url)) != C.CONTENT_TYPE_HLS) {
            cacheResource(url)
            return
        }
        var playlist = readPlaylist(url)
        if (playlist is HlsMultivariantPlaylist) {
            val matching = playlist.variants.filter { it.format.height in 1..videoHeight }
            val variant = matching.maxByOrNull { it.format.bitrate }
                ?: playlist.variants.minByOrNull { it.format.bitrate } ?: return
            playlist = readPlaylist(variant.url.toString())
        }
        val media = playlist as? HlsMediaPlaylist ?: return
        if (!media.hasEndTag) return
        for (segment in media.segments) {
            checkCancelled()
            if (remainingBytes == 0 || segment.relativeStartTimeUs >= 12_000_000L) break
            if (segment.relativeStartTimeUs > 0 && segment.relativeStartTimeUs + segment.durationUs > 12_000_000L) break
            if (segment.hasGapTag) continue
            segment.fullSegmentEncryptionKeyUri?.let { cacheResource(UriUtil.resolve(media.baseUri, it)) }
            segment.initializationSegment?.let {
                cacheResource(UriUtil.resolve(media.baseUri, it.url), it.byteRangeOffset, it.byteRangeLength)
            }
            cacheResource(UriUtil.resolve(media.baseUri, segment.url), segment.byteRangeOffset, segment.byteRangeLength)
        }
    }

    /** Non-blocking; the worker stops before another read/request. In-flight HTTP uses a short timeout. */
    fun cancel() { cancelled = true }

    private fun readPlaylist(url: String): HlsPlaylist {
        checkCancelled()
        val source = manifestFactory.createDataSource()
        val uri = Uri.parse(url)
        val content = ByteArrayOutputStream()
        val limit = minOf(512 * 1024, remainingBytes)
        try {
            source.open(DataSpec.Builder().setUri(uri).build())
            while (content.size() < limit) {
                checkCancelled()
                val count = source.read(buffer, 0, minOf(buffer.size, limit - content.size()))
                if (count == C.RESULT_END_OF_INPUT) break
                content.write(buffer, 0, count)
                remainingBytes -= count
            }
            checkCancelled()
            return HlsPlaylistParser().parse(source.uri ?: uri, content.toByteArray().inputStream())
        } finally { source.close() }
    }

    private fun cacheResource(url: String, position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) {
        checkCancelled()
        if (remainingBytes == 0 || !warmed.add(Triple(url, position, length))) return
        val source = cacheFactory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(url).setPosition(position).setLength(length).build())
            while (remainingBytes > 0) {
                checkCancelled()
                val count = source.read(buffer, 0, minOf(buffer.size, remainingBytes))
                if (count == C.RESULT_END_OF_INPUT) break
                remainingBytes -= count
            }
            checkCancelled()
        } finally { source.close() }
    }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Next-episode warm-up cancelled")
    }
}
