package com.techazsure.deskless.report

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Ways to get the finished PDF off the phone. Office Kit has no app API, so it picks the file up from shared storage. */
object Handoff {
    private const val PDF = "application/pdf"
    const val DOCUMENTS_FOLDER = "Documents/DeskLess"

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun viewIntent(context: Context, file: File): Intent {
        val uri = uriOf(context, file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, PDF)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Opens an issue's video clip in the phone's own player. */
    fun playIntent(context: Context, video: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(context, video), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(context: Context, file: File): Intent {
        val uri = uriOf(context, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(PDF)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, "Send report")
    }

    /**
     * Copies a report and its working files into their own folder under shared Documents, so the laptop
     * picks up the whole pack in one go. Returns the folder and how many files reached it, or null.
     */
    fun savePack(context: Context, files: List<File>): Pair<String, Int>? {
        if (files.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val folder = "$DOCUMENTS_FOLDER/${files.first().nameWithoutExtension}"
        val resolver = context.contentResolver
        val saved = files.count { file ->
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                }
                val target = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                val stream = target?.let { resolver.openOutputStream(it) } ?: return@runCatching false
                stream.use { out -> file.inputStream().use { it.copyTo(out) } }
                true
            }.getOrDefault(false)
        }
        return if (saved == 0) null else folder to saved
    }

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> PDF
        "csv" -> "text/csv"
        "ics" -> "text/calendar"
        "json" -> "application/json"
        else -> "text/plain"
    }

    /** Offers a piece of text, such as the owners' task lists, to any messaging app on the phone. */
    fun shareText(text: String, title: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            title,
        )

    /** Copies the PDF into the phone's shared Documents folder and returns where it went, or null on failure. */
    fun saveToDocuments(context: Context, file: File): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, PDF)
                put(MediaStore.MediaColumns.RELATIVE_PATH, DOCUMENTS_FOLDER)
            }
            val resolver = context.contentResolver
            val target = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            val stream = target?.let { resolver.openOutputStream(it) }
            if (stream == null) {
                null
            } else {
                stream.use { out -> file.inputStream().use { it.copyTo(out) } }
                "$DOCUMENTS_FOLDER/${file.name}"
            }
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (dir == null) {
                null
            } else {
                file.copyTo(File(dir, file.name), overwrite = true).absolutePath
            }
        }
    } catch (e: Exception) {
        null
    }
}
