package com.example.profilebrowser

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Saves files that DownloadManager can't fetch (blob: / data: URLs, e.g. the zip files
 * claude.ai generates in the page) into Downloads/<ProfileName>/.
 */
object FileSaver {

    class Sink(
        val out: OutputStream,
        val uri: Uri?,
        val file: File?,
        val name: String,
        val mime: String
    )

    /** Makes a safe file name and adds an extension from the MIME type if it has none. */
    fun cleanName(raw: String?, mime: String?): String {
        var n = (raw ?: "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trimStart('.')
        if (n.isEmpty()) n = "download"
        if (!n.contains('.')) {
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (ext != null) n += ".$ext"
        }
        return n.take(150)
    }

    /** If the browser gave a useless MIME type, guess it from the file extension. */
    fun resolveMime(name: String, mime: String?): String {
        val m = mime?.substringBefore(';')?.trim().orEmpty()
        if (m.isNotEmpty() && m != "application/octet-stream") return m
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    @Throws(IOException::class)
    fun open(ctx: Context, folder: String, name: String, mime: String): Sink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create file")
            val out = ctx.contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open file")
            return Sink(out, uri, null, name, mime)
        }

        // Android 8 / 9: write directly (WRITE_EXTERNAL_STORAGE is requested before this).
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create folder")
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return Sink(f.outputStream(), null, f, f.name, mime)
    }

    /** Closes the file and makes it visible. [done] gets a content:// Uri that can be shared. */
    fun finish(ctx: Context, sink: Sink, done: (Uri?) -> Unit) {
        try { sink.out.flush(); sink.out.close() } catch (_: Exception) {}
        if (sink.uri != null) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            ctx.contentResolver.update(sink.uri, values, null, null)
            done(sink.uri)
        } else if (sink.file != null) {
            MediaScannerConnection.scanFile(
                ctx, arrayOf(sink.file.absolutePath), arrayOf(sink.mime)
            ) { _, uri -> done(uri) }
        } else done(null)
    }

    fun abort(ctx: Context, sink: Sink) {
        try { sink.out.close() } catch (_: Exception) {}
        try {
            if (sink.uri != null) ctx.contentResolver.delete(sink.uri, null, null)
            sink.file?.delete()
        } catch (_: Exception) {}
    }
}
