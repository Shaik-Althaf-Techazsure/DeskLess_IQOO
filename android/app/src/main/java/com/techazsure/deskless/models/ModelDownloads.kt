package com.techazsure.deskless.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** One model file as it is published: where it is fetched from, how large it is and what it must hash to. */
data class ModelFile(val name: String, val label: String, val url: String, val bytes: Long, val sha256: String)

/**
 * The model files the app runs, each at the exact published version it was tested with. The addresses name
 * that version, and every download is checked against the size and SHA-256 written here, so a file that
 * was changed, cut short or damaged on the way is never used.
 */
object ModelCatalog {
    private const val GEMMA_SOURCE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
    private const val WHISPER_SOURCE = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/8f3c18b358db4d1f2fc1eae49d75cd20989e4309"

    /** The small ones first, so speech is ready long before the large model has arrived. */
    val FILES = listOf(
        ModelFile(
            ModelStore.VAD, "Voice detection", "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        ),
        ModelFile(
            ModelStore.WHISPER_TOKENS, "Speech to English", "$WHISPER_SOURCE/small-tokens.txt",
            816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
        ),
        ModelFile(
            ModelStore.WHISPER_ENCODER, "Speech to English", "$WHISPER_SOURCE/small-encoder.int8.onnx",
            112_442_483, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
        ),
        ModelFile(
            ModelStore.WHISPER_DECODER, "Speech to English", "$WHISPER_SOURCE/small-decoder.int8.onnx",
            262_226_114, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
        ),
        ModelFile(
            ModelStore.GEMMA, "Note and photo to report fields", "$GEMMA_SOURCE/gemma-4-E2B-it.litertlm",
            2_588_147_712, ModelStore.GEMMA_SHA256,
        ),
    )

    val TOTAL_BYTES = FILES.sumOf { it.bytes }

    /** A size as a person reads it: "643 KB", "112 MB", "2.96 GB". */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        else -> "${bytes / 1000} KB"
    }

    /**
     * How far the phone is towards holding every model: what it already has counts in full, what is on
     * its way counts by what has arrived. So the bar means the same on a phone that starts from nothing
     * and on one that only lacks the large model.
     */
    fun arrived(present: Set<String>, soFar: Map<String, Long>): Long =
        FILES.sumOf { file -> if (file.name in present) file.bytes else (soFar[file.name] ?: 0L).coerceIn(0L, file.bytes) }
}

/** Where the fetching of the models stands, for the screens. */
data class ModelFetch(
    val phase: Phase = Phase.IDLE,
    /** Bytes on the phone towards the whole set, and the size of the whole set. */
    val arrivedBytes: Long = 0,
    val totalBytes: Long = ModelCatalog.TOTAL_BYTES,
    /** What still has to come, when nothing is being fetched. */
    val missingBytes: Long = 0,
    /** The file arriving now, in words. */
    val now: String = "",
    /** What went wrong the last time, for the person to read. */
    val problem: String? = null,
    /** Whether mobile data may be used. */
    val mobileData: Boolean = false,
    /** The person pressed Stop: nothing is fetched until they ask again. */
    val stopped: Boolean = false,
) {
    enum class Phase { IDLE, RUNNING, WAITING_FOR_WIFI, WAITING_FOR_NETWORK, CHECKING, COMPLETE }

    val busy: Boolean get() = phase != Phase.IDLE && phase != Phase.COMPLETE
    val fraction: Float get() = if (totalBytes <= 0) 0f else (arrivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100).toInt()

    /** One line on where it stands. */
    val words: String
        get() = when (phase) {
            Phase.IDLE ->
                if (missingBytes >= totalBytes) "The AI models are not on this phone yet: ${ModelCatalog.size(missingBytes)} to download."
                else "Not all of the AI models are on this phone yet: ${ModelCatalog.size(missingBytes)} to download."
            Phase.RUNNING -> "Downloading the AI models: ${ModelCatalog.size(arrivedBytes)} of ${ModelCatalog.size(totalBytes)}"
            Phase.WAITING_FOR_WIFI -> "Waiting for Wi-Fi to download the AI models (${ModelCatalog.size(totalBytes - arrivedBytes)} to go)."
            Phase.WAITING_FOR_NETWORK -> "Waiting for a network to go on downloading the AI models."
            Phase.CHECKING -> "Checking what was downloaded…"
            Phase.COMPLETE -> "The AI models are on this phone."
        }
}

