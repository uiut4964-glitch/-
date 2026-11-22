package com.example.videobrowser

import android.content.Context
import android.system.Os
import android.webkit.CookieManager
import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * yt-dlp runtime for Android ARM64, embedded in the single VideoBrowser APK.
 * Python/curl_cffi executable libraries are packaged as this APK's jniLibs and
 * installed by Android under ApplicationInfo.nativeLibraryDir. Runtime data is
 * extracted separately into app-private storage; executable binaries are never
 * copied into that writable directory. The browser extractor and downloader use
 * the same Python/curl_cffi process.
 */
object YtDlpExtractor {
    private const val PYTHON_VERSION = "3.14.8"
    private const val CURL_CFFI_VERSION = "0.17.0"
    private const val BUNDLED_YTDLP_VERSION = "2026.10.26"
    private const val XHAMSTER_PATCH_VERSION = "xh-title-source-decipher-v1"
    private const val XHAMSTER_PATCH_ASSET = "site_extractor_patch.py"
    private const val RUNTIME_MARKER = "vb-python-runtime.txt"
    private const val PROCESS_TIMEOUT_SECONDS = 150L

    data class Result(
        val formats: List<Format>,
        val title: String = "",
        val thumbnail: String = "",
        val durationSec: Double = 0.0,
        val extractor: String = "",
        val version: String = "",
        val error: String = ""
    )

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VideoBrowser-yt-dlp").apply { isDaemon = true }
    }
    private val ioWorkers = Executors.newCachedThreadPool { r ->
        Thread(r, "VideoBrowser-yt-dlp-io").apply { isDaemon = true }
    }
    @Volatile private var initialized = false
    @Volatile private var initError = ""
    @Volatile private var runtimeSummary = "لم تتم تهيئة محرك Python/yt-dlp بعد"
    @Volatile private var cachedYtDlpVersion = BUNDLED_YTDLP_VERSION
    private val initLock = Any()

    fun warmUp(context: Context) {
        worker.execute {
            try {
                ensureInitialized(context.applicationContext)
            } catch (e: Exception) {
                initError = safeError(e)
                runtimeSummary = "فشلت تهيئة Python/yt-dlp: $initError"
            }
        }
    }

    fun extract(
        context: Context,
        pageUrl: String,
        requestHeaders: Map<String, String>,
        callback: (Result) -> Unit
    ) {
        worker.execute {
            val result = try {
                val app = context.applicationContext
                ensureInitialized(app)
                val headers = pageHeaders(pageUrl, requestHeaders)
                val cookieFile = createCookieFile(app, pageUrl)
                try {
                    val args = mutableListOf(
                        "--dump-single-json", "--no-playlist", "--no-progress",
                        "--no-cache-dir", "--socket-timeout", "20", "--extractor-retries", "1"
                    )
                    if (isPornhub(pageUrl)) args.addAll(listOf("--impersonate", "chrome"))
                    addHeaders(args, headers)
                    if (cookieFile != null) args.addAll(listOf("--cookies", cookieFile.absolutePath))
                    args.add(pageUrl)
                    val process = runYtDlp(app, args, PROCESS_TIMEOUT_SECONDS)
                    if (process.exitCode != 0) {
                        throw IOException(process.stderr.ifBlank { process.stdout }.ifBlank { "yt-dlp exited ${process.exitCode}" })
                    }
                    val json = extractJson(process.stdout)
                    val mapped = mapFormats(json, headers, pageUrl)
                    val version = currentVersion(app)
                    Result(
                        formats = mapped,
                        title = json.optStringClean("title"),
                        thumbnail = thumbnailUrl(json),
                        durationSec = json.optDouble("duration", 0.0).takeIf { it > 0.0 } ?: 0.0,
                        extractor = json.optStringClean("extractor_key").ifBlank { json.optStringClean("extractor") },
                        version = version,
                        error = if (mapped.isEmpty()) {
                            val details = summarizeExtractorWarnings(process.stderr)
                            "yt-dlp استخرج بيانات الصفحة لكن لم يُرجع صيغ فيديو متوافقة مع مسار التنزيل الحالي" +
                                if (details.isNotBlank()) " • $details" else ""
                        } else ""
                    )
                } finally {
                    cookieFile?.delete()
                }
            } catch (e: Exception) {
                Result(
                    formats = emptyList(),
                    version = cachedYtDlpVersion,
                    error = safeError(e)
                )
            }
            try { callback(result) } catch (_: Exception) {}
        }
    }

    /**
     * Download a chosen yt-dlp representation through the same curl_cffi-backed
     * runtime used for extraction. This is deliberately used for yt-dlp formats;
     * the WebView/legacy formats keep using the app's existing direct/HLS engine.
     */
    fun downloadToFile(
        context: Context,
        pageUrl: String,
        formatId: String,
        outputWithoutExtension: File,
        requestHeaders: Map<String, String>,
        shouldContinue: () -> Boolean = { true }
    ): File {
        val app = context.applicationContext
        ensureInitialized(app)
        if (formatId.isBlank() || formatId.length > 160 || formatId.any { Character.isISOControl(it) }) {
            throw IOException("معرّف صيغة yt-dlp غير صالح")
        }
        outputWithoutExtension.parentFile?.mkdirs()
        outputWithoutExtension.delete()
        outputWithoutExtension.parentFile?.listFiles()?.filter {
            it.name.startsWith(outputWithoutExtension.name + ".") || it.name == outputWithoutExtension.name + ".part"
        }?.forEach { it.delete() }

        val headers = pageHeaders(pageUrl, requestHeaders)
        val cookieFile = createCookieFile(app, pageUrl)
        try {
            val template = File(outputWithoutExtension.parentFile, outputWithoutExtension.name + ".%(ext)s")
            val args = mutableListOf(
                "--no-playlist", "--no-warnings", "--no-progress", "--no-cache-dir",
                "--socket-timeout", "20", "--retries", "2", "--fragment-retries", "2",
                "--force-overwrites", "--no-continue", "--format", formatId,
                "--output", template.absolutePath
            )
            if (isPornhub(pageUrl)) args.addAll(listOf("--impersonate", "chrome"))
            addHeaders(args, headers)
            if (cookieFile != null) args.addAll(listOf("--cookies", cookieFile.absolutePath))
            args.add(pageUrl)

            val process = runYtDlp(app, args, 4L * 60L * 60L, shouldContinue)
            if (process.exitCode != 0) throw IOException(process.stderr.ifBlank { process.stdout }.ifBlank { "yt-dlp download failed (${process.exitCode})" })
            val generated = outputWithoutExtension.parentFile?.listFiles()?.filter {
                it.isFile && it.name.startsWith(outputWithoutExtension.name + ".") && !it.name.endsWith(".part")
            }?.maxByOrNull { it.length() }
            if (generated == null || !generated.exists() || generated.length() <= 0L) {
                throw IOException("انتهى yt-dlp دون إنشاء ملف فيديو")
            }
            if (generated != outputWithoutExtension && !generated.renameTo(outputWithoutExtension)) {
                generated.copyTo(outputWithoutExtension, overwrite = true)
                generated.delete()
            }
            if (outputWithoutExtension.length() <= 0L) throw IOException("ملف التنزيل الناتج فارغ")
            return outputWithoutExtension
        } finally {
            cookieFile?.delete()
        }
    }

    /** Explicit update action; Python and curl_cffi stay pinned to the packaged runtime. */
    fun update(context: Context, callback: (Boolean, String) -> Unit) {
        worker.execute {
            val result = try {
                val app = context.applicationContext
                ensureInitialized(app)
                val script = File(app.filesDir, "yt-dlp/yt-dlp")
                val backup = File(app.cacheDir, "yt-dlp-pre-update.backup")
                val marker = xhamsterPatchMarker(script)
                script.copyTo(backup, overwrite = true)
                try {
                    val update = runYtDlp(app, listOf("--update-to", "stable"), 180L)
                    if (update.exitCode != 0) throw IOException(update.stderr.ifBlank { update.stdout })
                    marker.delete()
                    applyXHamsterExtractorPatch(app, script)
                    cachedYtDlpVersion = currentVersion(app)
                    true to "تم تحديث yt-dlp إلى $cachedYtDlpVersion مع إعادة تفعيل إصلاح xHamster (Python $PYTHON_VERSION / curl_cffi $CURL_CFFI_VERSION)"
                } catch (e: Exception) {
                    backup.copyTo(script, overwrite = true)
                    marker.writeText(XHAMSTER_PATCH_VERSION)
                    throw e
                } finally {
                    backup.delete()
                }
            } catch (e: Exception) {
                false to "تعذر تحديث yt-dlp: ${safeError(e)}"
            }
            try { callback(result.first, result.second) } catch (_: Exception) {}
        }
    }

    /** A neutral, user-safe status; implementation and supported-site details stay internal. */
    fun status(context: Context): String = when {
        initialized -> "جاهز"
        initError.isNotBlank() -> "تعذرت تهيئة المحرك"
        else -> "لم تتم تهيئة المحرك بعد"
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val nativeDir = appNativeLibraryDir(context)
            val python = File(nativeDir, "libpython.so")
            val runtimeZip = File(nativeDir, "libpython.zip.so")
            val ytDlpAsset = File(context.filesDir, "yt-dlp/yt-dlp")
            if (!python.isFile || !python.canExecute() || !runtimeZip.isFile || !File(nativeDir, "libqjs.so").isFile) {
                throw IOException("ملفات Python المضمنة في VideoBrowser غير مكتملة أو غير قابلة للتشغيل من مجلد مكتبات التطبيق")
            }
            if (!ytDlpAsset.exists() || !ytDlpAsset.isFile) {
                ytDlpAsset.parentFile?.mkdirs()
                context.assets.open("yt-dlp").use { input ->
                    FileOutputStream(ytDlpAsset).use { output -> input.copyTo(output) }
                }
            }
            val runtimeRoot = File(context.noBackupFilesDir, "videobrowser-python-$PYTHON_VERSION")
            val marker = File(runtimeRoot, RUNTIME_MARKER)
            val expectedMarker = "$PYTHON_VERSION:${runtimeZip.length()}"
            if (!marker.isFile || marker.readText() != expectedMarker || !File(runtimeRoot, "usr").isDirectory) {
                runtimeRoot.deleteRecursively()
                runtimeRoot.mkdirs()
                extractRuntimeZip(runtimeZip, runtimeRoot)
                marker.writeText(expectedMarker)
            }
            val usr = File(runtimeRoot, "usr")
            if (!File(usr, "lib").isDirectory || !File(usr, "lib/python3.14").isDirectory) {
                throw IOException("محتويات Python runtime غير مكتملة بعد فك الضغط")
            }
            val versionProbe = runPython(context, listOf("-c", "import sys,curl_cffi; print('PY='+sys.version.split()[0]); print('CURL='+curl_cffi.__version__)"), 30L)
            if (versionProbe.exitCode != 0) throw IOException(versionProbe.stderr.ifBlank { versionProbe.stdout }.ifBlank { "تعذر تحميل curl_cffi من Python runtime" })
            val pythonLine = versionProbe.stdout.lineSequence().firstOrNull { it.startsWith("PY=") }.orEmpty()
            val curlLine = versionProbe.stdout.lineSequence().firstOrNull { it.startsWith("CURL=") }.orEmpty()
            if (!pythonLine.contains(PYTHON_VERSION) || !curlLine.contains(CURL_CFFI_VERSION)) {
                throw IOException("نسخة Python/curl_cffi في الحزمة لا تطابق النسخ المطلوبة ($pythonLine, $curlLine)")
            }
            applyXHamsterExtractorPatch(context, ytDlpAsset)
            cachedYtDlpVersion = currentVersion(context)
            runtimeSummary = "yt-dlp $cachedYtDlpVersion / Python $PYTHON_VERSION / curl_cffi $CURL_CFFI_VERSION / xHamster title+source patch active"
            initialized = true
            initError = ""
        }
    }

    /** Extract Unix symlinks as symlinks; Termux's Python runtime relies on them. */
    private fun extractRuntimeZip(zipFile: File, targetDirectory: File) {
        var total = 0L
        var entriesSeen = 0
        ZipFile(zipFile).use { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entriesSeen++
                if (entriesSeen > 120_000) throw IOException("Python runtime ZIP contains too many entries")
                val dest = File(targetDirectory, entry.name)
                val rootPath = targetDirectory.canonicalPath + File.separator
                if (dest.canonicalPath != targetDirectory.canonicalPath && !dest.canonicalPath.startsWith(rootPath)) {
                    throw IOException("Unsafe path in Python runtime ZIP")
                }
                if (entry.isDirectory) {
                    dest.mkdirs()
                } else if (entry.isUnixSymlink) {
                    dest.parentFile?.mkdirs()
                    val target = archive.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (target.isBlank() || target.contains('\u0000')) throw IOException("Invalid symlink in Python runtime ZIP")
                    try { dest.delete() } catch (_: Exception) {}
                    Os.symlink(target, dest.absolutePath)
                } else {
                    total += entry.size.coerceAtLeast(0L)
                    if (total > 800L * 1024L * 1024L) throw IOException("Python runtime ZIP exceeds the extraction size limit")
                    dest.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output, 128 * 1024) }
                    }
                }
            }
        }
    }

    private fun appNativeLibraryDir(context: Context): File {
        val path = context.applicationInfo.nativeLibraryDir
        if (path.isBlank()) throw IOException("Android لم يحدد مجلد المكتبات الأصلية للتطبيق")
        return File(path)
    }

    private fun runtimeRoot(context: Context) = File(context.noBackupFilesDir, "videobrowser-python-$PYTHON_VERSION")

    private fun runPython(context: Context, arguments: List<String>, timeoutSeconds: Long): ProcessResult {
        val nativeDir = appNativeLibraryDir(context)
        val python = File(nativeDir, "libpython.so")
        val runtime = runtimeRoot(context)
        val usr = File(runtime, "usr")
        if (!python.exists() || !usr.exists()) throw IOException("Python runtime is unavailable")
        val command = ArrayList<String>(arguments.size + 1)
        command.add(python.absolutePath)
        command.addAll(arguments)
        return runProcess(context, command, usr, timeoutSeconds)
    }

    private fun xhamsterPatchMarker(script: File): File = File(script.parentFile, ".vb-xhamster-patch")

    /**
     * Apply the same narrow patch to the packaged yt-dlp zipapp and to any copy
     * downloaded later by yt-dlp's in-app updater. The patcher is shipped as an
     * asset and runs under the already verified embedded Python runtime.
     */
    private fun applyXHamsterExtractorPatch(context: Context, script: File) {
        val marker = xhamsterPatchMarker(script)
        if (marker.isFile && runCatching { marker.readText() == XHAMSTER_PATCH_VERSION }.getOrDefault(false)) return
        val patcher = File(context.cacheDir, XHAMSTER_PATCH_ASSET)
        context.assets.open(XHAMSTER_PATCH_ASSET).use { input ->
            FileOutputStream(patcher).use { output -> input.copyTo(output) }
        }
        try {
            val result = runPython(context, listOf(patcher.absolutePath, script.absolutePath), 45L)
            if (result.exitCode != 0) {
                throw IOException(result.stderr.ifBlank { result.stdout }.ifBlank { "تعذر تطبيق إصلاح xHamster على yt-dlp" })
            }
            marker.writeText(XHAMSTER_PATCH_VERSION)
        } finally {
            patcher.delete()
        }
    }

    private fun summarizeExtractorWarnings(stderr: String): String = stderr.lineSequence()
        .map { it.trim() }
        .filter { it.contains("WARNING", true) || it.contains("Skipping format", true) || it.contains("Unknown algorithm", true) }
        .take(5)
        .joinToString(" | ") { safeError(IOException(it)) }
        .take(480)

    private fun runYtDlp(
        context: Context,
        arguments: List<String>,
        timeoutSeconds: Long,
        shouldContinue: () -> Boolean = { true }
    ): ProcessResult {
        val script = File(context.filesDir, "yt-dlp/yt-dlp")
        if (!script.isFile) throw IOException("ملف yt-dlp غير موجود")
        val command = ArrayList<String>(arguments.size + 2)
        command.add(File(appNativeLibraryDir(context), "libpython.so").absolutePath)
        command.add(script.absolutePath)
        command.addAll(arguments)
        return runProcess(context, command, File(runtimeRoot(context), "usr"), timeoutSeconds, shouldContinue)
    }

    private fun runProcess(
        context: Context,
        command: List<String>,
        usr: File,
        timeoutSeconds: Long,
        shouldContinue: () -> Boolean = { true }
    ): ProcessResult {
        val nativeDir = appNativeLibraryDir(context)
        val processBuilder = ProcessBuilder(command)
        val env = processBuilder.environment()
        val usrLib = File(usr, "lib")
        env["PYTHONHOME"] = usr.absolutePath
        env["LD_LIBRARY_PATH"] = listOf(usrLib.absolutePath, nativeDir.absolutePath).joinToString(":")
        env["PATH"] = listOf(File(usr, "bin").absolutePath, nativeDir.absolutePath, "/system/bin").joinToString(":")
        env["SSL_CERT_FILE"] = File(usr, "etc/tls/cert.pem").absolutePath
        env["HOME"] = File(context.filesDir, "python-home").apply { mkdirs() }.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        env["PYTHONNOUSERSITE"] = "1"
        val process = try { processBuilder.start() } catch (e: Exception) { throw IOException("تعذر تشغيل Python runtime: ${e.message}", e) }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outError = AtomicReference<Throwable?>(null)
        val errError = AtomicReference<Throwable?>(null)
        val outFuture = ioWorkers.submit {
            try { process.inputStream.use { it.copyTo(stdout, 64 * 1024) } } catch (e: Throwable) { outError.set(e) }
        }
        val errFuture = ioWorkers.submit {
            try { process.errorStream.use { it.copyTo(stderr, 64 * 1024) } } catch (e: Throwable) { errError.set(e) }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            while (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
                if (!shouldContinue()) throw IOException("تم إيقاف عملية yt-dlp")
                if (System.nanoTime() > deadline) throw IOException("انتهت مهلة yt-dlp بعد ${timeoutSeconds} ثانية")
            }
            outFuture.get(10, TimeUnit.SECONDS)
            errFuture.get(10, TimeUnit.SECONDS)
            outError.get()?.let { throw IOException("تعذر قراءة مخرجات Python", it) }
            errError.get()?.let { throw IOException("تعذر قراءة أخطاء Python", it) }
            return ProcessResult(
                process.exitValue(),
                stdout.toString(Charsets.UTF_8.name()),
                stderr.toString(Charsets.UTF_8.name())
            )
        } catch (e: Exception) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            throw e
        }
    }

    private fun currentVersion(context: Context): String {
        return try {
            val result = runYtDlp(context, listOf("--version"), 20L)
            if (result.exitCode == 0) result.stdout.trim().lineSequence().firstOrNull().orEmpty().ifBlank { BUNDLED_YTDLP_VERSION }
            else BUNDLED_YTDLP_VERSION
        } catch (_: Exception) { BUNDLED_YTDLP_VERSION }
    }

    private fun pageHeaders(pageUrl: String, requestHeaders: Map<String, String>): Map<String, String> {
        val safe = LinkedHashMap(CaptureHeaders.safeRequestHeaders(requestHeaders))
        val host = try { URI(pageUrl).host?.lowercase(Locale.ROOT).orEmpty() } catch (_: Exception) { "" }
        if (isPornhub(pageUrl)) {
            // Let curl_cffi choose the User-Agent matching its Chrome TLS profile.
            // Keeping WebView's Android UA here would create a desktop-TLS/mobile-UA mismatch.
            safe.remove("User-Agent")
            val pageOrigin = try {
                val uri = URI(pageUrl)
                val root = listOf("pornhub.com", "pornhubpremium.com", "pornhub.net", "pornhub.org")
                    .firstOrNull { host == it || host.endsWith(".$it") }
                    ?: host
                "${uri.scheme ?: "https"}://www.$root"
            } catch (_: Exception) { "https://www.pornhub.com" }
            // Match the canonical site-root headers used by yt-dlp's PornHub extractor.
            safe["Referer"] = "$pageOrigin/"
            safe["Origin"] = pageOrigin
        } else if (isGayPorn(pageUrl)) {
            // GayPorn serves higher renditions only when the request looks like a
            // real browser page navigation: use the canonical page URL as Referer
            // and keep the WebView User-Agent so the CDN does not throttle to 720p.
            safe.remove("User-Agent")
            val pageOrigin = try {
                val uri = URI(pageUrl)
                "${uri.scheme ?: "https"}://${uri.host ?: "www.gayporn.com"}"
            } catch (_: Exception) { "https://www.gayporn.com" }
            safe["Referer"] = pageOrigin
            safe["Origin"] = pageOrigin
        } else if (host.isNotBlank()) {
            safe.putIfAbsent("Referer", pageUrl)
            safe.putIfAbsent("Origin", try { val u = URI(pageUrl); "${u.scheme}://${u.host}" } catch (_: Exception) { "" })
        }
        return safe.filterValues { it.isNotBlank() }
    }

    private fun addHeaders(arguments: MutableList<String>, headers: Map<String, String>) {
        val allowed = setOf("user-agent", "referer", "origin", "accept", "accept-language")
        headers.forEach { (name, value) ->
            if (name.lowercase(Locale.ROOT) !in allowed) return@forEach
            if (value.isBlank() || value.contains('\n') || value.contains('\r')) return@forEach
            arguments.addAll(listOf("--add-headers", "$name: $value"))
        }
    }

    /** Netscape cookie file scoped to the page host; cookie values are never put in task JSON/logs. */
    private fun createCookieFile(context: Context, pageUrl: String): File? {
        val host = try { URI(pageUrl).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null } ?: return null
        val raw = try { CookieManager.getInstance().getCookie(pageUrl) } catch (_: Exception) { null } ?: return null
        val pairs = raw.split(';').mapNotNull { item ->
            val part = item.trim()
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.filter { (name, value) ->
            name.isNotBlank() && name.none { it == '\t' || it == '\n' || it == '\r' } &&
                value.none { it == '\t' || it == '\n' || it == '\r' }
        }
        if (pairs.isEmpty()) return null
        val dir = File(context.cacheDir, "yt-dlp-cookies").apply { mkdirs() }
        val file = File.createTempFile("page-", ".cookies", dir)
        file.writeText(buildString {
            append("# Netscape HTTP Cookie File\n")
            pairs.forEach { (name, value) -> append(host).append("\tFALSE\t/\t").append(if (pageUrl.startsWith("https://", true)) "TRUE" else "FALSE").append("\t0\t").append(name).append('\t').append(value).append('\n') }
        })
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        return file
    }

    private fun isPornhub(url: String): Boolean {
        val host = try { URI(url).host?.lowercase(Locale.ROOT).orEmpty() } catch (_: Exception) { "" }
        return listOf("pornhub.com", "pornhubpremium.com", "pornhub.net", "pornhub.org")
            .any { host == it || host.endsWith(".$it") }
    }

    private fun isGayPorn(url: String): Boolean {
        val host = try { URI(url).host?.lowercase(Locale.ROOT).orEmpty() } catch (_: Exception) { "" }
        return listOf("gayporn.com").any { host == it || host.endsWith(".$it") }
    }

    private fun extractJson(raw: String): JSONObject {
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        if (first < 0 || last <= first) throw IOException("yt-dlp لم يُرجع JSON صالحًا: ${raw.take(180)}")
        return try { JSONObject(raw.substring(first, last + 1)) } catch (e: Exception) { throw IOException("تعذر قراءة JSON من yt-dlp", e) }
    }

    /** Prefer the extractor's page thumbnail, falling back to its largest declared rendition. */
    private fun thumbnailUrl(info: JSONObject): String {
        // A thumbnail URL is expected to contain words such as "thumb", "poster"
        // or "preview". Do not apply media-URL preview filters to page artwork.
        fun usable(value: String): Boolean =
            (value.startsWith("https://", true) || value.startsWith("http://", true)) &&
                !Formats.isAdUrl(value)
        val direct = info.optStringClean("thumbnail")
        if (usable(direct)) return direct
        val thumbnails = info.optJSONArray("thumbnails") ?: return ""
        return (0 until thumbnails.length()).mapNotNull { index ->
            val item = thumbnails.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optStringClean("url")
            if (!usable(url)) return@mapNotNull null
            val width = item.optInt("width", 0).coerceAtLeast(0)
            val height = item.optInt("height", 0).coerceAtLeast(0)
            Triple(url, width.toLong() * height.toLong(), index)
        }.maxWithOrNull(compareBy<Triple<String, Long, Int>> { it.second }.thenBy { it.third })?.first.orEmpty()
    }

    private fun mapFormats(info: JSONObject, baseHeaders: Map<String, String>, pageUrl: String): List<Format> {
        val out = LinkedHashMap<String, Format>()
        val safeBaseHeaders = CaptureHeaders.safeRequestHeaders(baseHeaders)
        val formats = info.optJSONArray("formats") ?: JSONArray()
        val hlsMasters = LinkedHashSet<String>()
        val dashVideoOnly = ArrayList<JSONObject>()
        val dashAudioOnly = ArrayList<JSONObject>()
        for (index in 0 until formats.length()) {
            val item = formats.optJSONObject(index) ?: continue
            val url = item.optStringClean("url").takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) } ?: continue
            if (Formats.isAdUrl(url) || Formats.isPreviewUrl(url)) continue
            val ext = item.optStringClean("ext").lowercase(Locale.ROOT).ifBlank { extension(url) }
            val manifest = item.optStringClean("manifest_url")
            val protocol = item.optStringClean("protocol").lowercase(Locale.ROOT)
            val dash = ext == "mpd" || extension(manifest) == "mpd" || protocol.contains("dash")
            val hls = ext == "m3u8" || extension(manifest) == "m3u8" || protocol.contains("m3u8") || protocol.contains("m3u")
            val vcodec = item.optStringClean("vcodec")
            val acodec = item.optStringClean("acodec")
            val formatId = item.optStringClean("format_id")
            if (dash && formatId.isNotBlank()) {
                if (vcodec.equals("none", true) && !acodec.equals("none", true)) {
                    dashAudioOnly.add(item)
                    continue
                }
                if (!vcodec.equals("none", true) && acodec.equals("none", true)) {
                    dashVideoOnly.add(item)
                    continue
                }
            }
            if (vcodec.equals("none", true)) continue
            if (acodec.equals("none", true)) {
                if (hls && extension(manifest) == "m3u8" && !Formats.isAdUrl(manifest) && !Formats.isPreviewUrl(manifest)) hlsMasters.add(manifest)
                continue
            }
            if (!hls && !dash && ext !in VIDEO_EXTENSIONS) continue
            val urlExt = extension(url)
            if (urlExt in setOf("vtt", "json") || (!dash && urlExt in setOf("m4s", "ts", "mpd"))) continue

            val height = item.optInt("height", 0).takeIf { it in 144..4320 }
                ?: Formats.qualityFromLabel(item.optStringClean("format_note"))
                ?: Formats.qualityFromLabel(item.optStringClean("format"))
                ?: Formats.qualityFromLabel(item.optStringClean("format_id"))
                ?: 0
            val width = item.optInt("width", 0).takeIf { it > 0 } ?: if (height > 0) height * 16 / 9 else 0
            val label = when {
                height > 0 -> Formats.qualityLabel(width, height)
                item.optStringClean("format_note").isNotBlank() -> item.optStringClean("format_note")
                item.optStringClean("format").isNotBlank() -> item.optStringClean("format")
                formatId.isNotBlank() -> formatId
                else -> "الجودة الأصلية"
            }
            val codec = listOf(vcodec, acodec).filter { it.isNotBlank() }.joinToString(" / ")
            val bitrateKbps = item.optDouble("tbr", 0.0).coerceAtLeast(0.0)
            val bandwidth = (bitrateKbps * 1000.0).toLong().coerceAtLeast(0L)
            val duration = info.optDouble("duration", 0.0).takeIf { it > 0.0 && it <= 86_400.0 } ?: -1.0
            val declaredBytes = item.optLong("filesize", -1L).takeIf { it > 0L }
                ?: item.optLong("filesize_approx", -1L).takeIf { it > 0L }
            val bytes = declaredBytes ?: YtDlpFormatSize.estimateBytes(bitrateKbps, duration)
            val fps = item.optDouble("fps", 0.0).coerceAtLeast(0.0)
            val details = buildList {
                add("yt-dlp / curl_cffi")
                if (formatId.isNotBlank()) add(formatId)
                if (width > 0 && height > 0) add("${width}x${height}")
                if (codec.isNotBlank()) add(codec)
                if (fps > 0) add("${fps.toInt()} fps")
                if (bandwidth > 0) add(String.format(Locale.US, "%.2f Mbps", bandwidth / 1_000_000.0))
            }.joinToString(" • ")
            val itemHeaders = item.optJSONObject("http_headers")?.toHeaderMap().orEmpty()
            val headers = CaptureHeaders.merge(safeBaseHeaders, CaptureHeaders.safeRequestHeaders(itemHeaders))
            val format = Format(
                label = label,
                details = details,
                url = url,
                kind = if (hls) "hls" else "direct",
                ext = if (hls || (dash && ext !in VIDEO_EXTENSIONS)) "mp4" else ext,
                fromMaster = hls,
                sizeText = if (bytes > 0L) "≈ ${Util.fmtSize(bytes)}" else "",
                isAudio = false,
                durSec = duration.takeIf { it > 0.0 } ?: 0.0,
                bytes = bytes,
                requestHeaders = headers,
                codec = codec,
                frameRate = fps,
                bandwidth = bandwidth,
                representationId = formatId,
                ytDlpPageUrl = pageUrl,
                ytDlpFormatId = formatId
            )
            // A single master/manifest URL may represent several independent yt-dlp
            // format IDs (for example 240p/480p/720p). URL-only deduplication silently
            // collapsed those choices to whichever entry appeared first.
            val identity = if (formatId.isNotBlank()) "yt:$formatId" else "url:$url"
            val old = out[identity]
            if (old == null || formatScore(format) > formatScore(old)) out[identity] = format
        }

        // DASH manifests often expose separate video-only and audio-only tracks.
        // Keep a yt-dlp format selector (videoId+audioId) so the same page extractor
        // downloads and muxes both tracks; never offer a silent video-only rendition.
        val bestDashAudio = dashAudioOnly.maxWithOrNull(
            compareBy<JSONObject> { audio ->
                audio.optDouble("abr", 0.0).takeIf { it > 0.0 }
                    ?: audio.optDouble("tbr", 0.0).coerceAtLeast(0.0)
            }.thenBy { audio ->
                audio.optLong("filesize", -1L).takeIf { it > 0L }
                    ?: audio.optLong("filesize_approx", -1L).coerceAtLeast(0L)
            }
        )
        if (bestDashAudio != null) {
            val audioId = bestDashAudio.optStringClean("format_id")
            val audioCodec = bestDashAudio.optStringClean("acodec")
            for (video in dashVideoOnly) {
                val videoId = video.optStringClean("format_id")
                val videoUrl = video.optStringClean("url")
                if (videoId.isBlank() || videoUrl.isBlank() || Formats.isAdUrl(videoUrl) || Formats.isPreviewUrl(videoUrl)) continue
                val height = video.optInt("height", 0).takeIf { it in 144..4320 }
                    ?: Formats.qualityFromLabel(video.optStringClean("format_note"))
                    ?: Formats.qualityFromLabel(video.optStringClean("format"))
                    ?: 0
                val width = video.optInt("width", 0).takeIf { it > 0 } ?: if (height > 0) height * 16 / 9 else 0
                val videoExt = video.optStringClean("video_ext").lowercase(Locale.ROOT)
                    .takeIf { it in VIDEO_EXTENSIONS }
                    ?: video.optStringClean("ext").lowercase(Locale.ROOT).takeIf { it in VIDEO_EXTENSIONS }
                    ?: "mp4"
                val selector = "$videoId+$audioId"
                val label = when {
                    height > 0 -> Formats.qualityLabel(width, height)
                    video.optStringClean("format_note").isNotBlank() -> video.optStringClean("format_note")
                    else -> videoId
                }
                val videoCodec = video.optStringClean("vcodec")
                val codec = listOf(videoCodec, audioCodec).filter { it.isNotBlank() }.joinToString(" / ")
                val videoBitrate = video.optDouble("tbr", 0.0).coerceAtLeast(0.0)
                val audioBitrate = bestDashAudio.optDouble("abr", 0.0).takeIf { it > 0.0 }
                    ?: bestDashAudio.optDouble("tbr", 0.0).coerceAtLeast(0.0)
                val bandwidth = ((videoBitrate + audioBitrate) * 1000.0).toLong().coerceAtLeast(0L)
                val videoBytes = video.optLong("filesize", -1L).takeIf { it > 0L }
                    ?: video.optLong("filesize_approx", -1L).takeIf { it > 0L } ?: -1L
                val audioBytes = bestDashAudio.optLong("filesize", -1L).takeIf { it > 0L }
                    ?: bestDashAudio.optLong("filesize_approx", -1L).takeIf { it > 0L } ?: -1L
                val duration = info.optDouble("duration", 0.0).takeIf { it > 0.0 && it <= 86_400.0 } ?: -1.0
                val bytes = when {
                    videoBytes > 0L && audioBytes > 0L -> videoBytes + audioBytes
                    videoBytes > 0L -> videoBytes + YtDlpFormatSize.estimateBytes(audioBitrate, duration).coerceAtLeast(0L)
                    audioBytes > 0L -> YtDlpFormatSize.estimateBytes(videoBitrate, duration).coerceAtLeast(0L) + audioBytes
                    else -> YtDlpFormatSize.estimateBytes(videoBitrate + audioBitrate, duration)
                }
                val fps = video.optDouble("fps", 0.0).coerceAtLeast(0.0)
                val details = buildList {
                    add("yt-dlp / curl_cffi / DASH")
                    add(selector)
                    if (width > 0 && height > 0) add("${width}x${height}")
                    if (codec.isNotBlank()) add(codec)
                    if (fps > 0.0) add("${fps.toInt()} fps")
                    if (bandwidth > 0L) add(String.format(Locale.US, "%.2f Mbps", bandwidth / 1_000_000.0))
                }.joinToString(" • ")
                val videoHeaders = CaptureHeaders.safeRequestHeaders(video.optJSONObject("http_headers")?.toHeaderMap().orEmpty())
                val audioHeaders = CaptureHeaders.safeRequestHeaders(bestDashAudio.optJSONObject("http_headers")?.toHeaderMap().orEmpty())
                val headers = CaptureHeaders.merge(CaptureHeaders.merge(safeBaseHeaders, videoHeaders), audioHeaders)
                val format = Format(
                    label = label,
                    details = details,
                    url = videoUrl,
                    kind = "direct",
                    ext = videoExt,
                    sizeText = if (bytes > 0L) "≈ ${Util.fmtSize(bytes)}" else "",
                    isAudio = false,
                    durSec = duration.takeIf { it > 0.0 } ?: 0.0,
                    bytes = bytes,
                    requestHeaders = headers,
                    codec = codec,
                    frameRate = fps,
                    bandwidth = bandwidth,
                    representationId = selector,
                    ytDlpPageUrl = pageUrl,
                    ytDlpFormatId = selector
                )
                val identity = "yt:$selector"
                val old = out[identity]
                if (old == null || formatScore(format) > formatScore(old)) out[identity] = format
            }
        }

        for (masterUrl in hlsMasters) {
            try {
                val expanded = Formats.resolve(
                    MediaItem(url = masterUrl, type = "hls", origin = "dom", requestHeaders = safeBaseHeaders, captureSource = "yt-dlp"),
                    safeBaseHeaders
                )
                for (format in expanded) {
                    if (format.isAudio || Formats.isAudio(format.ext) || Formats.isAdUrl(format.url) || Formats.isPreviewUrl(format.url)) continue
                    val old = out[format.url]
                    if (old == null || formatScore(format) > formatScore(old)) out[format.url] = format
                }
            } catch (_: Exception) {}
        }

        if (out.isEmpty()) {
            val url = info.optStringClean("url").takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            if (url != null && !Formats.isAdUrl(url) && !Formats.isPreviewUrl(url)) {
                val ext = info.optStringClean("ext").lowercase(Locale.ROOT).ifBlank { extension(url) }
                if (ext in VIDEO_EXTENSIONS && ext != "mpd") {
                    val height = info.optInt("height", 0).takeIf { it in 144..4320 } ?: 0
                    val width = info.optInt("width", 0).takeIf { it > 0 } ?: if (height > 0) height * 16 / 9 else 0
                    val formatId = info.optStringClean("format_id")
                    out[url] = Format(
                        label = if (height > 0) Formats.qualityLabel(width, height) else "الجودة الأصلية",
                        details = "yt-dlp / ${info.optStringClean("extractor")}",
                        url = url,
                        kind = "direct",
                        ext = ext,
                        bytes = info.optLong("filesize", -1L).takeIf { it > 0L } ?: info.optLong("filesize_approx", -1L),
                        durSec = info.optDouble("duration", 0.0).takeIf { it > 0.0 } ?: 0.0,
                        requestHeaders = CaptureHeaders.merge(safeBaseHeaders, info.optJSONObject("http_headers")?.toHeaderMap().orEmpty().let { CaptureHeaders.safeRequestHeaders(it) }),
                        representationId = formatId,
                        ytDlpPageUrl = pageUrl,
                        ytDlpFormatId = formatId
                    )
                }
            }
        }
        return out.values.sortedWith(
            compareByDescending<Format> { Formats.qualityFromLabel(it.label) ?: 0 }
                .thenByDescending { it.frameRate }
                .thenByDescending { it.bandwidth }
                .thenByDescending { it.bytes }
                .thenBy { it.codec }
        )
    }

    private fun JSONObject.toHeaderMap(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!isNull(key)) result[key] = optString(key)
        }
        return result
    }

    private fun JSONObject.optStringClean(key: String): String = if (isNull(key)) "" else optString(key).takeUnless { it == "null" }.orEmpty()

    private fun formatScore(f: Format): Int = listOf(
        f.codec.isNotBlank(), f.frameRate > 0.0, f.bandwidth > 0L, f.bytes > 0L,
        f.requestHeaders.isNotEmpty(), f.ytDlpFormatId.isNotBlank()
    ).count { it }

    private fun extension(raw: String): String = try { URI(raw).path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT) } catch (_: Exception) { "" }

    private fun safeError(error: Throwable): String = (error.message ?: error.javaClass.simpleName)
        .replace(Regex("https?://\\S+"), "[رابط مخفي]")
        .replace(Regex("(?i)(cookie|authorization|proxy-authorization)\\s*[:=]\\s*[^\\r\\n]+"), "\$1: [محجوب]")
        .replace(Regex("(?i)(--cookies\\s+)[^\\s]+"), "$1[ملف محجوب]")
        .replace(Regex("[?&](token|sig|signature|auth|key|expires|session)=[^&\\s]+", RegexOption.IGNORE_CASE), "[معامل مخفي]")
        .take(800)
        .ifBlank { error.javaClass.simpleName }

    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp")
}
