package com.example.videobrowser

import java.util.Locale

/**
 * Normalizes the choices shown to the user.
 * A page may expose the same rendition through WebView, HLS and yt-dlp. Those
 * are transport alternatives, not separate quality levels, so the sheet shows
 * only the strongest downloadable representation for each video height.
 */
object FormatSelection {
    fun deduplicate(formats: List<Format>): List<Format> {
        val selected = LinkedHashMap<String, Format>()
        for (format in formats) {
            if (format.url.isBlank() || Formats.isAdUrl(format.url) || Formats.isPreviewUrl(format.url)) continue

            val audio = format.isAudio || Formats.isAudio(format.ext)
            val key = if (audio) {
                // Keep separate audio tracks; they may represent different languages.
                "audio:${format.ext.lowercase(Locale.ROOT)}:${format.url}"
            } else {
                val height = qualityHeight(format)
                if (height != null) "video:$height" else "video:unknown:${normalizeLabel(format.label)}"
            }

            val current = selected[key]
            if (current == null || isBetter(format, current)) selected[key] = format
        }

        return selected.values.sortedWith(
            compareByDescending<Format> { if (it.isAudio || Formats.isAudio(it.ext)) 0 else qualityHeight(it) ?: 0 }
                .thenByDescending { it.frameRate }
                .thenByDescending { it.bandwidth }
                .thenByDescending { it.bytes }
                .thenBy { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.url }
        )
    }

    private fun qualityHeight(format: Format): Int? {
        Formats.qualityFromLabel(format.label)?.let { return it }
        // Details commonly contain an explicit 1920x1080 value. Do not scan for
        // arbitrary numbers here: codec IDs such as avc1.640028 are not resolutions.
        val resolution = Regex("(?<![0-9])\\d{3,5}\\s*[x×]\\s*(\\d{3,5})(?![0-9])")
            .find(format.details)
        return resolution?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 144..4320 }
    }

    private fun normalizeLabel(label: String): String =
        label.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "").ifBlank { "unknown" }

    private fun isBetter(candidate: Format, current: Format): Boolean {
        val a = score(candidate)
        val b = score(current)
        for (index in a.indices) {
            if (a[index] != b[index]) return a[index] > b[index]
        }
        return candidate.url < current.url
    }

    /** Lexicographic score: muxed audio/video first, then codec/bitrate/frame-rate/size;
     * prefer yt-dlp transport only after media quality is otherwise comparable. */
    private fun score(format: Format): List<Long> {
        val codec = format.codec.lowercase(Locale.ROOT)
        val hasAudioCodec = Regex("(?:mp4a|aac|opus|vorbis|mp3|ac-3|ec-3|flac|alac)")
            .containsMatchIn(codec)
        return listOf(
            if (hasAudioCodec) 1L else 0L,
            if (codec.isNotBlank()) 1L else 0L,
            format.bandwidth.coerceAtLeast(0L),
            (format.frameRate.coerceAtLeast(0.0) * 1000.0).toLong(),
            format.bytes.coerceAtLeast(0L),
            if (format.ytDlpFormatId.isNotBlank() && format.ytDlpPageUrl.isNotBlank()) 1L else 0L,
            if (format.requestHeaders.isNotEmpty()) 1L else 0L,
            if (format.ext.equals("mp4", true) || format.ext.equals("m4v", true)) 1L else 0L
        )
    }
}
