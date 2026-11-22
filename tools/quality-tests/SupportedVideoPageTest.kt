package com.example.videobrowser

/** Small JVM-only regression suite; can be run with kotlinc without Android SDK. */
fun main() {
    val supported = listOf(
        "https://www.pornhub.com/view_video.php?viewkey=abc123",
        "https://www.pornhub.com/video/show?viewkey=abc123",
        "https://www.pornhub.com/embed/abc123",
        "https://www.pornhub.com/shorties/abc123",
        "https://www.thumbzilla.com/video/abc123",
        "https://xhamster.com/videos/sample-title-123456789",
        "https://xhamster.desi/movies/123456789/sample-title.html",
        "https://xhamster.com/xembed.php?video=123456789",
        "https://gayporn.com/video/bareback-creampie-fills-tight-hole-fast",
        "https://www.gayporn.com/video/sample-title/"
    )
    val listings = listOf(
        "https://www.pornhub.com/",
        "https://www.pornhub.com/videos",
        "https://www.pornhub.com/video",
        "https://www.pornhub.com/video?c=1",
        "https://www.pornhub.com/categories",
        "https://xhamster.com/",
        "https://xhamster.com/categories",
        "https://xhamster.com/videos",
        "https://xhamster.com/search/sample",
        "https://xhamster.com/user/sample",
        "https://gayporn.com/",
        "https://gayporn.com/videos",
        "https://gayporn.com/search/sample"
    )
    supported.forEach { url -> check(SupportedVideoPage.isSupported(url)) { "Expected video URL to pass: $url" } }
    listings.forEach { url ->
        check(!SupportedVideoPage.isSupported(url)) { "Listing URL was incorrectly accepted: $url" }
        if (url.contains("pornhub.com") || url.contains("xhamster.com") || url.contains("gayporn.com")) {
            check(SupportedVideoPage.isKnownSite(url)) { "Known listing domain was not recognized: $url" }
        }
    }
    check(!SupportedVideoPage.isSupported("javascript:alert(1)"))
    check(!SupportedVideoPage.isSupported("https://evilpornhub.com/view_video.php?viewkey=abc123"))
    check(!SupportedVideoPage.isSupported("https://pornhub.com/view_video.php?viewkey="))
    println("SupportedVideoPage passed: ${supported.size} video routes accepted; ${listings.size + 3} invalid/listing routes rejected")
}
