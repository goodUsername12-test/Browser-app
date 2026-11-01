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

    fun normalizeMimeTypes(raw: Array<out String?>?): Array<String> {
        val map = MimeTypeMap.getSingleton()
        val out = LinkedHashSet<String>()
        raw?.forEach { entry ->
            entry?.split(',')?.forEach { part ->
                val t = part.trim().lowercase(Locale.ROOT)
                when {
                    t.isEmpty() -> Unit
                    t.contains('/') -> out += t
                    else -> map.getMimeTypeFromExtension(t.removePrefix("."))?.let { out += it }
                }
            }
        }
        return if (out.isEmpty()) arrayOf("*/*") else out.toTypedArray()
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
