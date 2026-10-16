package com.bfoxnet.pocketdev

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/**
 * Represents an open file tab in the multi-tab editor system.
 * Tracks backing storage ([uri] for SAF/external documents or [localFile] for
 * local project workspace files), current buffered text, dirty state, and
 * per-tab cursor/scroll position so switching tabs is instantaneous.
 */
data class OpenedFile(
    var uri: Uri?,
    var displayName: String,
    var content: String,
    var localFile: File? = null,
    val id: String = UUID.randomUUID().toString()
) {
    /** True when content has changed since the last load or save. */
    var modified: Boolean = false

    /** Saved cursor line (0-based) for this tab. */
    var cursorLine: Int = 0

    /** Saved cursor column (0-based) for this tab. */
    var cursorColumn: Int = 0

    /** Unique key for deduplicating already-open files across tabs. */
    fun matchesTarget(targetUri: Uri?, targetLocal: File?): Boolean {
        if (targetLocal != null && localFile != null) {
            return runCatching { targetLocal.canonicalPath == localFile?.canonicalPath }
                .getOrDefault(targetLocal.absolutePath == localFile?.absolutePath)
        }
        if (targetUri != null && uri != null) {
            return targetUri == uri
        }
        return false
    }
}

/**
 * Maps file extensions onto the TextMate scope names bundled in assets.
 */
fun scopeNameFor(fileName: String): String? =
    EditorController.resolveTextMateSpec(fileName)?.first

/** Fetch a display name from an open-file URI, falling back to the last segment. */
fun Uri.queryDisplayName(resolver: ContentResolver): String {
    try {
        resolver.query(this, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.let { return it }
            }
        }
    } catch (e: Exception) {
        // Fall through to the path-based name below.
    }
    val seg = lastPathSegment ?: return "untitled.txt"
    return seg.substringAfterLast('/').substringAfterLast(':')
        .ifEmpty { "untitled.txt" }
}
