package com.example.videobrowser

/** Conservative fallback estimate for yt-dlp formats without filesize metadata. */
internal object YtDlpFormatSize {
    fun estimateBytes(bitrateKbps: Double, durationSeconds: Double): Long {
        if (!bitrateKbps.isFinite() || bitrateKbps <= 0.0) return -1L
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0 || durationSeconds > 86_400.0) return -1L
        val bytes = bitrateKbps * 1000.0 * durationSeconds / 8.0
        if (!bytes.isFinite() || bytes < 1.0 || bytes >= Long.MAX_VALUE.toDouble()) return -1L
        return bytes.toLong().takeIf { it > 0L } ?: -1L
    }
}
