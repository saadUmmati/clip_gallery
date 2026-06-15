package com.example.gallery.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes image sharpness using the Laplacian Variance method,
 * equivalent to cv2.Laplacian(img, cv2.CV_64F).var() in Python.
 *
 * Higher score = sharper image.
 * Below 80.0 is considered blurry (configurable via ClusterEngine.BLUR_THRESHOLD).
 */
@Singleton
class SharpnessAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // Laplacian kernel: [0,1,0,1,-4,1,0,1,0]
        private val LAPLACIAN = intArrayOf(0, 1, 0, 1, -4, 1, 0, 1, 0)

        private const val SAMPLE_SIZE = 400   // Downscale for performance
    }

    /**
     * Computes Laplacian variance sharpness score for the image at [uri].
     * Returns 0f if the image cannot be loaded.
     */
    fun computeSharpness(uri: String): Float {
        val bitmap = loadSampledBitmap(uri) ?: return 0f
        return laplacianVariance(bitmap)
    }

    private fun loadSampledBitmap(uri: String): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            val stream = openStream(uri) ?: return null
            BitmapFactory.decodeStream(stream, null, opts)
            stream.close()

            val scale = maxOf(opts.outWidth, opts.outHeight) / SAMPLE_SIZE
            val opts2 = BitmapFactory.Options().apply {
                inSampleSize = if (scale > 1) scale else 1
            }
            val stream2 = openStream(uri) ?: return null
            val bmp = BitmapFactory.decodeStream(stream2, null, opts2)
            stream2.close()
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun openStream(uri: String) = try {
        if (uri.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(uri))
        } else {
            java.io.FileInputStream(uri)
        }
    } catch (e: Exception) { null }

    /**
     * Pure Kotlin Laplacian variance computation.
     * Converts to grayscale, applies 3×3 Laplacian kernel,
     * and computes variance of the response image.
     */
    private fun laplacianVariance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = FloatArray(w * h) { i ->
            val px = pixels[i]
            (0.299f * ((px shr 16) and 0xFF) + 0.587f * ((px shr 8) and 0xFF) + 0.114f * (px and 0xFF))
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                // Unrolled 3x3 Laplacian: [0, 1, 0, 1, -4, 1, 0, 1, 0]
                val l = gray[(y - 1) * w + x] + gray[(y + 1) * w + x] +
                        gray[y * w + (x - 1)] + gray[y * w + (x + 1)] -
                        4 * gray[y * w + x]

                sum += l
                sumSq += l * l
                count++
            }
        }

        val mean = sum / count
        return ((sumSq / count) - (mean * mean)).toFloat()
    }
}
