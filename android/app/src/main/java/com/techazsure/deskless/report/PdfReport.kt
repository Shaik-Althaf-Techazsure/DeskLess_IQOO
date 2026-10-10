package com.techazsure.deskless.report

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.techazsure.deskless.capture.Geo
import com.techazsure.deskless.capture.decodeSampled
import com.techazsure.deskless.structure.Expiry
import com.techazsure.deskless.data.Check
import com.techazsure.deskless.data.Inspection
import com.techazsure.deskless.data.Issue
import com.techazsure.deskless.data.Readings
import com.techazsure.deskless.data.Severity
import com.techazsure.deskless.data.Template
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Builds the English inspection report with Android's own PDF writer; nothing leaves the phone. */
object PdfReport {
    // A4 in PostScript points.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val FOOTER_SPACE = 28f

    private const val BAND_H = 94f
    private const val INK = 0xFF0F1115.toInt()
    private const val YELLOW = 0xFFFFC31F.toInt()
    private const val FIXED_GREEN = 0xFF2E7D32.toInt()

    /** The frame for the picture taken after a repair: smaller than the first, as it is the follow-up. */
    private const val FIX_W = 120f
    private const val FIX_H = 100f

    private const val PHOTO_W = 180f
    private const val PHOTO_H = 135f
    private const val PHOTO_MAX_H = 200f
    private const val PHOTO_GAP = 14f
    private const val LABEL_W = 58f

    /**
     * The longest side, in pixels, a picture is stored at in the report. It prints sharp at the size the
     * page shows it, and keeps the report small enough to cross from phone to phone quickly: the pictures
     * are nearly all of a report's size.
     */
    private const val PHOTO_PIXELS = 520

    fun newFile(context: Context, inspection: Inspection): File {
        val dir = File(context.filesDir, "reports").apply { mkdirs() }
        val site = inspection.site.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(30)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val name = listOf("DeskLess", site, stamp).filter { it.isNotEmpty() }.joinToString("-")
        return File(dir, "$name.pdf")
    }

