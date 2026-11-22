package com.example.videobrowser

fun main() {
    check(YtDlpFormatSize.estimateBytes(800.0, 60.0) == 6_000_000L)
    check(YtDlpFormatSize.estimateBytes(1200.0, 120.0) == 18_000_000L)
    check(YtDlpFormatSize.estimateBytes(0.0, 60.0) == -1L)
    check(YtDlpFormatSize.estimateBytes(800.0, 0.0) == -1L)
    check(YtDlpFormatSize.estimateBytes(Double.NaN, 60.0) == -1L)
    check(YtDlpFormatSize.estimateBytes(800.0, 90_000.0) == -1L)
    println("yt-dlp bitrate/duration size estimate tests passed")
}
