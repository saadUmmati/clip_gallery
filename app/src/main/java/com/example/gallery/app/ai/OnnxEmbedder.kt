package com.example.gallery.app.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the DINOv2-small INT8 ONNX model from assets and produces
 * 384-dimensional image embeddings per query image.
 *
 * MODEL FILE: app/src/main/assets/dinov2_small_embedder_int8.onnx
 *
 * Optimizations for mobile:
 * - Lazy OrtEnvironment init (avoids main-thread native call during DI)
 * - Session kept alive across worker invocations (no re-init penalty)
 * - Graph optimization enabled for faster inference
 * - Reduced thread count (2) to avoid CPU throttling on mobile
 */
@Singleton
class OnnxEmbedder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "OnnxEmbedder"
        private const val MODEL_FILE = "dinov2_small_embedder_int8.onnx"
        private const val IMAGE_SIZE = 224
        private const val EMBEDDING_DIM = 384

        private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        private val STD  = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    // Lazy init — avoids OrtEnvironment native call during Hilt DI on main thread
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var initFailed = false

    fun isInitialized(): Boolean = session != null

    /**
     * Initializes the ONNX session. Safe to call multiple times —
     * subsequent calls are no-ops. Must be called from a background thread.
     */
    fun initialize() {
        if (session != null) return
        if (initFailed) return

        synchronized(this) {
            if (session != null) return
            if (initFailed) return

            try {
                val startTime = System.currentTimeMillis()

                val modelDir = File(context.filesDir, "onnx_models")
                modelDir.mkdirs()

                val modelFile = File(modelDir, MODEL_FILE)

                // Copy model from assets to internal storage (so ONNX can find the .data companion)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    Log.i(TAG, "Copying model from assets to internal storage...")
                    copyAsset(MODEL_FILE, modelFile)
                    Log.i(TAG, "Model copied: ${modelFile.length() / 1024}KB")
                }

                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(2)
                    setMemoryPatternOptimization(true)
                    addCPU(true)
                }

                // Load by file path so ONNX Runtime can find the .data companion
                session = env.createSession(modelFile.absolutePath, options)
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Model initialized in ${elapsed}ms")
            } catch (e: Exception) {
                initFailed = true
                Log.e(TAG, "Failed to initialize ONNX model", e)
                throw e
            }
        }
    }

    private fun copyAsset(assetName: String, dest: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Generates a 384-dim normalized embedding for the image at [uri].
     * Returns null if the image cannot be decoded or inference fails.
     */
    fun embed(uri: String): FloatArray? {
        val sess = session ?: run {
            initialize()
            session ?: return null
        }

        val bitmap = loadAndResizeBitmap(uri) ?: return null
        val inputTensor = bitmapToTensor(bitmap)
        bitmap.recycle()

        return try {
            val inputMap = mapOf("pixel_values" to inputTensor)
            sess.run(inputMap).use { output ->
                val embedTensor = output[0].value as Array<FloatArray>
                l2Normalize(embedTensor[0])
            }
        } catch (e: Exception) {
            Log.w(TAG, "Embed failed for $uri: ${e.message}")
            null
        } finally {
            inputTensor.close()
        }
    }

    private fun loadAndResizeBitmap(uri: String): Bitmap? {
        return try {
            // Decode bounds only (no memory allocation)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }

            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            // Calculate minimum sample size to get close to IMAGE_SIZE
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            var inSampleSize = 1
            while (maxDim / inSampleSize > IMAGE_SIZE * 2) {
                inSampleSize *= 2
            }

            // Decode with sample size
            val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val decoded = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                ?: return null

            // Scale to exact IMAGE_SIZE
            val scaled = Bitmap.createScaledBitmap(decoded, IMAGE_SIZE, IMAGE_SIZE, true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } catch (e: Exception) {
            null
        }
    }

    private fun openStream(uri: String): java.io.InputStream? = try {
        if (uri.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(uri))
        } else {
            FileInputStream(uri)
        }
    } catch (e: Exception) { null }

    private fun bitmapToTensor(bitmap: Bitmap): OnnxTensor {
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        val buffer = FloatBuffer.allocate(3 * IMAGE_SIZE * IMAGE_SIZE)
        val channelSize = IMAGE_SIZE * IMAGE_SIZE

        for (i in pixels.indices) {
            val px = pixels[i]
            buffer.put(i,                (((px shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0])
            buffer.put(i + channelSize,  (((px shr 8)  and 0xFF) / 255f - MEAN[1]) / STD[1])
            buffer.put(i + channelSize * 2, ((px and 0xFF) / 255f - MEAN[2]) / STD[2])
        }
        buffer.rewind()

        return OnnxTensor.createTensor(
            env, buffer,
            longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
        )
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vec) sum += v * v
        val norm = Math.sqrt(sum).toFloat()
        if (norm > 1e-8f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    /**
     * Release the ONNX session to free memory.
     * Only call when the app is going to background or memory is critical.
     * The session will be lazily re-created on next embed() call.
     */
    fun release() {
        session?.close()
        session = null
        initFailed = false
    }
}
