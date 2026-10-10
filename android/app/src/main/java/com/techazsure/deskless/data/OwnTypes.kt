package com.techazsure.deskless.data

import org.json.JSONObject
import java.io.File

/**
 * The company's own report types and the type it usually starts a walk with, as last heard from its
 * server. They are kept on the phone, so they are there with no network and before anyone has signed in
 * again.
 */
object OwnTypes {
    private const val FILE = "report-types.json"

    /** The report type the company starts its walks with; blank when it has not said. */
    var usual: String = ""
        private set

    /** The usual type this phone has already switched to once, so a person's own choice is not undone each time. */
    private var applied: String = ""

    /** Reads what was kept. Called before the walk in hand is read, which may be of one of these types. */
    fun load(dir: File) {
        runCatching {
            val kept = JSONObject(File(dir, FILE).readText())
            Template.adopt(kept.optJSONArray("types"))
            usual = kept.optString("usual")
            applied = kept.optString("applied")
        }
    }

    /**
     * Takes what the server says along with the person's details. Returns the type to start with when
     * the company's usual type is news to this phone; otherwise nothing.
     */
    fun heard(dir: File, me: JSONObject): Template? {
        // A server from before this says nothing of report types: what the phone has is left alone.
        val types = me.optJSONArray("report_types") ?: return null
        Template.adopt(types)
        usual = me.optString("report_type")
        val news = usual.isNotBlank() && usual != applied && Template.entries.any { it.name == usual }
        if (news) applied = usual
        runCatching {
            File(dir, FILE).writeText(JSONObject().put("types", types).put("usual", usual).put("applied", applied).toString())
        }
        return if (news) Template.named(usual) else null
    }
}
