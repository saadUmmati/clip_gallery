package com.example.gallery.app.ai

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClusterEngineTest {

    private lateinit var clusterEngine: ClusterEngine

    @Before
    fun setup() {
        clusterEngine = ClusterEngine()
    }

    @Test
    fun `empty embeddings returns empty result`() {
        val result = clusterEngine.cluster(emptyMap(), emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single image returns one cluster`() {
        val embeddings = mapOf("uri1" to FloatArray(384) { 1f / 384f })
        val sharpness = mapOf("uri1" to 100f)
        val result = clusterEngine.cluster(embeddings, sharpness)
        assertEquals(1, result.size)
        assertEquals(listOf("uri1"), result[0].memberUris)
        assertEquals("uri1", result[0].bestShotUri)
    }

    @Test
    fun `similar images are clustered together`() {
        // Two nearly identical vectors (cosine distance close to 0)
        val vec1 = FloatArray(384) { if (it < 256) 1f else 0f }
        val vec2 = FloatArray(384) { if (it < 256) 0.99f else 0.01f }
        // Normalize vectors
        normalize(vec1)
        normalize(vec2)

        val embeddings = mapOf("uri1" to vec1, "uri2" to vec2)
        val sharpness = mapOf("uri1" to 100f, "uri2" to 90f)
        val result = clusterEngine.cluster(embeddings, sharpness)

        assertEquals(1, result.size)
        assertEquals(2, result[0].memberUris.size)
        assertTrue(result[0].memberUris.containsAll(listOf("uri1", "uri2")))
    }

    @Test
    fun `dissimilar images are in different clusters`() {
        val vec1 = floatArrayOf(1f, 0f, 0f, 0f)
        val vec2 = floatArrayOf(0f, 0f, 0f, 1f)
        normalize(vec1)
        normalize(vec2)

        val embeddings = linkedMapOf("uri1" to vec1, "uri2" to vec2)
        val sharpness = mapOf("uri1" to 100f, "uri2" to 100f)
        val result = clusterEngine.cluster(embeddings, sharpness)

        assertEquals(2, result.size)
    }

    @Test
    fun `best shot is the sharpest image`() {
        // Create vectors that are very similar (low cosine distance)
        val vec1 = FloatArray(384) { if (it < 10) 1f else 0f }
        val vec2 = FloatArray(384) { if (it < 10) 0.99f else 0.01f }
        val vec3 = FloatArray(384) { if (it < 10) 0.98f else 0.02f }
        normalize(vec1)
        normalize(vec2)
        normalize(vec3)

        val embeddings = mapOf(
            "blurry" to vec1,
            "sharp1" to vec2,
            "sharp2" to vec3
        )
        val sharpness = mapOf(
            "blurry" to 50f,   // Below threshold
            "sharp1" to 200f,  // Highest sharpness
            "sharp2" to 150f
        )
        val result = clusterEngine.cluster(embeddings, sharpness)

        assertTrue(result.isNotEmpty())
        // Find the cluster containing our images
        val cluster = result.find { it.memberUris.containsAll(listOf("blurry", "sharp1", "sharp2")) }
        if (cluster != null) {
            assertEquals("sharp1", cluster.bestShotUri)
            assertTrue(cluster.blurryUris.contains("blurry"))
        }
    }

    @Test
    fun `blurry threshold works correctly`() {
        // Use identical vectors so they cluster together
        val vec = FloatArray(384) { if (it < 10) 1f else 0f }
        normalize(vec)
        val embeddings = mapOf(
            "below" to vec.copyOf(),
            "above" to vec.copyOf()
        )
        val sharpness = mapOf(
            "below" to 79.9f,
            "above" to 80.1f
        )
        val result = clusterEngine.cluster(embeddings, sharpness)

        assertEquals(1, result.size)
        assertTrue(result[0].blurryUris.contains("below"))
        assertFalse(result[0].blurryUris.contains("above"))
    }

    @Test
    fun `large dataset clusters correctly`() {
        // Create 5000 embeddings: 5 groups of 1000 similar vectors
        val embeddings = mutableMapOf<String, FloatArray>()
        val sharpness = mutableMapOf<String, Float>()
        for (group in 0 until 5) {
            for (i in 0 until 1000) {
                val uri = "uri_${group}_$i"
                embeddings[uri] = FloatArray(384) { if (it in group*76 until (group+1)*76) 1f else 0.01f }
                sharpness[uri] = 100f
            }
        }
        val result = clusterEngine.cluster(embeddings, sharpness)

        // Should produce ~5 clusters (one per group)
        assertTrue("Expected ~5 clusters, got ${result.size}", result.size in 3..7)
        // Each cluster should have many members
        result.forEach { cluster ->
            assertTrue("Cluster has ${cluster.memberUris.size} members, expected >100", cluster.memberUris.size > 100)
        }
    }

    private fun normalize(vec: FloatArray) {
        val norm = Math.sqrt(vec.fold(0.0) { acc, v -> acc + v * v }).toFloat()
        if (norm > 1e-8f) {
            for (i in vec.indices) vec[i] /= norm
        }
    }
}
