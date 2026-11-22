package com.example.videobrowser

fun main() {
    val formats = listOf(
        Format("1080P HD", "1920x1080 • avc1", "https://cdn.example/video-1080-hls.m3u8", "hls", "mp4",
            codec = "avc1", bandwidth = 2_000_000L, frameRate = 30.0, ytDlpPageUrl = "https://site.example/watch/1", ytDlpFormatId = "hls-1080"),
        Format("1080P HD", "1920x1080 • avc1 / mp4a", "https://cdn.example/video-1080.mp4", "direct", "mp4",
            codec = "avc1 / mp4a.40.2", bandwidth = 4_500_000L, frameRate = 30.0, bytes = 90_000_000L,
            ytDlpPageUrl = "https://site.example/watch/1", ytDlpFormatId = "1080p"),
        Format("720P HD", "1280x720", "https://cdn.example/video-720.mp4", "direct", "mp4",
            codec = "avc1 / mp4a.40.2", bandwidth = 2_400_000L, ytDlpPageUrl = "https://site.example/watch/1", ytDlpFormatId = "720p"),
        Format("240P", "426x240", "https://cdn.example/video-240-a.mp4", "direct", "mp4",
            codec = "avc1", bandwidth = 350_000L, ytDlpPageUrl = "https://site.example/watch/1", ytDlpFormatId = "240-a"),
        Format("240P", "426x240", "https://cdn.example/video-240-b.mp4", "direct", "mp4",
            codec = "avc1 / mp4a.40.2", bandwidth = 500_000L, ytDlpPageUrl = "https://site.example/watch/1", ytDlpFormatId = "240-b"),
        Format("الجودة الأصلية", "", "https://ht-cdn2.adtng.com/ad.mp4", "direct", "mp4"),
        Format("الجودة الأصلية", "", "https://cdn.example/unknown-a.mp4", "direct", "mp4"),
        Format("الجودة الأصلية", "", "https://cdn.example/unknown-b.mp4", "direct", "mp4")
    )

    val result = FormatSelection.deduplicate(formats)
    val video = result.filterNot { it.isAudio || Formats.isAudio(it.ext) }
    val heights = video.mapNotNull { Formats.qualityFromLabel(it.label) ?: Regex("(?<![0-9])\\d{3,5}\\s*[x×]\\s*(\\d{3,5})(?![0-9])").find(it.details)?.groupValues?.get(1)?.toIntOrNull() }
    check(heights.count { it == 1080 } == 1) { "1080p variants were not collapsed to one choice" }
    check(heights.count { it == 720 } == 1) { "720p choice was lost or duplicated" }
    check(heights.count { it == 240 } == 1) { "240p variants were not collapsed to one choice" }
    check(video.none { Formats.isAdUrl(it.url) }) { "Ad CDN URL leaked into the quality list" }
    check(result.none { it.url.contains("adtng.com") }) { "adtng.com was not filtered" }
    check(result.any { it.url.endsWith("video-1080.mp4") }) { "The stronger muxed 1080p choice was not selected" }
    check(result.any { it.url.endsWith("video-240-b.mp4") }) { "The stronger muxed 240p choice was not selected" }
    check(video.count { it.label == "الجودة الأصلية" } == 1) { "Unknown original-quality duplicates were not collapsed" }
    check(Formats.isAdUrl("https://ht-cdn2.adtng.com/path/asset.mp4")) { "adtng.com must be recognized as an ad host" }
    check(!Formats.isAdUrl("https://cdn.example/video-1080.mp4")) { "A normal CDN was incorrectly marked as an ad" }

    println("PASS: one video choice per height, best muxed candidate preferred, unknown duplicates collapsed, ad CDN excluded")
}
