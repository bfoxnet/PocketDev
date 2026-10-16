package com.bfoxnet.pocketdev

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Ultra-fast search engine for in-file and across-project code search.
 *
 * Performance Architecture:
 * 1. **Boyer-Moore-Horspool (BMH)** bad-character skip table for literal and
 *    case-insensitive searches: jumps up to `pattern.length` characters per comparison
 *    without allocating per-line strings until a hit is confirmed.
 * 2. **Multi-Core Work Stealing / Fixed Pool**: Scans project files concurrently across
 *    all available CPU cores.
 * 3. **Binary & Build-Artifact Filtering**: Detects null bytes in the header of a stream
 *    to skip binary files (`png`, `jar`, `so`, `dex`, `ttf`) in microseconds.
 */
object ProjectSearchEngine {

    data class SearchMatch(
        val fileName: String,
        val relativePath: String,
        val lineNumber: Int,      // 1-based line number
        val columnNumber: Int,    // 0-based column index
        val matchLength: Int,
        val linePreview: String,
        val localFile: File? = null,
        val safUri: Uri? = null
    )

    data class SearchSummary(
        val matches: List<SearchMatch>,
        val filesScanned: Int,
        val elapsedMillis: Double
    )

    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "ttf", "otf", "woff", "woff2",
        "zip", "jar", "aar", "apk", "aab", "tar", "gz", "xz", "7z", "so", "dll", "dylib",
        "class", "dex", "pdf", "iso", "qcow2", "img", "mp3", "mp4", "wav", "ogg"
    )

    /**
     * Boyer-Moore-Horspool matcher over 16-bit chars (with ASCII 256-entry fast skip table).
     */
    class BMHMatcher(patternStr: String, private val caseSensitive: Boolean) {
        private val needle: CharArray =
            (if (caseSensitive) patternStr else patternStr.lowercase()).toCharArray()
        val length: Int = needle.size
        private val skipTable = IntArray(256) { length.coerceAtLeast(1) }

        init {
            if (length > 1) {
                val last = length - 1
                for (i in 0 until last) {
                    val code = needle[i].code and 0xFF
                    skipTable[code] = last - i
                }
            }
        }

        fun indexOf(haystack: CharSequence, startIndex: Int = 0): Int {
            val n = haystack.length
            val m = length
            if (m == 0) return startIndex
            if (n < m || startIndex > n - m) return -1

            val last = m - 1
            val lastNeedle = needle[last]
            var i = startIndex + last
            while (i < n) {
                val ch = norm(haystack[i])
                if (ch == lastNeedle) {
                    var j = last - 1
                    var k = i - 1
                    while (j >= 0 && norm(haystack[k]) == needle[j]) {
                        j--
                        k--
                    }
                    if (j < 0) return i - last
                }
                val step = skipTable[ch.code and 0xFF]
                i += step
            }
            return -1
        }

        private fun norm(c: Char): Char =
            if (caseSensitive) c else c.lowercaseChar()
    }

    /**
     * Searches across all files in the active project in parallel.
     */
    fun searchAcrossProject(
        context: Context,
        query: String,
        caseSensitive: Boolean,
        useRegex: Boolean,
        fileFilterGlob: String = "",
        openTabs: List<OpenedFile> = emptyList(),
        maxMatches: Int = 500
    ): SearchSummary {
        val startNs = System.nanoTime()
        if (query.isEmpty()) {
            return SearchSummary(emptyList(), 0, 0.0)
        }

        val regexPattern: Pattern? = if (useRegex) {
            try {
                val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
                Pattern.compile(query, flags)
            } catch (e: Exception) {
                return SearchSummary(emptyList(), 0, 0.0)
            }
        } else {
            null
        }
        val bmhMatcher = if (!useRegex) BMHMatcher(query, caseSensitive) else null

        val extFilters = fileFilterGlob
            .split(',', ';', ' ')
            .map { it.trim().removePrefix("*.").removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        val allNodes = ProjectManager.collectAllProjectFiles(context)
            .filter { node ->
                val ext = node.name.substringAfterLast('.', "").lowercase()
                if (ext in BINARY_EXTENSIONS) return@filter false
                if (extFilters.isNotEmpty() && ext !in extFilters) return@filter false
                true
            }

        // Map open modified tabs so unsaved in-memory buffers are searched accurately
        val openByPath = HashMap<String, String>()
        val openByUri = HashMap<Uri, String>()
        for (tab in openTabs) {
            tab.localFile?.absolutePath?.let { openByPath[it] = tab.content }
            tab.uri?.let { openByUri[it] = tab.content }
        }

        val results = Collections.synchronizedList(ArrayList<SearchMatch>())
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            val tasks = allNodes.map { node ->
                Callable {
                    if (results.size >= maxMatches) return@Callable
                    val text = readNodeText(context, node, openByPath, openByUri) ?: return@Callable
                    scanText(
                        text = text,
                        fileName = node.name,
                        relativePath = node.relativePath,
                        localFile = node.localFile,
                        safUri = node.safDocUri,
                        bmh = bmhMatcher,
                        regex = regexPattern,
                        out = results,
                        maxMatches = maxMatches
                    )
                }
            }
            pool.invokeAll(tasks)
        } catch (ignored: Exception) {
        } finally {
            pool.shutdownNow()
        }

        val sorted = results.sortedWith(
            compareBy<SearchMatch>({ it.relativePath.lowercase() }, { it.lineNumber }, { it.columnNumber })
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        return SearchSummary(sorted, allNodes.size, elapsedMs)
    }

    private fun readNodeText(
        context: Context,
        node: ProjectManager.FileNode,
        openByPath: Map<String, String>,
        openByUri: Map<Uri, String>
    ): String? {
        node.localFile?.let { file ->
            openByPath[file.absolutePath]?.let { return it }
            return try {
                val bytes = file.readBytes()
                if (isBinaryBuffer(bytes)) null else String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
        node.safDocUri?.let { uri ->
            openByUri[uri]?.let { return it }
            return try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (isBinaryBuffer(bytes)) null else String(bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private fun isBinaryBuffer(bytes: ByteArray): Boolean {
        val checkLen = bytes.size.coerceAtMost(512)
        for (i in 0 until checkLen) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    private fun scanText(
        text: String,
        fileName: String,
        relativePath: String,
        localFile: File?,
        safUri: Uri?,
        bmh: BMHMatcher?,
        regex: Pattern?,
        out: MutableList<SearchMatch>,
        maxMatches: Int
    ) {
        if (bmh != null) {
            // Fast fail on whole file using BMH before splitting or tracking lines!
            val firstHit = bmh.indexOf(text, 0)
            if (firstHit < 0) return
        }

        var lineStart = 0
        var lineNumber = 1
        val len = text.length

        while (lineStart <= len && out.size < maxMatches) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = len
            val cleanEnd = if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            val lineSeq = text.subSequence(lineStart, cleanEnd)

            if (bmh != null) {
                val hitCol = bmh.indexOf(lineSeq, 0)
                if (hitCol >= 0) {
                    out.add(
                        SearchMatch(
                            fileName = fileName,
                            relativePath = relativePath,
                            lineNumber = lineNumber,
                            columnNumber = hitCol,
                            matchLength = bmh.length,
                            linePreview = lineSeq.toString().trim().take(160),
                            localFile = localFile,
                            safUri = safUri
                        )
                    )
                }
            } else if (regex != null) {
                val m = regex.matcher(lineSeq)
                if (m.find()) {
                    out.add(
                        SearchMatch(
                            fileName = fileName,
                            relativePath = relativePath,
                            lineNumber = lineNumber,
                            columnNumber = m.start(),
                            matchLength = (m.end() - m.start()).coerceAtLeast(1),
                            linePreview = lineSeq.toString().trim().take(160),
                            localFile = localFile,
                            safUri = safUri
                        )
                    )
                }
            }

            if (lineEnd == len) break
            lineStart = lineEnd + 1
            lineNumber++
        }
    }
}
