package com.ilukezippo.vcfdesk.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
}

/** Image decoding and encoding, kept behind an interface so the scaling rule is testable without Android. */
interface ImageCodec {
    /** Pixel size of the image, or null if [bytes] is not an image. */
    fun size(bytes: ByteArray): Pair<Int, Int>?
    /** [bytes] scaled to [width] x [height], encoded as JPEG. */
    fun resize(bytes: ByteArray, width: Int, height: Int): ByteArray
}

/** Images already within the limit are kept byte-for-byte; larger ones are scaled down. */
class CodecPhotoScaler(private val codec: ImageCodec, private val max: Int = PhotoScaling.MAX_SIDE) : PhotoScaler {
    override fun scale(bytes: ByteArray): ByteArray? {
        val (w, h) = codec.size(bytes) ?: return null
        val (tw, th) = PhotoScaling.targetSize(w, h, max)
        if (tw == w && th == h) return bytes
        return codec.resize(bytes, tw, th)
    }
}

class AndroidImageCodec : ImageCodec {
    override fun size(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    override fun resize(bytes: ByteArray, width: Int, height: Int): ByteArray {
        val (w, h) = size(bytes) ?: throw java.io.IOException("not an image")
        // decode already reduced by a power of two, then scale the rest exactly
        var sample = 1
        while (w / (sample * 2) >= width && h / (sample * 2) >= height) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw java.io.IOException("not an image")
        val scaled = Bitmap.createScaledBitmap(src, width, height, true)
        if (scaled !== src) src.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, PhotoScaling.JPEG_QUALITY, out)
        scaled.recycle()
        return out.toByteArray()
    }
}

/** The scaler the app uses: also applies the EXIF rotation, so a portrait photo is not stored sideways. */
class AndroidPhotoScaler(private val codec: ImageCodec = AndroidImageCodec()) : PhotoScaler {
    override fun scale(bytes: ByteArray): ByteArray? {
        val upright = rotateIfNeeded(bytes) ?: bytes
        return CodecPhotoScaler(codec).scale(upright)
    }

    private fun rotateIfNeeded(bytes: ByteArray): ByteArray? {
        val degrees = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) { 0 }
        if (degrees == 0) return null
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, PhotoScaling.JPEG_QUALITY, out)
        return out.toByteArray()
    }
}
