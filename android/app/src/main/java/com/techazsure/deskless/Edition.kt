package com.techazsure.deskless

import android.content.Context
import android.content.pm.PackageManager

/**
 * Which edition of DeskLess this build is. The same code makes two: the usual one, for the people of an
 * organisation (inspectors, technicians, managers), and, built with `-PuserApp`, the one handed to the
 * people who use a place: residents, students, visitors. The user edition carries its own name on the
 * phone and is meant to show only their side: no staff sign-up, no walk, no desk.
 */
object Edition {
    private var known: Boolean? = null

    /** True in the edition for people who use a place. Read once from what the build wrote into the manifest. */
    fun userOnly(context: Context): Boolean = known ?: runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("deskless.edition") == "user"
    }.getOrDefault(false).also { known = it }
}
