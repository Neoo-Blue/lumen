package ai.lumen.assistant

import androidx.annotation.RequiresApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Size
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

object Imaging {
    fun jpegDataUrl(context: Context, uri: Uri, maxSide: Int, quality: Int): String? {
        val bitmap = decode(context, uri, maxSide) ?: return null
        return toDataUrl(bitmap, maxSide, quality)
    }

    fun jpegDataUrl(file: File, maxSide: Int, quality: Int): String? {
        val bitmap = decode(file, maxSide) ?: return null
        return toDataUrl(bitmap, maxSide, quality)
    }

    fun jpegDataUrl(bitmap: Bitmap, maxSide: Int, quality: Int): String {
        return toDataUrl(bitmap, maxSide, quality)
    }

    fun thumbDataUrl(context: Context, uri: Uri): String? {
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= 29) loadPlatformThumb(context, uri)
            else decode(context, uri, 256)
        } catch (_: Exception) {
            try {
                decode(context, uri, 256)
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return toDataUrl(bitmap, 256, 60)
    }

    @RequiresApi(29)
    private fun loadPlatformThumb(context: Context, uri: Uri): Bitmap {
        return context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
    }

    private fun toDataUrl(source: Bitmap, maxSide: Int, quality: Int): String {
        val soft = software(source)
        val scaled = scaleDown(soft, maxSide)
        val out = ByteArrayOutputStream()
        try {
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 90), out)
            val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            return "data:image/jpeg;base64,$encoded"
        } finally {
            if (scaled !== soft) recycleQuietly(scaled)
            if (soft !== source) recycleQuietly(soft)
            recycleQuietly(source)
        }
    }

    private fun recycleQuietly(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        } catch (_: Exception) {
        }
    }

    private fun software(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val width = src.width
        val height = src.height
        val maxDim = maxOf(width, height)
        if (width <= 0 || height <= 0 || maxDim <= maxSide) return src
        val scale = maxSide.toFloat() / maxDim.toFloat()
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) decodeModern(context, uri, maxSide)
            else decodeLegacyUri(context, uri, maxSide)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(file: File, maxSide: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) decodeModern(file, maxSide)
            else decodeLegacyFile(file, maxSide)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @RequiresApi(28)
    private fun decodeModern(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            applyTarget(decoder, info.size.width, info.size.height, maxSide)
        }
    }

    @RequiresApi(28)
    private fun decodeModern(file: File, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            applyTarget(decoder, info.size.width, info.size.height, maxSide)
        }
    }

    private fun decodeLegacyUri(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val rotation = rotationOf(ByteArrayInputStream(bytes))
        return decodeSampled(rotation, maxSide) { ByteArrayInputStream(bytes) }
    }

    private fun decodeLegacyFile(file: File, maxSide: Int): Bitmap? {
        val rotation = file.inputStream().use { rotationOf(it) }
        return decodeSampled(rotation, maxSide) { file.inputStream() }
    }

    private fun decodeSampled(rotation: Int, maxSide: Int, open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val decoded = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return rotate(decoded, rotation)
    }

    private fun applyTarget(decoder: ImageDecoder, width: Int, height: Int, maxSide: Int) {
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val maxDim = maxOf(width, height)
        if (width > 0 && height > 0 && maxDim > maxSide) {
            val scale = maxSide.toFloat() / maxDim.toFloat()
            decoder.setTargetSize(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1)
            )
        }
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val maxDim = maxOf(width, height)
        if (maxDim <= 0) return 1
        while (maxDim / sample > maxSide * 2) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun rotationOf(stream: InputStream): Int {
        return try {
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }
}
