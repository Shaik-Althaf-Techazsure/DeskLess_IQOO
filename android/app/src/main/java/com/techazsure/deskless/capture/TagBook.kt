package com.techazsure.deskless.capture

import android.content.Context
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import org.json.JSONObject
import java.io.File

/**
 * The NFC tags this phone knows: a sticker on a door, a pump or a medicine fridge, or any card with a
 * chip. A tag is recognised by its own serial number, so nothing has to be written to it; the name it
 * stands for is kept here on the phone.
 */
class TagBook(context: Context) {
    private val file = File(context.filesDir, "tags.json")
    private val names = HashMap<String, String>()

    init {
        runCatching {
            val json = JSONObject(file.readText())
            json.keys().forEach { names[it] = json.getString(it) }
        }
    }

    val size get() = names.size

    fun nameOf(id: String): String? = names[id]

    fun name(id: String, name: String) {
        names[id] = name
        save()
    }

    /** Gives every tag that stood for [old] the new name, or forgets them when [new] is blank. */
    fun rename(old: String, new: String) {
        val ids = names.filterValues { it == old }.keys.toList()
        ids.forEach { if (new.isBlank()) names.remove(it) else names[it] = new }
        save()
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject(names as Map<*, *>).toString())
        tmp.renameTo(file)
    }

    companion object {
        /** The tag's serial number as text, e.g. "04A1B2C3D4E5F6". */
        fun idOf(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it) }

        /** Text already written on the tag, if it carries any; offered as the name when the tag is new. */
        fun textOn(tag: Tag): String? = runCatching {
            Ndef.get(tag)?.cachedNdefMessage?.records?.firstNotNullOfOrNull { record ->
                if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) return@firstNotNullOfOrNull null
                val payload = record.payload
                // A text record starts with a status byte holding the length of the language code that follows.
                val skip = 1 + (payload[0].toInt() and 0x3F)
                String(payload, skip, payload.size - skip, if (payload[0].toInt() and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16)
                    .trim().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}