/**
 * Fetches the model files by itself, so a newly installed app needs nothing sent to it by hand. The
 * downloading is given to Android's own download service: it goes on while DeskLess is in the background
 * or closed, picks up where it stopped when the network drops, and shows its progress among the phone's
 * notifications. When a file has arrived it is checked against the published size and SHA-256 and only
 * then put where the models are read from.
 *
 * The app asks for the models when it comes to the front and again when it finds a network, which is
 * often the same moment, so everything here happens one call at a time: a file is never asked for twice.
 */
class ModelDownloads(private val context: Context, private val store: ModelStore) {
    private val manager: DownloadManager? = context.getSystemService(DownloadManager::class.java)
    private val memory = context.getSharedPreferences("model-downloads", Context.MODE_PRIVATE)

    /** The app's own folder on shared storage: the download service can write there, and no other app can read it. */
    private val folder: File? = context.getExternalFilesDir(null)?.let { File(it, "models") }

    /** The person pressed Stop: nothing is fetched again until they ask. */
    var stopped: Boolean
        get() = memory.getBoolean(STOPPED, false)
        private set(value) = memory.edit().putBoolean(STOPPED, value).apply()

    val mobileData: Boolean get() = memory.getBoolean(MOBILE_DATA, false)

    fun missing(): List<ModelFile> = ModelCatalog.FILES.filter { store.find(it.name) == null }

    private fun idOf(file: ModelFile): Long = memory.getLong(ID + file.name, NONE)

    private fun forget(file: ModelFile) = memory.edit().remove(ID + file.name).apply()

    private fun landing(file: ModelFile): File? = folder?.let { File(it, file.name + ARRIVING) }

    /** Whatever unfinished or unchecked copies of a file lie about, under the name asked for or one the download service made up. */
    private fun leftovers(file: ModelFile): List<File> =
        folder?.listFiles { one -> one.name.startsWith(file.name) && one.name.endsWith(ARRIVING) }?.toList().orEmpty()

