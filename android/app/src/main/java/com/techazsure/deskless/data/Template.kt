package com.techazsure.deskless.data

/**
 * The kinds of walk-round DeskLess can write up. The capture flow is the same for all of them; a template
 * changes the wording on screen, what the on-device model is told about severity, the types an issue can
 * be sorted into, and the title of the report.
 */
enum class Template(
    val title: String,
    val reportTitle: String,
    val blurb: String,
    /** What the place being walked is called, e.g. "Site". */
    val placeLabel: String,
    /** What the person doing the walk is called. */
    val personLabel: String,
    /** Who an issue is handed to. */
    val ownerLabel: String,
    val callToAction: String,
    val placeHint: String,
    val personHint: String,
    /** What the empty first screen tells a new user to do. */
    val guide: String,
    /** How the model is told who is speaking. */
    val reporter: String,
    val severityGuide: String,
    val categories: List<String>,
    /** An extra rule for the model, where the subject needs one. */
    val caution: String = "",
    /** Whether the model is asked to read an expiry date printed on what is photographed. */
    val readsExpiry: Boolean = false,
) {
    SITE(
        title = "Site inspection",
        reportTitle = "SITE INSPECTION REPORT",
        blurb = "Construction, fit-out and facility walks",
        placeLabel = "Site",
        personLabel = "Inspector",
        ownerLabel = "Owner",
        callToAction = "WALK THE SITE",
        placeHint = "Name this site",
        personHint = "Inspector name",
        guide = "Photograph each problem and say what is wrong, in Hindi, English or both. " +
            "DeskLess writes the report on this phone, with no network.",
        reporter = "a site inspector walking a construction or facility site",
        severityGuide = "Critical is immediate danger to people or the structure; High is a safety risk, work " +
            "that must stop, or a security problem such as theft or an unknown person on site; Medium needs " +
            "fixing soon; Low is minor or cosmetic.",
        categories = listOf("Structural", "Electrical", "Plumbing", "Fire safety", "Housekeeping", "Security", "Other"),
    ),
    MEDICATION(
        title = "Medication audit",
        reportTitle = "MEDICATION AUDIT REPORT",
        blurb = "Pharmacy and ward stock, storage and labelling checks",
        placeLabel = "Pharmacy or ward",
        personLabel = "Auditor",
        ownerLabel = "Responsible",
        callToAction = "CHECK THE SHELVES",
        placeHint = "Name the pharmacy or ward",
        personHint = "Auditor name",
        guide = "Photograph each shelf, fridge or pack that is wrong and say what you found, in Hindi, English " +
            "or both. DeskLess writes the audit on this phone; nothing is uploaded.",
        reporter = "an auditor checking how medicines are stocked, stored and labelled in a pharmacy or hospital ward",
        severityGuide = "Critical is a risk to a patient right now, such as expired or mislabelled medicine where " +
            "it is dispensed; High is a breach that could cause harm soon, such as a broken cold chain or " +
            "controlled drugs left unlocked; Medium is a stock or record problem to fix this week; Low is " +
            "minor housekeeping.",
        categories = listOf("Expired stock", "Storage and cold chain", "Labelling", "Controlled drugs", "Records", "Hygiene", "Other"),
        caution = "Record only what the auditor observed. Do not give medical, dosage or treatment advice.",
        readsExpiry = true,
    ),
    COMMUNITY(
        title = "Community issue",
        reportTitle = "COMMUNITY ISSUE REPORT",
        blurb = "Roads, water, waste and public safety problems for the local office",
        placeLabel = "Area or ward",
        personLabel = "Reported by",
        ownerLabel = "Department",
        callToAction = "WALK THE AREA",
        placeHint = "Name the area or ward",
        personHint = "Your name",
        guide = "Photograph each problem you see and say what it is and where, in Hindi, English or both. " +
            "DeskLess writes the report on this phone, with no network.",
        reporter = "a community worker reporting public problems to the local authority",
        severityGuide = "Critical is immediate danger to the public, such as an open manhole or a live wire; High " +
            "is a hazard or a failed essential service, such as no water or a blocked road; Medium needs " +
            "fixing soon, such as a pothole or an overflowing bin; Low is minor.",
        categories = listOf("Roads", "Water and drainage", "Waste", "Street lighting", "Public safety", "Welfare", "Other"),
    ),
}
