package com.primusobscurus.dedupcleaner

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DuplicateScanner {

    data class ScanResult(
        val exactGroups: List<DuplicateGroup>,
        val similarGroups: List<DuplicateGroup>
    )

    /** Scans the device gallery (images + videos) and finds duplicate groups. */
    suspend fun scan(
        context: Context,
        similarityThreshold: Int, // 70, 80 or 90
        onProgress: (String) -> Unit
    ): ScanResult = withContext(Dispatchers.IO) {

        val items = mutableListOf<MediaItem>()

        // --- 1. Load image and video metadata from MediaStore ---
        for (video in listOf(false, true)) {
            val collection = if (video)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    if (!file.exists()) continue
                    items += MediaItem(
                        id = id,
                        uri = uri,
                        name = cursor.getString(nameCol) ?: file.name,
                        path = path,
                        size = cursor.getLong(sizeCol),
                        date = cursor.getLong(dateCol),
                        durationMs = if (video) 1 else 0, // placeholder; real duration from retriever
                        isVideo = video
                    )
                }
            }
        }
        onProgress("Analizando ${items.size} archivos…")

        // --- 2. Exact duplicates: group by size first, then MD5 ---
        val bySize = items.groupBy { it.size }.filter { it.value.size > 1 }
        val exactGroups = mutableListOf<DuplicateGroup>()
        for (sameSize in bySize.values) {
            val byMd5 = mutableMapOf<String, MutableList<MediaItem>>()
            for (item in sameSize) {
                val md5 = runCatching { Hashing.md5(File(item.path)) }.getOrDefault("")
                if (md5.isNotEmpty()) byMd5.getOrPut(md5) { mutableListOf() } += item
            }
            for (dupes in byMd5.values) {
                if (dupes.size > 1) {
                    exactGroups += DuplicateGroup(items = dupes, exact = true)
                }
            }
        }
        onProgress("Duplicados exactos: ${exactGroups.size} grupos")

        // --- 3. Perceptual hashes for every remaining item ---
        val exactIds = exactGroups.flatMap { g -> g.items.map { it.id } }.toSet()
        for (item in items) {
            if (item.id in exactIds) continue // already grouped as exact
            val file = File(item.path)
            try {
                if (item.isVideo) {
                    val (duration, hashes) = Hashing.videoInfo(file)
                    item.durationMs = duration
                    item.frameHashes = hashes
                    item.hash = Hashing.combine(hashes)
                } else {
                    val bmp = Hashing.decodeForHash(file) ?: continue
                    item.hash = Hashing.dHash(bmp)
                    bmp.recycle()
                }
            } catch (e: Exception) {
                // skip unreadable files
            }
        }
        onProgress("Comparando similitud…")

        // --- 4. Similar (not exact) groups via union-find at the chosen threshold ---
        val hashed = items.filter { it.hash != 0uL && it.id !in exactIds }
        val images = hashed.filter { !it.isVideo }
        val videos = hashed.filter { it.isVideo }

        val similarGroups = mutableListOf<DuplicateGroup>()
        for (set in listOf(images, videos)) {
            val parent = IntArray(set.size) { it }
            fun find(x: Int): Int = if (parent[x] == x) x else find(parent[x]).also { parent[x] = it }
            fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

            for (i in set.indices) {
                for (j in i + 1 until set.size) {
                    if (Hashing.similarity(set[i], set[j]) >= similarityThreshold) union(i, j)
                }
            }
            val clusters = set.indices.groupBy { find(it) }
            for (cluster in clusters.values) {
                if (cluster.size > 1) {
                    similarGroups += DuplicateGroup(items = cluster.map { set[it] }, exact = false)
                }
            }
        }

        ScanResult(exactGroups, similarGroups)
    }
}
