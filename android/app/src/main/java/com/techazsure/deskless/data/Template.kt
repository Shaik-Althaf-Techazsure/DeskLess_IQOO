package com.techazsure.deskless.data

/**
 * The kinds of walk-round DeskLess can write up. It is not for building sites alone: the same walk is done
 * in a college, a government office, an apartment block, a mall or a hospital. The capture flow is the
 * same for all of them; a template changes the wording on screen, what the on-device model is told about
 * severity, the types an issue can be sorted into, and the title of the report.
 *
 * A stored walk remembers its template by name, so names are never changed, only added. Besides the
 * types that come with the app, a company can make its own on its dashboard; the app fetches them, so
 * a new kind of place needs no new version of the app.
 */
class Template(
    /** What a stored walk and the server remember the type by. */
    val name: String,
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
    /** True for a type a company made for itself on its dashboard. */
    val custom: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is Template && other.name == name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name

    companion object {
        /** For any place at all, and the first choice for a new walk: nothing in it belongs to one trade. */
        val GENERAL = Template(
            name = "GENERAL",
            title = "General inspection",
            reportTitle = "INSPECTION REPORT",
            blurb = "Any place: a building, a floor, a yard, a vehicle, a room",
            placeLabel = "Place",
            personLabel = "Inspector",
            ownerLabel = "Owner",
            callToAction = "START THE WALK",
            placeHint = "Name this place",
            personHint = "Your name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "a person walking round a place to write up what is wrong with it",
            severityGuide = "Critical is immediate danger to people; High is a safety risk, a security problem, or " +
                "something that stops the place being used; Medium needs fixing soon; Low is minor or cosmetic.",
            categories = listOf("Safety", "Electrical", "Water and sanitation", "Cleanliness", "Equipment", "Security", "Access", "Other"),
        )
        val SITE = Template(
            name = "SITE",
            title = "Site inspection",
            reportTitle = "SITE INSPECTION REPORT",
            blurb = "Construction projects of any size, fit-outs and handovers",
            placeLabel = "Site",
            personLabel = "Inspector",
            ownerLabel = "Owner",
            callToAction = "WALK THE SITE",
            placeHint = "Name this site",
            personHint = "Inspector name",
            guide = "Photograph each problem and say what is wrong, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "a site inspector walking a construction site or a building being fitted out or handed over",
            severityGuide = "Critical is immediate danger to people or the structure; High is a safety risk, work " +
                "that must stop, or a security problem such as theft or an unknown person on site; Medium needs " +
                "fixing soon; Low is minor or cosmetic.",
            categories = listOf("Structural", "Electrical", "Plumbing", "Fire safety", "Housekeeping", "Security", "Other"),
        )
        val CAMPUS = Template(
            name = "CAMPUS",
            title = "Campus inspection",
            reportTitle = "CAMPUS INSPECTION REPORT",
            blurb = "Colleges and schools: classrooms, labs, hostels, canteens",
            placeLabel = "Campus or block",
            personLabel = "Inspector",
            ownerLabel = "Department",
            callToAction = "WALK THE CAMPUS",
            placeHint = "Name the campus or block",
            personHint = "Inspector name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "an inspector walking a college or school campus: classrooms, laboratories, hostels and canteens",
            severityGuide = "Critical is immediate danger to students or staff, such as exposed live wiring, a gas " +
                "leak in a laboratory or a blocked fire exit; High is a safety or hygiene risk, such as unsafe " +
                "drinking water, a broken railing or an unknown person in a hostel; Medium disturbs teaching or " +
                "living and needs fixing soon; Low is minor or cosmetic.",
            categories = listOf("Classrooms and furniture", "Laboratories", "Hostel and canteen", "Electrical", "Water and sanitation", "Fire safety", "Security", "Other"),
        )
        val OFFICE = Template(
            name = "OFFICE",
            title = "Office inspection",
            reportTitle = "OFFICE INSPECTION REPORT",
            blurb = "Government and company offices: counters, records, access",
            placeLabel = "Office",
            personLabel = "Inspecting officer",
            ownerLabel = "Section",
            callToAction = "WALK THE OFFICE",
            placeHint = "Name the office",
            personHint = "Officer name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "an officer inspecting a government or company office: public counters, record rooms, work areas and access for visitors",
            severityGuide = "Critical is immediate danger to staff or the public, such as exposed live wiring or a " +
                "blocked fire exit; High is a safety risk, records or cash left unsecured, or a public service " +
                "that has stopped; Medium hinders work or visitors and needs fixing soon; Low is minor or cosmetic.",
            categories = listOf("Public counters and waiting", "Records and storage", "Electrical and IT", "Water and sanitation", "Accessibility", "Fire safety", "Security", "Other"),
        )
        val RESIDENTIAL = Template(
            name = "RESIDENTIAL",
            title = "Apartment inspection",
            reportTitle = "APARTMENT INSPECTION REPORT",
            blurb = "Apartment blocks and housing societies: lifts, water, common areas",
            placeLabel = "Building or society",
            personLabel = "Inspector",
            ownerLabel = "Vendor or committee",
            callToAction = "WALK THE BUILDING",
            placeHint = "Name the building or society",
            personHint = "Inspector name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "an inspector walking an apartment block or housing society: lifts, water supply, common areas and parking",
            severityGuide = "Critical is immediate danger to residents, such as a faulty lift, exposed live wiring or " +
                "a gas leak; High is a safety risk or a failed essential service, such as no water, a broken gate " +
                "or a dark stairwell; Medium needs fixing soon, such as a leak or a damaged common area; Low is " +
                "minor or cosmetic.",
            categories = listOf("Lifts", "Water and plumbing", "Electrical", "Common areas", "Parking", "Fire safety", "Security", "Other"),
        )
        val RETAIL = Template(
            name = "RETAIL",
            title = "Mall inspection",
            reportTitle = "MALL INSPECTION REPORT",
            blurb = "Malls, shops and food courts: escalators, exits, hygiene",
            placeLabel = "Mall or store",
            personLabel = "Inspector",
            ownerLabel = "Tenant or team",
            callToAction = "WALK THE FLOOR",
            placeHint = "Name the mall or store",
            personHint = "Inspector name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "an inspector walking a shopping mall or store: escalators, exits, food courts, shop fronts and parking",
            severityGuide = "Critical is immediate danger to shoppers or staff, such as a blocked fire exit, a faulty " +
                "escalator or exposed live wiring; High is a safety or food hygiene risk, or a security problem " +
                "such as theft or an unattended bag; Medium spoils the visit and needs fixing soon, such as a spill " +
                "or broken lighting; Low is minor or cosmetic.",
            categories = listOf("Escalators and lifts", "Fire safety", "Food court hygiene", "Electrical", "Housekeeping", "Signage and access", "Security", "Other"),
        )
        val HOSPITAL = Template(
            name = "HOSPITAL",
            title = "Hospital round",
            reportTitle = "HOSPITAL ROUND REPORT",
            blurb = "Wards, corridors and plant rooms: safety, hygiene, equipment",
            placeLabel = "Hospital or ward",
            personLabel = "Inspector",
            ownerLabel = "Department",
            callToAction = "WALK THE WARD",
            placeHint = "Name the hospital or ward",
            personHint = "Inspector name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone; nothing of a patient is needed in the picture.",
            reporter = "an inspector on a round of a hospital building: wards, corridors, plant rooms and waste areas",
            severityGuide = "Critical is immediate danger to a patient or staff, such as an unsecured oxygen cylinder, " +
                "failed backup power or a blocked fire exit; High is an infection or safety risk, such as " +
                "biomedical waste left open, a broken bed rail or no running water at a hand-wash point; Medium " +
                "needs fixing soon; Low is minor or cosmetic.",
            categories = listOf("Infection control", "Medical gases and equipment", "Electrical and backup power", "Water and sanitation", "Biomedical waste", "Patient safety", "Fire safety", "Other"),
            caution = "Record only what was observed about the building and its equipment. Do not give medical advice, and do not name or describe a patient.",
        )
        val MEDICATION = Template(
            name = "MEDICATION",
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
        )
        val COMMUNITY = Template(
            name = "COMMUNITY",
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
        )
        val FACTORY = Template(
            name = "FACTORY",
            title = "Factory round",
            reportTitle = "FACTORY ROUND REPORT",
            blurb = "Plants and workshops: machines, guards, floors, stores",
            placeLabel = "Plant or line",
            personLabel = "Inspector",
            ownerLabel = "Department",
            callToAction = "WALK THE FLOOR",
            placeHint = "Name the plant or line",
            personHint = "Inspector name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "an inspector on a round of a factory: machines, guards, walkways, stores and utilities",
            severityGuide = "Critical is immediate danger to a worker, such as a missing machine guard, a leak of gas " +
                "or chemical, or exposed live wiring; High is a safety risk or a stopped line, such as an oil " +
                "spill on a walkway or a failed emergency stop; Medium needs fixing soon; Low is minor or cosmetic.",
            categories = listOf("Machine safety", "Electrical", "Chemicals and gases", "Housekeeping", "Fire safety", "Protective equipment", "Utilities", "Other"),
        )
        val HOTEL = Template(
            name = "HOTEL",
            title = "Hotel inspection",
            reportTitle = "HOTEL INSPECTION REPORT",
            blurb = "Hotels and restaurants: rooms, kitchens, corridors, pools",
            placeLabel = "Hotel or floor",
            personLabel = "Inspector",
            ownerLabel = "Department",
            callToAction = "WALK THE FLOOR",
            placeHint = "Name the hotel or floor",
            personHint = "Inspector name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "an inspector walking a hotel or restaurant: guest rooms, kitchens, corridors and public areas",
            severityGuide = "Critical is immediate danger to a guest or staff, such as a gas leak in a kitchen, " +
                "exposed live wiring or a blocked fire exit; High is a safety or food hygiene risk, or a room " +
                "that cannot be let; Medium spoils a stay and needs fixing soon; Low is minor or cosmetic.",
            categories = listOf("Guest rooms", "Kitchen and food hygiene", "Electrical", "Water and plumbing", "Housekeeping", "Fire safety", "Security", "Other"),
        )
        val WAREHOUSE = Template(
            name = "WAREHOUSE",
            title = "Warehouse inspection",
            reportTitle = "WAREHOUSE INSPECTION REPORT",
            blurb = "Warehouses and depots: racking, docks, forklifts, stock",
            placeLabel = "Warehouse or bay",
            personLabel = "Inspector",
            ownerLabel = "Team",
            callToAction = "WALK THE BAYS",
            placeHint = "Name the warehouse or bay",
            personHint = "Inspector name",
            guide = "Photograph each problem and say what is wrong and where, in Hindi, English or both. " +
                "DeskLess writes the report on this phone, with no network.",
            reporter = "an inspector walking a warehouse or depot: racking, loading docks, forklift routes and stored goods",
            severityGuide = "Critical is immediate danger to people, such as damaged racking under load, a blocked " +
                "fire exit or a leaking drum; High is a safety risk or damage to goods, such as a spill on a " +
                "forklift route or a broken dock leveller; Medium needs fixing soon; Low is minor or cosmetic.",
            categories = listOf("Racking and storage", "Forklifts and traffic", "Loading docks", "Electrical", "Housekeeping", "Fire safety", "Security", "Other"),
        )

        /** The types that come with the app, the general one first. */
        val BUILT_IN = listOf(GENERAL, SITE, FACTORY, WAREHOUSE, CAMPUS, OFFICE, RESIDENTIAL, RETAIL, HOTEL, HOSPITAL, MEDICATION, COMMUNITY)

        /** The types the person's company made for itself. They come from its server and are kept on the phone. */
        @Volatile
        var own: List<Template> = emptyList()
            private set

        /** Every type that can be chosen: the company's own first, then those that come with the app. */
        val entries: List<Template> get() = own + BUILT_IN

        /** The type with this name; the general one when the name is not known on this phone. */
        fun named(name: String?): Template = entries.firstOrNull { it.name == name } ?: GENERAL

        /**
         * A company's own type, as its server describes it: a name, what is checked, what the place is
         * called and the kinds of issue. Everything else is worded for it here.
         */
        fun made(key: String, title: String, checks: String, place: String, kinds: List<String>, serious: String = ""): Template {
            val called = place.trim().ifBlank { "Place" }
            val what = checks.trim().trimEnd('.').ifBlank { title.trim() }
            return Template(
                name = key,
                title = title.trim(),
                reportTitle = title.trim().uppercase() + " REPORT",
                blurb = what.replaceFirstChar { it.uppercase() },
                placeLabel = called,
                personLabel = "Inspector",
                ownerLabel = "Owner",
                callToAction = "START THE WALK",
                placeHint = "Name the ${called.lowercase()}",
                personHint = "Your name",
                guide = GENERAL.guide,
                reporter = "a person inspecting $what",
                severityGuide = GENERAL.severityGuide + if (serious.isBlank()) "" else " In this kind of place, treat as serious: ${serious.trim().trimEnd('.')}.",
                categories = (kinds.map { it.trim() }.filter { it.isNotEmpty() && !it.equals("Other", ignoreCase = true) }.distinctBy { it.lowercase() }.take(7) + "Other"),
                custom = true,
            )
        }

        /** Takes the company's own types from the server's list, or from the copy kept on the phone. */
        fun adopt(list: org.json.JSONArray?) {
            own = (0 until (list?.length() ?: 0)).mapNotNull { at ->
                val one = list?.optJSONObject(at) ?: return@mapNotNull null
                val key = one.optString("key")
                val title = one.optString("title")
                if (key.isBlank() || title.isBlank() || BUILT_IN.any { it.name == key }) return@mapNotNull null
                val kinds = one.optJSONArray("categories")
                made(key, title, one.optString("checks"), one.optString("place"), (0 until (kinds?.length() ?: 0)).map { kinds!!.optString(it) }, one.optString("serious"))
            }
        }
    }
}