    /**
     * Asks for every model file the phone lacks and is not already fetching. Returns a sentence when it
     * cannot, such as too little room. With [mobileData] false the files come over Wi-Fi only, and wait
     * for it if there is none.
     */
    @Synchronized
    fun start(mobileData: Boolean): String? {
        val manager = manager ?: return "This phone has no download service. Import the models from a folder instead."
        val folder = folder ?: return "This phone's storage cannot be reached just now. Try again in a moment."
        val wanted = missing().filter { idOf(it) == NONE }
        memory.edit().putBoolean(STOPPED, false).putBoolean(MOBILE_DATA, mobileData).apply()
        if (wanted.isEmpty()) return null
        folder.mkdirs()
        val room = folder.usableSpace
        val needed = wanted.sumOf { it.bytes } + SPARE_BYTES
        if (room < needed) {
            return "Not enough room on this phone: the AI models need ${ModelCatalog.size(needed)} free, and ${ModelCatalog.size(room)} is free."
        }
        for (file in wanted) {
            // The download service does not write over a file; what an earlier try left behind goes first.
            leftovers(file).forEach { it.delete() }
            val request = DownloadManager.Request(Uri.parse(file.url))
                .setTitle("DeskLess AI models")
                .setDescription("${file.label} · ${ModelCatalog.size(file.bytes)}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(mobileData)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, "models", file.name + ARRIVING)
            val id = runCatching { manager.enqueue(request) }.getOrElse {
                return "The download could not be started: ${it.message ?: "the phone refused it"}."
            }
            memory.edit().putLong(ID + file.name, id).apply()
        }
        return null
    }

    /** Stops whatever is being fetched and throws away what had arrived of it. Files already checked stay. */
    @Synchronized
    fun stop() {
        for (file in ModelCatalog.FILES) {
            val id = idOf(file)
            if (id != NONE) runCatching { manager?.remove(id) }
            forget(file)
            landing(file)?.delete()
        }
        stopped = true
    }

    /** Starts over allowing mobile data. What had arrived of a file still on its way is fetched again. */
    @Synchronized
    fun useMobileData(): String? {
        for (file in ModelCatalog.FILES) {
            val id = idOf(file)
            if (id == NONE || state(id)?.status == DownloadManager.STATUS_SUCCESSFUL) continue
            runCatching { manager?.remove(id) }
            forget(file)
        }
        return start(mobileData = true)
    }

    private class Row(val status: Int, val reason: Int, val soFar: Long)

    private fun state(id: Long): Row? {
        val manager = manager ?: return null
        return runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                Row(
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                )
            }
        }.getOrNull()
    }

    /**
     * What [look] found: where the fetching stands, whether files have arrived that are still to be
     * checked, and whether a download has just failed for good.
     */
    class Look(val fetch: ModelFetch, val toCheck: Boolean, val failed: Boolean)

    /** Asks the download service how each file is doing. Not for the main thread. */
    @Synchronized
    fun look(problem: String? = null): Look {
        val present = HashSet<String>()
        val soFar = HashMap<String, Long>()
        var running: ModelFile? = null
        var waitingForWifi = false
        var waitingForNetwork = false
        var arrived = false
        var failed: String? = null
        var tracked = 0
        for (file in ModelCatalog.FILES) {
            val id = idOf(file)
            if (store.find(file.name) != null) {
                // It came another way meanwhile, such as an import from a folder: no need to go on fetching it.
                if (id != NONE) {
                    runCatching { manager?.remove(id) }
                    forget(file)
                    landing(file)?.delete()
                }
                present.add(file.name)
                continue
            }
            if (id == NONE) continue
            val row = state(id)
            if (row == null) {
                // Taken off the list from outside, for instance cancelled among the notifications.
                forget(file)
                continue
            }
            tracked++
            soFar[file.name] = row.soFar
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> arrived = true
                DownloadManager.STATUS_FAILED -> {
                    failed = "${file.name}: ${why(row.reason)}"
                    runCatching { manager?.remove(id) }
                    forget(file)
                    landing(file)?.delete()
                    tracked--
                }
                DownloadManager.STATUS_PAUSED -> when (row.reason) {
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> waitingForWifi = true
                    else -> waitingForNetwork = true
                }
                else -> if (running == null) running = file
            }
        }
        val missing = ModelCatalog.FILES.filter { it.name !in present }
        val phase = when {
            missing.isEmpty() -> ModelFetch.Phase.COMPLETE
            arrived -> ModelFetch.Phase.CHECKING
            tracked == 0 -> ModelFetch.Phase.IDLE
            running != null -> ModelFetch.Phase.RUNNING
            waitingForWifi -> ModelFetch.Phase.WAITING_FOR_WIFI
            waitingForNetwork -> ModelFetch.Phase.WAITING_FOR_NETWORK
            else -> ModelFetch.Phase.RUNNING
        }
        val fetch = ModelFetch(
            phase = phase,
            arrivedBytes = ModelCatalog.arrived(present, soFar),
            missingBytes = missing.sumOf { it.bytes },
            now = running?.let { "${it.label} · ${ModelCatalog.size(soFar[it.name] ?: 0)} of ${ModelCatalog.size(it.bytes)}" }.orEmpty(),
            problem = failed ?: problem,
            mobileData = mobileData,
            stopped = stopped,
        )
        return Look(fetch, arrived, failed != null)
    }

    /**
     * Checks every file that has arrived against its published size and SHA-256, and puts the good ones
     * where the models are read from. Returns what was wrong with the others. Slow for the large model:
     * not for the main thread.
     */
    @Synchronized
    fun settle(): List<String> {
        val problems = ArrayList<String>()
        for (file in ModelCatalog.FILES) {
            val id = idOf(file)
            if (id == NONE || state(id)?.status != DownloadManager.STATUS_SUCCESSFUL) continue
            val got = landing(file)
            val trouble = when {
                got == null || !got.isFile -> "the downloaded file is not where it was saved"
                got.length() != file.bytes -> "it arrived incomplete (${ModelCatalog.size(got.length())} of ${ModelCatalog.size(file.bytes)})"
                sha256(got) != file.sha256 -> "it does not match the published file (checksum differs)"
                !got.renameTo(File(got.parentFile, file.name)) -> "it could not be put in place"
                else -> null
            }
            if (trouble != null) {
                got?.delete()
                problems.add("${file.name}: $trouble. Tap Download to fetch it again.")
            }
            // The file has been moved or thrown away; the entry in the download service's list goes too.
            runCatching { manager?.remove(id) }
            forget(file)
            leftovers(file).forEach { it.delete() }
        }
        return problems
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun why(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "the phone ran out of room."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "the phone's storage could not be reached."
        DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "the file could not be saved."
        DownloadManager.ERROR_CANNOT_RESUME -> "the download broke off and could not be picked up again."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            "the server it comes from did not answer as expected."
        in 400..599 -> "the server it comes from refused it (code $reason)."
        else -> "the download failed (code $reason)."
    }

    private companion object {
        const val ID = "id."
        const val STOPPED = "stopped"
        const val MOBILE_DATA = "mobile-data"
        const val NONE = -1L
        const val ARRIVING = ".download"

        /** Room left over beyond the files themselves; the large model also writes a cache when it first loads. */
        const val SPARE_BYTES = 300_000_000L
    }
}
