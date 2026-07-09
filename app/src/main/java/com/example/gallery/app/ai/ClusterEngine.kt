package com.example.gallery.app.ai

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class ClusterEngine @Inject constructor() {

    companion object {
        /**
         * Cosine distance threshold for DINOv2-small embeddings.
         * Lower = more clusters (fine-grained).
         * Higher = fewer clusters (broad groupings).
         *
         * Tuning guide:
         *   0.22 → very fine-grained (many small clusters)
         *   0.28 → recommended default for DINOv2
         *   0.35 → broader groupings
         *   0.45 → too loose for DINOv2, causes over-merging (old CLIP value)
         */
        const val DEFAULT_DISTANCE_THRESHOLD = 0.28f

        /**
         * Incremental match threshold — slightly tighter than clustering
         * threshold to avoid wrongly assigning new images to existing clusters.
         */
        const val INCREMENTAL_MATCH_THRESHOLD = 0.25f

        /** Laplacian variance threshold — below this score = blurry image. */
        const val BLUR_THRESHOLD = 80f

        /** Clusters smaller than this get merged into nearest neighbor. */
        const val MIN_CLUSTER_SIZE = 2
    }

    data class ClusterResult(
        val clusterId: Int,
        val memberUris: List<String>,
        val bestShotUri: String,
        val blurryUris: List<String>,
        val folder: String = "Unknown"
    )

    /**
     * Online sequential clustering — O(n*k) where k = number of clusters.
     *
     * For each image, compare to all existing cluster centroids.
     * If closest is within threshold → add to that cluster.
     * Otherwise → create a new cluster.
     *
     * Handles datasets of any size (no O(n²) guard).
     */
    fun cluster(
        embeddings: Map<String, FloatArray>,
        sharpness: Map<String, Float>,
        distanceThreshold: Float = DEFAULT_DISTANCE_THRESHOLD
    ): List<ClusterResult> {
        if (embeddings.isEmpty()) return emptyList()

        val uris = embeddings.keys.toList()

        // clusterIndex → list of URI indices
        val clusterMembers = mutableListOf<MutableList<Int>>()
        // clusterIndex → centroid
        val centroids = mutableListOf<FloatArray>()

        for ((i, uri) in uris.withIndex()) {
            val emb = embeddings[uri]!!

            // Find closest existing cluster centroid
            var bestCluster = -1
            var bestDist = Float.MAX_VALUE
            for (c in centroids.indices) {
                val dist = cosineDistance(emb, centroids[c])
                if (dist < bestDist) {
                    bestDist = dist
                    bestCluster = c
                }
            }

            if (bestCluster != -1 && bestDist <= distanceThreshold) {
                // Add to existing cluster
                clusterMembers[bestCluster].add(i)
                // Update centroid incrementally
                val members = clusterMembers[bestCluster]
                val allEmbeddings = members.map { embeddings[uris[it]]!! }
                centroids[bestCluster] = computeCentroidFromArrays(allEmbeddings)
            } else {
                // Create new cluster
                clusterMembers.add(mutableListOf(i))
                centroids.add(emb.copyOf())
            }
        }

        // Merge tiny clusters into nearest larger neighbor
        val smallClusters = clusterMembers.indices.filter { clusterMembers[it].size < MIN_CLUSTER_SIZE }
        val removed = mutableSetOf<Int>()
        for (smallIdx in smallClusters) {
            if (smallIdx in removed) continue
            var bestDist = Float.MAX_VALUE
            var bestNeighbor = -1
            for (otherIdx in clusterMembers.indices) {
                if (otherIdx == smallIdx || otherIdx in removed) continue
                if (clusterMembers[otherIdx].size < clusterMembers[smallIdx].size) continue
                val dist = cosineDistance(centroids[smallIdx], centroids[otherIdx])
                if (dist < bestDist) {
                    bestDist = dist
                    bestNeighbor = otherIdx
                }
            }
            if (bestNeighbor != -1 && bestDist <= distanceThreshold) {
                clusterMembers[bestNeighbor].addAll(clusterMembers[smallIdx])
                val allEmbeddings = clusterMembers[bestNeighbor].map { embeddings[uris[it]]!! }
                centroids[bestNeighbor] = computeCentroidFromArrays(allEmbeddings)
                removed.add(smallIdx)
            }
        }

        // Build results, re-indexing cluster IDs
        val results = mutableListOf<ClusterResult>()
        var clusterId = 0
        for (i in clusterMembers.indices) {
            if (i in removed) continue
            val members = clusterMembers[i].map { uris[it] }
            val blurryUris = members.filter { uri ->
                (sharpness[uri] ?: Float.MAX_VALUE) < BLUR_THRESHOLD
            }
            val sharpMembers = members - blurryUris.toSet()
            val bestShot = sharpMembers.maxByOrNull { sharpness[it] ?: 0f }
                ?: members.maxByOrNull { sharpness[it] ?: 0f }
                ?: members.first()

            results.add(ClusterResult(
                clusterId   = clusterId,
                memberUris  = members,
                bestShotUri = bestShot,
                blurryUris  = blurryUris
            ))
            clusterId++
        }
        return results
    }

    /**
     * Matches new images against existing cluster centroids.
     * Uses tighter threshold than clustering to avoid wrong assignments.
     */
    data class MatchResult(
        val assigned: Map<Int, MutableList<String>>,
        val unmatched: MutableMap<String, FloatArray>
    )

    fun matchToExistingClusters(
        newEmbeddings: Map<String, FloatArray>,
        existingClusters: List<Pair<Int, FloatArray>>,
        threshold: Float = INCREMENTAL_MATCH_THRESHOLD   // tighter than cluster threshold
    ): MatchResult {
        val assigned  = mutableMapOf<Int, MutableList<String>>()
        val unmatched = mutableMapOf<String, FloatArray>()

        for ((uri, embedding) in newEmbeddings) {
            var bestClusterId = -1
            var bestDistance  = Float.MAX_VALUE

            for ((clusterId, centroid) in existingClusters) {
                val dist = cosineDistance(embedding, centroid)
                if (dist < bestDistance) {
                    bestDistance  = dist
                    bestClusterId = clusterId
                }
            }

            if (bestClusterId != -1 && bestDistance <= threshold) {
                assigned.getOrPut(bestClusterId) { mutableListOf() }.add(uri)
            } else {
                unmatched[uri] = embedding
            }
        }

        return MatchResult(assigned, unmatched)
    }

    // ── Helpers ──

    fun computeCentroid(embeddings: List<FloatArray>): FloatArray =
        computeCentroidFromArrays(embeddings)

    private fun computeCentroidFromArrays(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return floatArrayOf()
        val dim = embeddings[0].size
        val sum = FloatArray(dim)
        for (emb in embeddings) {
            for (i in 0 until dim) sum[i] += emb[i]
        }
        val n = embeddings.size.toFloat()
        for (i in 0 until dim) sum[i] /= n
        return l2Normalize(sum)
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return 1f - dot.coerceIn(-1f, 1f)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var norm = 0.0
        for (v in vec) norm += v * v
        norm = sqrt(norm)
        if (norm > 1e-8) for (i in vec.indices) vec[i] /= norm.toFloat()
        return vec
    }

    fun bytesFromFloatArray(arr: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(arr.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(arr)
        return bb.array()
    }

    fun floatArrayFromBytes(bytes: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val arr = FloatArray(bytes.size / 4)
        bb.asFloatBuffer().get(arr)
        return arr
    }
}