    /**
     * [briefing] is the model's opening summary and [speed] one line on how it ran; both are left out when
     * null. [fileOf] finds a stored picture by name, for the picture taken after a repair. [signed] is set
     * when the inspector is signing the report off with a fingerprint; the report then closes with it.
     */
    fun build(
        inspection: Inspection,
        issues: List<Issue>,
        photoOf: (Issue) -> File,
        out: File,
        briefing: String? = null,
        speed: String? = null,
        fileOf: (String) -> File = { File(it) },
        signed: SignOff.Mark? = null,
        review: Review? = null,
    ) {
        val doc = PdfDocument()
        try {
            val writer = Writer(doc, inspection.template)
            writer.header(inspection, issues)
            if (briefing != null) writer.briefing(briefing, speed)
            if (review != null) writer.review(review)
            writer.map(issues)
            issues.forEachIndexed { i, issue ->
                writer.issue(i + 1, issue, photoOf(issue), issue.fixPhoto?.let(fileOf))
            }
            if (signed != null) writer.signOff(signed)
            writer.finish()
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    fun severityColor(severity: Severity): Int = when (severity) {
        Severity.LOW -> 0xFF2E7D32.toInt()
        Severity.MEDIUM -> 0xFFB7791F.toInt()
        Severity.HIGH -> 0xFFE65100.toInt()
        Severity.CRITICAL -> 0xFFC62828.toInt()
    }

    private class Writer(private val doc: PdfDocument, private val template: Template) {
        private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val ink = 0xFF1B1B1F.toInt()
        private val grey = 0xFF6B6B73.toInt()

        private val brandPaint = text(12f, Color.WHITE, bold)
        private val headingPaint = text(21f, YELLOW, Typeface.create("sans-serif-condensed", Typeface.BOLD))
        private val metaPaint = text(10.5f, ink)
        private val titlePaint = text(13f, ink, bold)
        private val labelPaint = text(8.5f, grey)
        private val valuePaint = text(10.5f, ink)
        private val missingPaint = text(10.5f, 0xFF9A9AA3.toInt())
        private val badgePaint = text(8.5f, Color.WHITE, bold)
        private val tagPaint = text(8.5f, grey, bold)
        private val tagOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
            color = 0xFF9A9AA3.toInt()
        }
        private val footerPaint = text(8f, grey)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val rule = Paint().apply { color = 0xFFD9D9DE.toInt(); strokeWidth = 0.8f }

        private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH)

        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private lateinit var canvas: Canvas
        private var y = MARGIN

        fun header(inspection: Inspection, issues: List<Issue>) {
            newPage()
            // The masthead: the same charcoal, yellow and site tape as the app.
            fill.color = INK
            canvas.drawRect(0f, 0f, PAGE_W.toFloat(), BAND_H, fill)
            stripes(BAND_H, 6f)
            logo(MARGIN, 24f, 46f)
            canvas.drawText("DeskLess", MARGIN + 60f, 42f, brandPaint)
            canvas.drawText(template.reportTitle, MARGIN + 60f, 66f, headingPaint)
            y = BAND_H + 6f + 20f

            // Two columns of facts under the masthead.
            val meta = listOf(
                template.placeLabel to inspection.site.ifBlank { "Not given" },
                "Started" to dateFormat.format(Date(inspection.startedAt)),
                template.personLabel to inspection.inspector.ifBlank { "Not given" },
                "Report built" to dateFormat.format(Date()),
            )
            meta.chunked(2).forEach { row ->
                row.forEachIndexed { column, (label, value) ->
                    val x = MARGIN + column * (CONTENT_W / 2)
                    canvas.drawText(label, x, y + 10f, labelPaint)
                    canvas.drawText(value, x + 84f, y + 10f, metaPaint)
                }
                y += 17f
            }
            y += 8f

            // What kinds of issue were found, in the template's own order.
            val types = template.categories
                .map { type -> type to issues.count { it.category == type } }
                .filter { it.second > 0 }
                .joinToString("  ·  ") { "${it.second} ${it.first}" }

            val counts = Severity.entries.reversed()
                .map { severity -> severity to issues.count { it.severity == severity } }
                .filter { it.second > 0 }
                .joinToString("  ·  ") { "${it.second} ${it.first.label}" }
            val fixed = issues.count { it.resolved }
            val total = (if (issues.size == 1) "1 issue" else "${issues.size} issues") +
                if (fixed > 0) " ($fixed fixed)" else ""
            canvas.drawText(if (counts.isEmpty()) total else "$total:  $counts", MARGIN, y + 11f, titlePaint)
            y += 20f
            if (types.isNotEmpty()) {
                canvas.drawText("By type", MARGIN, y + 9f, labelPaint)
                canvas.drawText(types, MARGIN + 84f, y + 9f, metaPaint)
                y += 18f
            }
            // A reader should know where the numbers in the entries come from, and how far to trust them.
            if (issues.any { it.readings.isNotEmpty() || it.stamp?.let(Readings::describe).orEmpty().isNotEmpty() }) {
                canvas.drawText("Readings", MARGIN, y + 9f, labelPaint)
                canvas.drawText("Taken on the spot by the phone's own sensors: a guide, not calibrated measurements.", MARGIN + 84f, y + 9f, labelPaint)
                y += 16f
            }
            y += 2f
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            y += 16f
        }

        /** The model's few lines for the reader in a hurry, in a tinted box with a yellow edge. */
        fun briefing(text: String, speed: String?) {
            val body = layout(text, valuePaint, CONTENT_W - 28f)
            val credit = speed?.let { layout("Written by $it. Check it against the entries below.", labelPaint, CONTENT_W - 28f) }
            val height = 14f + 12f + body.height + (credit?.let { 6f + it.height } ?: 0f) + 12f
            ensure(height + 14f)
            fill.color = 0xFFF6F3E7.toInt()
            canvas.drawRect(MARGIN, y, PAGE_W - MARGIN, y + height, fill)
            fill.color = YELLOW
            canvas.drawRect(MARGIN, y, MARGIN + 4f, y + height, fill)
            canvas.drawText("AI BRIEFING", MARGIN + 14f, y + 18f, tagPaint)
            draw(body, MARGIN + 14f, y + 26f)
            credit?.let { draw(it, MARGIN + 14f, y + 26f + body.height + 6f) }
            y += height + 14f
        }

        /**
         * What the review made of the report's own evidence: the verdict, the reason, and each thing that
         * was checked with a green, red or grey dot for passed, failed or simply noted.
         */
        fun review(review: Review) {
            val tone = when (review.verdict) {
                Verdict.GENUINE -> FIXED_GREEN
                Verdict.DOUBTFUL -> 0xFFB7791F.toInt()
                Verdict.FAKE -> 0xFFC62828.toInt()
            }
            val wash = when (review.verdict) {
                Verdict.GENUINE -> 0xFFEAF4EC.toInt()
                Verdict.DOUBTFUL -> 0xFFFBF3DF.toInt()
                Verdict.FAKE -> 0xFFFBE9E7.toInt()
            }
            val width = CONTENT_W - 28f
            val headline = TextPaint(titlePaint).apply { color = tone }
            val reason = layout(review.reason, valuePaint, width)
            val checks = review.findings.map { it to layout(it.text, metaPaint, width - 14f) }
            val small = layout(
                (if (review.byModel) "Checked by Gemma 4 on this phone and by fixed checks." else "Checked by fixed checks; the AI model was not on the phone.") +
                    " This is a check of the report's own evidence, to help a reviewer. It is not proof.",
                labelPaint, width,
            )
            val height = 14f + 12f + 18f + reason.height + 8f + checks.sumOf { it.second.height + 3 } + 6f + small.height + 12f
            ensure(height + 14f)
            fill.color = wash
            canvas.drawRect(MARGIN, y, PAGE_W - MARGIN, y + height, fill)
            fill.color = tone
            canvas.drawRect(MARGIN, y, MARGIN + 4f, y + height, fill)
            canvas.drawText("AI REVIEW", MARGIN + 14f, y + 18f, tagPaint)
            var ty = y + 26f
            canvas.drawText("${review.verdict.label.uppercase(Locale.ENGLISH)}  ·  ${review.verdict.decision.uppercase(Locale.ENGLISH)}", MARGIN + 14f, ty + 12f, headline)
            ty += 18f
            draw(reason, MARGIN + 14f, ty)
            ty += reason.height + 8f
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = grey }
            for ((finding, text) in checks) {
                val dotY = ty + 7.5f
                when (finding.mark) {
                    Mark.PASS -> { fill.color = FIXED_GREEN; canvas.drawCircle(MARGIN + 18f, dotY, 3f, fill) }
                    Mark.FAIL -> { fill.color = 0xFFC62828.toInt(); canvas.drawCircle(MARGIN + 18f, dotY, 3f, fill) }
                    Mark.NOTE -> canvas.drawCircle(MARGIN + 18f, dotY, 2.6f, ring)
                }
                draw(text, MARGIN + 28f, ty)
                ty += text.height + 3f
            }
            draw(small, MARGIN + 14f, ty + 6f)
            y += height + 14f
        }

