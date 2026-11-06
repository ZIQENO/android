package dev.ziqeno.android

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.Image
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

internal object QrReader {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun image(resolver: ContentResolver, uri: Uri): String? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 2048) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decode(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))
        } finally {
            bitmap.recycle()
        }
    }

    fun frame(image: Image): String? {
        val width = image.width
        val height = image.height
        val plane = image.planes.first()
        val input = plane.buffer
        val pixels = ByteArray(width * height)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) {
            val rowStart = y * rowStride
            val output = y * width
            if (pixelStride == 1) {
                input.position(rowStart)
                input.get(pixels, output, width)
            } else {
                for (x in 0 until width) pixels[output + x] = input.get(rowStart + x * pixelStride)
            }
        }
        return decode(PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false))
    }

    private fun decode(source: LuminanceSource): String? =
        decodeOne(source) ?: decodeOne(source.invert())

    private fun decodeOne(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        null
    }
}
