package com.example.gallery.app.ai

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Pure Kotlin implementation of Agglomerative (hierarchical) clustering
 * using cosine similarity, mirroring the Python sklearn pipeline.
 *
 * Distance threshold is configurable — lower = more clusters (fine-grained),
 * higher = fewer clusters (broad groupings).
 */
@Singleton
class ClusterEngine @Inject constructor() {

    companion object {
        /** Cosine distance threshold for merging two clusters (0–2 scale). */
        const val DEFAULT_DISTANCE_THRESHOLD = 0.45f

        /** Laplacian variance threshold — below this score = blurry image. */
        const val BLUR_THRESHOLD = 80f
    }

    data class ClusterResult(
        val clusterId: Int,
        val memberUris: List<String>,
        val bestShotUri: String,
        val blurryUris: List<String>
    )

    /**
     * Clusters a list of (uri → embedding) pairs using single-linkage
     * agglomerative clustering with cosine distance.
     *
     * @param embeddings map of image URI → normalized 512-dim float vector
     * @param sharpness  map of image URI → Laplacian variance score
     * @return list of clusters with best-shot and blurry members identified
     */
    fun cluster(
        embeddings: Map<String, FloatArray>,
        sharpness: Map<String, Float>,
        distanceThreshold: Float = DEFAULT_DISTANCE_THRESHOLD
    ): List<ClusterResult> {
        if (embeddings.isEmpty()) return emptyList()

        val uris = embeddings.keys.toList()
        val n = uris.size

        // Union-Find structure for cluster assignment
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var node = x
            while (parent[node] != r) {
                val next = parent[node]
                parent[node] = r
                node = next
            }
            return r
        }

        fun union(a: Int, b: Int) {
            parent[find(a)] = find(b)
        }

        // Build distance matrix and merge pairs below threshold
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dist = cosineDistance(embeddings[uris[i]]!!, embeddings[uris[j]]!!)
                if (dist <= distanceThreshold) {
                    union(i, j)
                }
            }
        }

        // Group URIs by cluster root
        val clusterMap = mutableMapOf<Int, MutableList<String>>()
        for (i in 0 until n) {
            val root = find(i)
            clusterMap.getOrPut(root) { mutableListOf() }.add(uris[i])
        }

        // Build final results
        return clusterMap.entries.mapIndexed { idx, (_, members) ->
            val blurryUris = members.filter { uri ->
                (sharpness[uri] ?: Float.MAX_VALUE) < BLUR_THRESHOLD
            }
            val sharpMembers = members - blurryUris.toSet()
            val bestShot = sharpMembers.maxByOrNull { sharpness[it] ?: 0f }
                ?: members.maxByOrNull { sharpness[it] ?: 0f }
                ?: members.first()

            ClusterResult(
                clusterId  = idx,
                memberUris = members,
                bestShotUri = bestShot,
                blurryUris = blurryUris
            )
        }
    }

    /**
     * Cosine distance = 1 - cosine_similarity.
     * Both vectors should already be L2-normalized (embedder guarantees this).
     * If normalized: cosine_similarity = dot product directly.
     */
    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return 1f - dot.coerceIn(-1f, 1f)
    }
}
