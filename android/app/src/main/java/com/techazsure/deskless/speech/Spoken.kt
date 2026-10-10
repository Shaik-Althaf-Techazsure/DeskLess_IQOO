package com.techazsure.deskless.speech

/**
 * The language a note is spoken in. Whatever it is, the note is written down in English: the speech model
 * on the phone translates as it listens. [code] is the model's own name for the language; blank lets the
 * model work out the language from the first seconds of each note.
 */
enum class Spoken(val code: String, val label: String, val own: String, val hint: String) {
    HINDI("hi", "Hindi + English", "हिन्दी", "Hindi, English or both"),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ", "Kannada, with English words where you like"),
    TAMIL("ta", "Tamil", "தமிழ்", "Tamil, with English words where you like"),
    TELUGU("te", "Telugu", "తెలుగు", "Telugu, with English words where you like"),
    MARATHI("mr", "Marathi", "मराठी", "Marathi, with English words where you like"),
    BENGALI("bn", "Bengali", "বাংলা", "Bengali, with English words where you like"),
    ENGLISH("en", "English only", "English", "English"),
    AUTO("", "Detect it", "Any", "any language; the phone works out which");

    companion object {
        fun named(name: String?): Spoken = entries.firstOrNull { it.name == name } ?: HINDI
    }
}
