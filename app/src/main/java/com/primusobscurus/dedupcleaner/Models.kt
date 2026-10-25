package com.primusobscurus.dedupcleaner

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val path: String,
    val size: Long,
    val date: Long,
    var durationMs: Long, // 0 for images
    val isVideo: Boolean,
    val md5: String = "",
    var hash: ULong = 0u, // dHash (images) or combined video signature
    var frameHashes: List<ULong> = emptyList(), // 6 frame hashes for videos
    var selectedForDeletion: Boolean = false,
    var thumb: android.graphics.Bitmap? = null
)

data class DuplicateGroup(
    val items: List<MediaItem>,
    val exact: Boolean
) {
    val maxSimilarity: Int get() {
        if (exact) return 100
        var best = 0
        for (i in items.indices) for (j in i + 1 until items.size) {
            best = maxOf(best, Hashing.similarity(items[i], items[j]))
        }
        return best
    }
}
