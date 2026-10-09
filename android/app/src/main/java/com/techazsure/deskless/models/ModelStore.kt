package com.techazsure.deskless.models

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where the on-device model files live. They are too big for the APK, so they arrive as ordinary files:
 * the user sends the laptop's `DeskLess-models` folder to the phone (Office Kit file transfer) and imports
 * it here, which copies everything into app storage and joins the split Gemma file.
 */
class ModelStore(private val context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    /** Developer shortcut: files pushed over USB to Android/data/<package>/files/models are used as they are. */
    private val sideloadDir: File? = context.getExternalFilesDir(null)?.let { File(it, "models") }

    fun find(name: String): File? =
        listOfNotNull(File(dir, name), sideloadDir?.let { File(it, name) })
            .firstOrNull { it.isFile && it.length() > 0 }

    val speechReady: Boolean
        get() = SPEECH_FILES.all { find(it) != null }

    val gemma: File?
        get() = find(GEMMA)

    data class Progress(val file: String, val copiedBytes: Long, val totalBytes: Long)

    data class ImportResult(val imported: List<String>, val problems: List<String>)

    /**
     * Copies every model file found under the folder the user picked. Blocking; call it off the main thread.
     * Gemma may come whole or as `.part1` + `.part2` (Office Kit refuses files over 2 GB).
     */
    fun importFrom(tree: Uri, onProgress: (Progress) -> Unit): ImportResult {
        val found = HashMap<String, Pair<Uri, Long>>()
        collect(tree, DocumentsContract.getTreeDocumentId(tree), found, depth = 0)
        return import(found, onProgress)
    }

    /** Same, for files the user picked one by one (a download folder cannot be picked as a whole). */
    fun importFiles(files: List<Uri>, onProgress: (Progress) -> Unit): ImportResult {
        val found = HashMap<String, Pair<Uri, Long>>()
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        for (file in files) {
            context.contentResolver.query(file, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (name in WANTED) found[name] = file to cursor.getLong(1)
                }
            }
        }
        return import(found, onProgress)
    }

    private fun import(found: Map<String, Pair<Uri, Long>>, onProgress: (Progress) -> Unit): ImportResult {
        val imported = ArrayList<String>()
        val problems = ArrayList<String>()

        for (name in SPEECH_FILES) {
            val source = found[name] ?: continue
            copy(name, listOf(source), expectedSha256 = null, onProgress)?.let(problems::add) ?: imported.add(name)
        }

        val whole = found[GEMMA]
        val parts = listOf(found["$GEMMA.part1"], found["$GEMMA.part2"])
        val gemmaSources = when {
            whole != null -> listOf(whole)
            parts.all { it != null } -> parts.filterNotNull()
            parts.any { it != null } -> {
                problems.add("$GEMMA: only one of the two parts was found; both are needed.")
                null
            }
            else -> null
        }
        if (gemmaSources != null) {
            copy(GEMMA, gemmaSources, GEMMA_SHA256, onProgress)?.let(problems::add) ?: imported.add(GEMMA)
        }

        if (imported.isEmpty() && problems.isEmpty()) problems.add("No model files were found there.")
        return ImportResult(imported, problems)
    }

    private fun collect(tree: Uri, documentId: String, into: MutableMap<String, Pair<Uri, Long>>, depth: Int) {
        if (depth > 3) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val folders = ArrayList<String>()
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders.add(id)
                } else if (name in WANTED) {
                    into[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id) to cursor.getLong(3)
                }
            }
        }
        folders.forEach { collect(tree, it, into, depth + 1) }
    }

    /** Returns null on success, or a sentence saying what went wrong. */
    private fun copy(
        name: String,
        sources: List<Pair<Uri, Long>>,
        expectedSha256: String?,
        onProgress: (Progress) -> Unit,
    ): String? {
        val total = sources.sumOf { it.second }
        val tmp = File(dir, "$name.importing")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            tmp.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20)
                for ((uri, _) in sources) {
                    val input: InputStream = context.contentResolver.openInputStream(uri)
                        ?: return "$name: could not be opened."
                    input.use {
                        while (true) {
                            val read = it.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            if (expectedSha256 != null) digest.update(buffer, 0, read)
                            copied += read
                            onProgress(Progress(name, copied, total))
                        }
                    }
                }
            }
            if (expectedSha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expectedSha256) {
                    tmp.delete()
                    return "$name: the copy does not match the original (checksum differs). Send it again."
                }
            }
            val target = File(dir, name)
            target.delete()
            if (!tmp.renameTo(target)) return "$name: could not be saved."
            return null
        } catch (e: Exception) {
            tmp.delete()
            return "$name: ${e.message ?: "copy failed"}"
        }
    }

    companion object {
        const val WHISPER_ENCODER = "small-encoder.int8.onnx"
        const val WHISPER_DECODER = "small-decoder.int8.onnx"
        const val WHISPER_TOKENS = "small-tokens.txt"
        const val VAD = "silero_vad.onnx"
        const val GEMMA = "gemma-4-E2B-it.litertlm"

        /** SHA-256 of the file published by litert-community on Hugging Face. */
        const val GEMMA_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

        val SPEECH_FILES = listOf(WHISPER_ENCODER, WHISPER_DECODER, WHISPER_TOKENS, VAD)
        private val WANTED = SPEECH_FILES + listOf(GEMMA, "$GEMMA.part1", "$GEMMA.part2")
    }
}