        /**
         * Where the issues are, drawn from their own positions: numbered dots in severity colours on a
         * metre grid, with the route walked. Left out when fewer than two issues have a position.
         */
        fun map(issues: List<Issue>) {
            val located = issues.withIndex().filter { it.value.located }
            if (located.size < 2) return
            val points = Geo.project(located.map { it.value.lat!! to it.value.lon!! })
            val span = Geo.span(points)
            val bar = Geo.scaleBar(span)
            val height = 150f
            ensure(height + 34f)

            canvas.drawText("WHERE THEY ARE", MARGIN, y + 9f, tagPaint)
            canvas.drawText(
                "${located.size} of ${issues.size} issues carry a position from the phone, taken with no network",
                MARGIN + 100f, y + 9f, labelPaint,
            )
            y += 16f
            val box = RectF(MARGIN, y, PAGE_W - MARGIN, y + height)
            fill.color = 0xFFF4F4F6.toInt()
            canvas.drawRect(box, fill)
            canvas.save()
            canvas.clipRect(box)

            val radius = 8f
            val scale = (min(box.width(), box.height()) - 4 * radius) / span
            val cx = box.centerX()
            val cy = box.centerY()
            val step = bar * scale
            var gx = box.left + (cx - box.left) % step
            while (gx < box.right) {
                canvas.drawLine(gx, box.top, gx, box.bottom, rule)
                gx += step
            }
            var gy = box.top + (cy - box.top) % step
            while (gy < box.bottom) {
                canvas.drawLine(box.left, gy, box.right, gy, rule)
                gy += step
            }

            val spots = points.map { (cx + it.east * scale) to (cy - it.north * scale) }
            val route = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9B9C0.toInt(); strokeWidth = 1.2f }
            spots.zipWithNext().forEach { (from, to) -> canvas.drawLine(from.first, from.second, to.first, to.second, route) }
            spots.forEachIndexed { i, (sx, sy) ->
                val issue = located[i].value
                fill.color = Color.WHITE
                canvas.drawCircle(sx, sy, radius + 1.5f, fill)
                val label = "${located[i].index + 1}"
                // A fixed issue is an open ring, so it is not taken for a low-severity one.
                if (issue.resolved) {
                    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; color = FIXED_GREEN }
                    canvas.drawCircle(sx, sy, radius - 1f, ring)
                    val green = TextPaint(badgePaint).apply { color = FIXED_GREEN }
                    canvas.drawText(label, sx - green.measureText(label) / 2, sy + 3.1f, green)
                } else {
                    fill.color = severityColor(issue.severity)
                    canvas.drawCircle(sx, sy, radius, fill)
                    canvas.drawText(label, sx - badgePaint.measureText(label) / 2, sy + 3.1f, badgePaint)
                }
            }
            canvas.restore()

            // Scale bar and north, outside the clip so they are never cut.
            val barLeft = box.left + 10f
            val barY = box.bottom - 10f
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 1.4f }
            canvas.drawLine(barLeft, barY, barLeft + step, barY, line)
            canvas.drawText(Geo.distance(bar), barLeft, barY - 5f, labelPaint)
            canvas.drawText("N", box.right - 18f, box.top + 30f, labelPaint)
            canvas.drawLine(box.right - 15f, box.top + 20f, box.right - 15f, box.top + 8f, line)
            canvas.drawLine(box.right - 15f, box.top + 8f, box.right - 18.5f, box.top + 13f, line)
            canvas.drawLine(box.right - 15f, box.top + 8f, box.right - 11.5f, box.top + 13f, line)
            y += height + 18f
        }

        fun issue(number: Int, issue: Issue, photo: File, fixPhoto: File? = null) {
            val textX = MARGIN + PHOTO_W + PHOTO_GAP
            val textW = CONTENT_W - PHOTO_W - PHOTO_GAP

            val title = layout("$number. ${issue.title.ifBlank { "Untitled issue" }}", titlePaint, textW, 3)
            val verdict = when (issue.check) {
                Check.NONE -> null
                Check.MATCH -> "Picture matches the note"
                // Say both halves: a reader should know the model had doubts and a person overruled it.
                Check.CONFIRMED -> "AI was unsure; picture confirmed on site"
                Check.UNSURE -> "Picture not confirmed"
                Check.MISMATCH -> "Picture may not match the note"
            }
            val position = if (issue.lat != null && issue.lon != null) {
                Geo.label(issue.lat, issue.lon) + if (issue.accuracy > 0f) " (±${issue.accuracy.toInt()} m)" else ""
            } else {
                null
            }
            // The date the model read off the pack, with the sum done by the app: passed, or not.
            val expiry = Expiry.parse(issue.expiry)?.let { read ->
                "${issue.expiry} read by AI: " + if (Expiry.isExpired(read)) "EXPIRED" else "in date"
            }
            // What the phone's sensors read when the picture was taken, and what the inspector measured with it.
            val stamped = issue.stamp?.let(Readings::describe).orEmpty().joinToString(" · ")
                .replaceFirstChar { it.uppercase(Locale.ENGLISH) }
            val measured = issue.readings.joinToString(" · ", transform = Readings::describe)
            val fields = listOfNotNull(
                "Location" to issue.location,
                template.ownerLabel to issue.owner,
                "Due" to issue.dueDate,
                "Captured" to dateFormat.format(Date(issue.createdAt)) + if (issue.videoFile != null) " (video)" else "",
                position?.let { "Position" to it },
                issue.place.takeIf { it.isNotBlank() }?.let { "Tagged" to "$it (NFC tag on site)" },
                stamped.takeIf { it.isNotEmpty() }?.let { "Sensors" to it },
                measured.takeIf { it.isNotEmpty() }?.let { "Measured" to it },
                expiry?.let { "Expiry" to it },
                verdict?.let { "AI check" to it },
            ).map { (label, value) ->
                // An empty field is spelt out in grey, so a reader sees it was not said rather than lost.
                label to if (value.isBlank()) layout("Not stated", missingPaint, textW - LABEL_W, 2)
                else layout(value, valuePaint, textW - LABEL_W, 4)
            }
            val note = issue.note.trim().takeIf { it.isNotEmpty() }?.let { layout(it, valuePaint, CONTENT_W) }

            val badgeH = 15f
            val rightH = title.height + 6f + badgeH + 8f + fields.sumOf { it.second.height + 3 }
            val (photoW, photoH) = photoSize(photo)
            val topH = max(photoH, rightH)
            val noteH = if (note == null) 0f else 12f + 13f + note.height

            // A repaired issue carries the second picture and what was made of it.
            val fixed = fixPhoto?.takeIf { it.isFile }
            val fixWords = if (fixed == null) null else layout(
                listOfNotNull(
                    when (issue.fixCheck) {
                        Check.MATCH -> "Fixed. The AI compared the two pictures and the problem is no longer visible."
                        Check.CONFIRMED -> "Fixed, confirmed on site."
                        Check.MISMATCH -> "Still open. In the AI's view the problem is still visible after the repair."
                        Check.UNSURE -> "Still open. The AI could not tell from the two pictures whether it is fixed."
                        Check.NONE -> "A picture was taken after the repair; nobody has confirmed the fix."
                    },
                    issue.fixSeen.takeIf { it.isNotBlank() }?.let { "The second picture shows: $it" },
                    "Picture taken ${dateFormat.format(Date(issue.fixedAt))}.",
                ).joinToString(" "),
                valuePaint, CONTENT_W - FIX_W - PHOTO_GAP,
            )
            val fixH = if (fixed == null) 0f else 12f + 13f + max(FIX_H, fixWords!!.height.toFloat())
            ensure(topH + noteH + fixH + 22f)

            drawPhoto(photo, RectF(MARGIN, y, MARGIN + photoW, y + photoH))

            var ty = y
            draw(title, textX, ty)
            ty += title.height + 6f

            val badgeText = issue.severity.label.uppercase(Locale.ENGLISH)
            val badgeW = badgePaint.measureText(badgeText) + 14f
            fill.color = severityColor(issue.severity)
            canvas.drawRoundRect(RectF(textX, ty, textX + badgeW, ty + badgeH), 7.5f, 7.5f, fill)
            canvas.drawText(badgeText, textX + 7f, ty + 10.8f, badgePaint)
            // The issue's type sits beside the severity, as an outlined tag.
            if (issue.category.isNotBlank()) {
                val tag = issue.category.uppercase(Locale.ENGLISH)
                val left = textX + badgeW + 6f
                val tagW = tagPaint.measureText(tag) + 14f
                canvas.drawRoundRect(RectF(left, ty + 0.5f, left + tagW, ty + badgeH - 0.5f), 7f, 7f, tagOutline)
                canvas.drawText(tag, left + 7f, ty + 10.8f, tagPaint)
            }
            ty += badgeH + 8f

            for ((label, value) in fields) {
                canvas.drawText(label, textX, ty + 10f, labelPaint)
                draw(value, textX + LABEL_W, ty)
                ty += value.height + 3f
            }

            y += topH
            if (note != null) {
                y += 12f
                canvas.drawText("Note", MARGIN, y + 9f, labelPaint)
                y += 13f
                draw(note, MARGIN, y)
                y += note.height
            }
            if (fixed != null && fixWords != null) {
                y += 12f
                canvas.drawText(if (issue.resolved) "AFTER THE FIX" else "AFTER THE REPAIR", MARGIN, y + 9f, tagPaint)
                y += 13f
                // Drawn to fit its frame whatever its shape, so a tall picture is not stretched.
                val (w, h) = photoSize(fixed, FIX_W, FIX_H)
                drawPhoto(fixed, RectF(MARGIN, y, MARGIN + w, y + h))
                draw(fixWords, MARGIN + FIX_W + PHOTO_GAP, y)
                y += max(FIX_H, fixWords.height.toFloat())
            }
            y += 10f
            canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            y += 12f
        }

        /** The closing block of a signed report: who signed, how, and where the signature is. */
        fun signOff(mark: SignOff.Mark) {
            val body = layout(
                "Signed by ${mark.by.ifBlank { "the ${template.personLabel.lowercase(Locale.ENGLISH)}" }} on ${dateFormat.format(mark.at)}, " +
                    "confirmed with the fingerprint sensor of the phone that built this report. Signing key ${mark.keyId}.",
                valuePaint, CONTENT_W - 28f,
            )
            val small = layout(
                "The signature is in ${mark.file}, which travels with this PDF. It was made over the finished PDF by a key " +
                    "kept in the phone's secure hardware; if the PDF is changed afterwards, the signature no longer matches.",
                labelPaint, CONTENT_W - 28f,
            )
            val height = 14f + 12f + body.height + 6f + small.height + 12f
            ensure(height + 14f)
            fill.color = 0xFFEAF4EC.toInt()
            canvas.drawRect(MARGIN, y, PAGE_W - MARGIN, y + height, fill)
            fill.color = FIXED_GREEN
            canvas.drawRect(MARGIN, y, MARGIN + 4f, y + height, fill)
            canvas.drawText("SIGNED OFF", MARGIN + 14f, y + 18f, tagPaint)
            draw(body, MARGIN + 14f, y + 26f)
            draw(small, MARGIN + 14f, y + 26f + body.height + 6f)
            y += height + 14f
        }

        fun finish() = finishPage()

        /** The size the photo is drawn at: as wide as its column allows, taller for portrait shots. */
        private fun photoSize(photo: File, maxW: Float = PHOTO_W, maxH: Float = PHOTO_MAX_H): Pair<Float, Float> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return maxW to min(maxH, PHOTO_H)
            val scale = min(maxW / bounds.outWidth, maxH / bounds.outHeight)
            return bounds.outWidth * scale to bounds.outHeight * scale
        }

        private fun drawPhoto(photo: File, frame: RectF) {
            val decoded = decodeSampled(photo, 480)
            if (decoded == null) {
                fill.color = 0xFFEDEDF0.toInt()
                canvas.drawRect(frame, fill)
                return
            }
            val longest = max(decoded.width, decoded.height)
            val sized = if (longest <= PHOTO_PIXELS) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, decoded.width * PHOTO_PIXELS / longest, decoded.height * PHOTO_PIXELS / longest, true)
                    .also { if (it !== decoded) decoded.recycle() }
            }
            // The PDF writer stores pictures without loss, so what they cost is decided here. Fewer
            // colour steps than the eye misses at this size more than halve it again: measured on
            // photos from the phone, a picture went from about 800 KB in the report to about 160 KB.
            val bitmap = sized.copy(Bitmap.Config.RGB_565, false)?.also { sized.recycle() } ?: sized
            canvas.drawBitmap(bitmap, null, frame, bitmapPaint)
            bitmap.recycle()
        }

        /** A strip of black-and-yellow site tape across the page, starting at [top]. */
        private fun stripes(top: Float, height: Float) {
            fill.color = YELLOW
            canvas.drawRect(0f, top, PAGE_W.toFloat(), top + height, fill)
            fill.color = INK
            canvas.save()
            canvas.clipRect(0f, top, PAGE_W.toFloat(), top + height)
            val w = 9f
            var x = -height - 2 * w
            while (x < PAGE_W + height) {
                val band = Path().apply {
                    moveTo(x, top + height)
                    lineTo(x + w, top + height)
                    lineTo(x + w + height, top)
                    lineTo(x + height, top)
                    close()
                }
                canvas.drawPath(band, fill)
                x += 2 * w
            }
            canvas.restore()
        }

        /** The DeskLess mark, as in the app: a clipboard with a tick on a yellow tile. */
        private fun logo(left: Float, top: Float, s: Float) {
            fill.color = YELLOW
            canvas.drawRoundRect(RectF(left, top, left + s, top + s), s * 0.24f, s * 0.24f, fill)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = s * 0.075f
                color = INK
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawRoundRect(
                RectF(left + s * 0.27f, top + s * 0.25f, left + s * 0.73f, top + s * 0.81f),
                s * 0.08f, s * 0.08f, line,
            )
            fill.color = INK
            canvas.drawRoundRect(
                RectF(left + s * 0.39f, top + s * 0.17f, left + s * 0.61f, top + s * 0.32f),
                s * 0.045f, s * 0.045f, fill,
            )
            val tick = Path().apply {
                moveTo(left + s * 0.385f, top + s * 0.545f)
                lineTo(left + s * 0.47f, top + s * 0.63f)
                lineTo(left + s * 0.625f, top + s * 0.45f)
            }
            canvas.drawPath(tick, line)
        }

        private fun ensure(height: Float) {
            if (page == null || y + height > PAGE_H - MARGIN - FOOTER_SPACE) newPage()
        }

        private fun newPage() {
            finishPage()
            pageNumber++
            val started = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create())
            page = started
            canvas = started.canvas
            y = MARGIN
        }

        private fun finishPage() {
            val current = page ?: return
            val footer = "DeskLess  ·  built on the phone, offline  ·  Page $pageNumber"
            val x = (PAGE_W - footerPaint.measureText(footer)) / 2
            canvas.drawText(footer, x, PAGE_H - MARGIN + 12f, footerPaint)
            doc.finishPage(current)
            page = null
        }

        private fun text(size: Float, color: Int, typeface: Typeface = Typeface.SANS_SERIF) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                this.typeface = typeface
            }

        private fun layout(text: CharSequence, paint: TextPaint, width: Float, maxLines: Int = Int.MAX_VALUE) =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
                .setLineSpacing(1.5f, 1f)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

        private fun draw(layout: StaticLayout, x: Float, top: Float) {
            canvas.save()
            canvas.translate(x, top)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
