package com.primusobscurus.dedupcleaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest

object Hashing {

    const val VIDEO_FRAME_COUNT = 6

    /** MD5 of the full file content, used for exact-duplicate detection. */
    fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 64-bit difference hash (dHash) from an arbitrary bitmap. */
    fun dHash(bitmap: Bitmap): ULong {
        val w = 9
        val h = 8
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != bitmap) scaled.recycle()

        var hash = 0uL
        var bit = 63
        for (row in 0 until h) {
            for (col in 0 until 8) {
                val left = gray(pixels[row * w + col])
                val right = gray(pixels[row * w + col + 1])
                if (left > right) hash = hash or (1uL shl bit)
                bit--
            }
        }
        return hash
    }

    private fun gray(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Down-scaled decode of an image file for hashing. */
    fun decodeForHash(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 64 && opts.outHeight / (sample * 2) >= 64) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** Extracts 6 evenly-spaced frames from a video and hashes each one.
     *  Returns the real duration (ms) plus the frame hashes. */
    fun videoInfo(file: File): Pair<Long, List<ULong>> {
        val retriever = MediaMetadataRetriever()
        val hashes = mutableListOf<ULong>()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = (retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            if (durationMs <= 0L) return 0L to emptyList()
            for (i in 0 until VIDEO_FRAME_COUNT) {
                val t = durationMs * (i + 1) / (VIDEO_FRAME_COUNT + 1)
                val frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                hashes += dHash(frame)
                frame.recycle()
            }
            durationMs to hashes
        } catch (e: Exception) {
            0L to emptyList()
        } finally {
            try { retriever.release() } catch (e: Exception) { /* ignore */ }
        }
    }

    fun hamming(a: ULong, b: ULong): Int = (a xor b).toLong().countOneBits()

    /** Similarity percentage between two media items (0-100). */
    fun similarity(a: MediaItem, b: MediaItem): Int {
        if (a.isVideo && b.isVideo && a.frameHashes.isNotEmpty() && b.frameHashes.isNotEmpty()) {
            var total = 0
            for (fa in a.frameHashes) {
                var best = Int.MIN_VALUE
                for (fb in b.frameHashes) {
                    best = maxOf(best, ((64 - hamming(fa, fb)) * 100) / 64)
                }
                total += best
            }
            return total / a.frameHashes.size
        }
        if (!a.isVideo && !b.isVideo) {
            return ((64 - hamming(a.hash, b.hash)) * 100) / 64
        }
        return 0 // mixed image/video pairs are never "similar"
    }

    /** Combined order-independent video signature: XOR of frame hashes. */
    fun combine(hashes: List<ULong>): ULong = hashes.reduceOrNull { acc, u -> acc xor u } ?: 0uL
}
