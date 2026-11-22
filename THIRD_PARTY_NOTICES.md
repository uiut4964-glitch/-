# Third-party notices

## yt-dlp

- Source: https://github.com/yt-dlp/yt-dlp
- Bundled zipapp: stable release `2026.10.26`.
- License: Unlicense; retain upstream copyright and license materials where applicable.
- VideoBrowser applies a narrow local compatibility patch to `yt_dlp/extractor/xhamster.py` after verifying the upstream binary checksum. The patch adds a missing-title fallback and routes opaque `videoModel.sources` values through the existing xHamster URL-decipher implementation; it does not replace or remove the upstream extractor.

## CPython Android runtime and curl_cffi

- Runtime source/build project: https://github.com/deniscerri/ytdlnis-packages
- Bundled package: Python 3.14.8 ARM64 runtime extracted from the upstream `python-3.14.8` package; SHA-256 pinned in `tools/prepare_android_runtime.sh`.
- Runtime includes Python 3.14.8 and `curl_cffi 0.17.0` built for Android ARM64.
- The runtime packaging project declares GNU GPL-3.0. Review and satisfy its source, attribution, and redistribution obligations before distributing the APK.
- curl_cffi source: https://github.com/lexiforest/curl_cffi (MIT license); libcurl/BoringSSL and other bundled Python dependencies carry their own notices in the upstream runtime bundle.

## Apache Commons Compress

- Maven: `org.apache.commons:commons-compress:1.27.1`.
- Source: https://commons.apache.org/proper/commons-compress/
- License: Apache License 2.0.
- Used to extract the Python runtime archive while preserving Unix symbolic links.

## Previous wrapper (not used in this version)

`io.github.junkfood02.youtubedl-android:library:0.18.1` is removed from the app dependencies in v1.12.0. It is not packaged by this version.



## Python runtime packaging in VideoBrowser 1.12.3

The ARM64 Python 3.14.8 / curl_cffi 0.17.0 / QuickJS native runtime is bundled inside the single VideoBrowser APK under `app/src/main/jniLibs/arm64-v8a`. Android installs these APK-embedded native files under the app's `nativeLibraryDir`; Python's writable runtime data is separately expanded under app-private storage. There is no separate runtime APK in v1.12.3. The Python runtime and yt-dlp remain subject to their respective upstream licenses; see the upstream YTDLnis packages repository and yt-dlp project notices.
