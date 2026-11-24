package com.ilukezippo.vcfdesk.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Turns a picked image into the bytes stored on the contact; null if it is not a readable image. */
interface PhotoScaler {
    fun scale(bytes: ByteArray): ByteArray?
}

object PhotoScaling {
    const val MAX_SIDE = 720
    const val JPEG_QUALITY = 90

    /** Size to scale a [width] x [height] image to: its longest side becomes at most [max]; smaller images keep their size. */
    fun targetSize(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val f = max.toDouble() / longest
        return maxOf(1, Math.round(width * f).toInt()) to maxOf(1, Math.round(height * f).toInt())
    }

    /** BitmapFactory inSampleSize: the largest power of two that keeps the longest side at or above [max]. */
    fun sampleSize(width: Int, height: Int, max: Int = MAX_SIDE): Int {
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= max) sample *= 2
        return sample
    }
}

/** Image decoding and encoding, kept behind an interface so the scaling rule is testable without Android. */
interface ImageCodec {
    /** Upright pixel size of the image (EXIF rotation applied), or null if [bytes] is not an image. */
    fun size(bytes: ByteArray): Pair<Int, Int>?
    /** Clockwise degrees (0, 90, 180, 270) the image must be turned to be upright. */
    fun rotation(bytes: ByteArray): Int = 0
    /** [bytes] turned upright and scaled to [width] x [height] (upright size), encoded as JPEG. */
    fun resize(bytes: ByteArray, width: Int, height: Int): ByteArray
}

/** Upright images already within the limit are kept byte-for-byte; larger or rotated ones are redone once. */
class CodecPhotoScaler(private val codec: ImageCodec, private val max: Int = PhotoScaling.MAX_SIDE) : PhotoScaler {
    override fun scale(bytes: ByteArray): ByteArray? {
        val (w, h) = codec.size(bytes) ?: return null
        val (tw, th) = PhotoScaling.targetSize(w, h, max)
        if (tw == w && th == h && codec.rotation(bytes) == 0) return bytes
        return codec.resize(bytes, tw, th)
    }
}

class AndroidImageCodec : ImageCodec {
    private fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    override fun rotation(bytes: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) { 0 }

    override fun size(bytes: ByteArray): Pair<Int, Int>? {
        val (w, h) = bounds(bytes) ?: return null
        return if (rotation(bytes) % 180 != 0) h to w else w to h
    }

    /** Decodes at most a few times the target size, then rotates and scales in one step and encodes once. */
    override fun resize(bytes: ByteArray, width: Int, height: Int): ByteArray {
        val (rw, rh) = bounds(bytes) ?: throw IOException("not an image")
        val degrees = rotation(bytes)
        val src = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = PhotoScaling.sampleSize(rw, rh) })
        } catch (e: OutOfMemoryError) {
            throw IOException("image too large", e)
        } ?: throw IOException("not an image")
        var out: Bitmap? = null
        try {
            val sw = if (degrees % 180 != 0) src.height else src.width
            val sh = if (degrees % 180 != 0) src.width else src.height
            val m = Matrix().apply {
                postRotate(degrees.toFloat())
                postScale(width.toFloat() / sw, height.toFloat() / sh)
            }
            out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val buf = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, PhotoScaling.JPEG_QUALITY, buf)
            return buf.toByteArray()
        } catch (e: OutOfMemoryError) {
            throw IOException("image too large", e)
        } finally {
            if (out != null && out !== src) out.recycle()
            src.recycle()
        }
    }
}

/** The scaler the app uses. A failure to decode (including running out of memory) gives null, never a crash. */
class AndroidPhotoScaler(private val codec: ImageCodec = AndroidImageCodec()) : PhotoScaler {
    override fun scale(bytes: ByteArray): ByteArray? = try {
        CodecPhotoScaler(codec).scale(bytes)
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: IOException) {
        null
    }
}
