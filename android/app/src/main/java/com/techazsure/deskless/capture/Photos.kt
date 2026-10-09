package com.techazsure.deskless.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Rewrites a camera JPEG upright and no larger than [maxEdge] on its long side, so the list, the PDF
 * and the on-device model can all read it without handling EXIF rotation or full-size frames.
 */
fun normalizeJpeg(file: File, maxEdge: Int = 1600) {
    val orientation = try {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val source = decodeSampled(file, maxEdge) ?: return
    val scale = min(1f, maxEdge.toFloat() / max(source.width, source.height))
    val matrix = Matrix().apply {
        postScale(scale, scale)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }
    val upright = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    val tmp = File(file.parentFile, file.name + ".tmp")
    FileOutputStream(tmp).use { upright.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    if (upright !== source) source.recycle()
    upright.recycle()
    tmp.renameTo(file)
}

/**
 * Saves evenly spaced stills from a video clip as JPEGs, one per entry of [outputs], no larger than
 * [maxEdge] on the long side. Returns how many were written. Blocking; call it off the main thread.
 */
fun extractFrames(video: File, outputs: List<File>, maxEdge: Int = 1600): Int {
    val retriever = MediaMetadataRetriever()
    var written = 0
    try {
        retriever.setDataSource(video.absolutePath)
        val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        outputs.forEachIndexed { i, out ->
            // 1/(n+1), 2/(n+1)… of the way through, so the very first and last frames are avoided.
            val at = durationUs * (i + 1) / (outputs.size + 1)
            val frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@forEachIndexed
            val scale = min(1f, maxEdge.toFloat() / max(frame.width, frame.height))
            val sized = if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
            } else {
                frame
            }
            FileOutputStream(out).use { sized.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (sized !== frame) sized.recycle()
            frame.recycle()
            written++
        }
    } finally {
        retriever.release()
    }
    return written
}

/** Decodes with power-of-two subsampling; the result's long side is between [maxEdge] and twice that. */
fun decodeSampled(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}
