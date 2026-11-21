package com.fable.browser.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

object UploadFiles {
    private const val DIR = "uploads"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    private val UNSAFE_NAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    private val EXTRA_EXTENSION_MIMES = mapOf(
        "json" to "application/json",
        "jsonl" to "application/json",
        "csv" to "text/csv",
        "tsv" to "text/tab-separated-values",
        "md" to "text/markdown",
        "txt" to "text/plain",
        "log" to "text/plain",
        "yaml" to "text/yaml",
        "yml" to "text/yaml",
        "xml" to "text/xml",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "gz" to "application/gzip",
        "xz" to "application/x-xz",
        "tar" to "application/x-tar",
        "apk" to "application/vnd.android.package-archive",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "avif" to "image/avif",
        "heic" to "image/heic",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "glb" to "model/gltf-binary",
        "gltf" to "model/gltf+json",
        "wasm" to "application/wasm"
    )

    /**
     * Converts HTML <input type="file" accept="..."> MIME types and dot-extensions into
     * Android Storage Access Framework MIME filters without greying out valid files whose
     * provider reports incomplete metadata (e.g. application/octet-stream in Downloads).
     */
    fun normalizeMimeTypes(raw: Array<out String?>?): Array<String> {
        if (raw.isNullOrEmpty()) return arrayOf("*/*")
        val map = MimeTypeMap.getSingleton()
        val out = LinkedHashSet<String>()
        var sawCustomOrUnmappedExtension = false
        var sawDotExtension = false

        for (entry in raw) {
            if (entry.isNullOrBlank()) continue
            for (part in entry.split(',')) {
                val t = part.trim().lowercase(Locale.ROOT)
                if (t.isEmpty()) continue
                if (t == "*/*" || t == "*") return arrayOf("*/*")
                if (t.contains('/')) {
                    out += t
                } else {
                    sawDotExtension = true
                    val ext = t.removePrefix(".")
                    val resolved = EXTRA_EXTENSION_MIMES[ext] ?: map?.getMimeTypeFromExtension(ext)
                    if (resolved != null) {
                        out += resolved
                        // Broaden video/audio/image extension requests so sibling containers in Downloads stay selectable
                        when {
                            resolved.startsWith("video/") -> out += "video/*"
                            resolved.startsWith("audio/") -> out += "audio/*"
                            resolved.startsWith("image/") -> out += "image/*"
                        }
                    } else {
                        sawCustomOrUnmappedExtension = true
                    }
                }
            }
        }

        if (out.isEmpty() || sawCustomOrUnmappedExtension) {
            return arrayOf("*/*")
        }
        // Android DownloadsProvider frequently tags .mkv, .webm, .json, .zip, .glb, etc. as application/octet-stream
        if (sawDotExtension || out.any { !it.startsWith("image/") }) {
            out += "application/octet-stream"
        }
        return out.toTypedArray()
    }

    fun copyToUploads(context: Context, uri: Uri): Uri? = runCatching {
        if (uri.scheme == "file") return@runCatching uri
        val name = displayName(context, uri)
            ?.replace(UNSAFE_NAME_CHARS, "_")
            ?.trim()
            ?.take(120)
            ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
            ?: "upload"
        val dir = File(File(context.cacheDir, DIR), System.nanoTime().toString())
        if (!dir.mkdirs() && !dir.isDirectory) return@runCatching null
        val out = File(dir, name)
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
        Uri.fromFile(out)
    }.getOrNull()

    fun cleanOld(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            File(context.cacheDir, DIR).listFiles()?.forEach { dir ->
                if (dir.lastModified() < cutoff) dir.deleteRecursively()
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
