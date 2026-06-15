package com.example.gallery.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the CLIP ViT-B/32 ONNX model from assets and produces
 * 512-dimensional image embeddings per query image.
 *
 * PLACE YOUR MODEL FILE AT: app/src/main/assets/clip_image_encoder.onnx
 *
 * Input: float32[1, 3, 224, 224]  — normalized RGB image
 * Output: float32[1, 512]         — semantic embedding vector
 */
@Singleton
class OnnxEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val MODEL_FILE = "clip_image_encoder.onnx"
        private const val IMAGE_SIZE = 224
        private const val EMBEDDING_DIM = 512

        // CLIP normalization constants (ImageNet)
        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD  = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    /**
     * Lazily initializes the ONNX session.
     * Call from a background thread — model loading is expensive.
     */
    fun initialize() {
        if (session != null) return
        val modelBytes = context.assets.open(MODEL_FILE).readBytes()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        session = env.createSession(modelBytes, options)
    }

    /**
     * Generates a 512-dim normalized embedding for the image at [uri].
     * Returns null if the image cannot be decoded or inference fails.
     */
    fun embed(uri: String): FloatArray? {
        val sess = session ?: run { initialize(); session ?: return null }
        val bitmap = loadAndResizeBitmap(uri) ?: return null
        val inputTensor = bitmapToTensor(bitmap)

        return try {
            val inputMap = mapOf("input" to inputTensor)
            sess.run(inputMap).use { output ->
                val embedTensor = output[0].value as Array<FloatArray>
                l2Normalize(embedTensor[0])
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            inputTensor.close()
        }
    }
    /**
     * Loads and resizes image to [IMAGE_SIZE]×[IMAGE_SIZE], handling content:// URIs.
     */
    private fun loadAndResizeBitmap(uri: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

            val targetSize = IMAGE_SIZE
            var inSampleSize = 1
            if (options.outHeight > targetSize || options.outWidth > targetSize) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= targetSize && halfWidth / inSampleSize >= targetSize) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val decoded = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            decoded?.let {
                Bitmap.createScaledBitmap(it, IMAGE_SIZE, IMAGE_SIZE, true)
            }
        } catch (e: Exception) { null }
    }

    private fun openStream(uri: String) = if (uri.startsWith("content://")) {
        context.contentResolver.openInputStream(Uri.parse(uri))
    } else {
        java.io.FileInputStream(uri)
    }
    /**
     * Converts a Bitmap to a normalized float tensor: [1, 3, H, W]
     * Applies CLIP-specific ImageNet mean/std normalization.
     */
    private fun bitmapToTensor(bitmap: Bitmap): OnnxTensor {
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        val buffer = FloatBuffer.allocate(1 * 3 * IMAGE_SIZE * IMAGE_SIZE)
        val rChannel = FloatArray(IMAGE_SIZE * IMAGE_SIZE)
        val gChannel = FloatArray(IMAGE_SIZE * IMAGE_SIZE)
        val bChannel = FloatArray(IMAGE_SIZE * IMAGE_SIZE)

        for (i in pixels.indices) {
            val px = pixels[i]
            rChannel[i] = (((px shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            gChannel[i] = (((px shr 8) and 0xFF)  / 255f - MEAN[1]) / STD[1]
            bChannel[i] = ((px and 0xFF)           / 255f - MEAN[2]) / STD[2]
        }

        buffer.put(rChannel)
        buffer.put(gChannel)
        buffer.put(bChannel)
        buffer.rewind()

        return OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        )
    }

    /**
     * L2-normalizes a float vector in-place and returns it.
     * Required for cosine similarity to work correctly.
     */
    private fun l2Normalize(vec: FloatArray): FloatArray {
        val norm = Math.sqrt(vec.fold(0.0) { acc, v -> acc + v * v }).toFloat()
        if (norm > 1e-8f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    fun release() {
        session?.close()
        session = null
    }
}
