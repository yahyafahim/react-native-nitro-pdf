package com.nitropdf

import android.graphics.Bitmap
import java.util.ArrayDeque

/**
 * Minimal bitmap pool to reduce GC churn + avoid OOM when rendering many pages.
 *
 * The pool is keyed by (width, height, config) and respects a byte budget.
 */
class BitmapPool(
  private val maxBytes: Long,
) {
  private data class Key(
    val width: Int,
    val height: Int,
    val config: Bitmap.Config?,
  )

  private val pool: MutableMap<Key, ArrayDeque<Bitmap>> = HashMap()
  private var currentBytes: Long = 0

  private fun bitmapBytes(bitmap: Bitmap): Long {
    // byteCount is in bytes (API 19+).
    return bitmap.byteCount.toLong()
  }

  @Synchronized
  fun get(width: Int, height: Int, config: Bitmap.Config): Bitmap {
    val key = Key(width, height, config)
    val deque = pool[key]
    if (deque != null) {
      val bmp = deque.pollLast()
      if (bmp != null) {
        currentBytes -= bitmapBytes(bmp)
        return bmp
      }
    }
    return Bitmap.createBitmap(width, height, config)
  }

  @Synchronized
  fun put(bitmap: Bitmap) {
    val config = bitmap.config ?: return bitmap.recycle().let { Unit }
    val bytes = bitmapBytes(bitmap)
    if (bytes > maxBytes) {
      bitmap.recycle()
      return
    }

    // If the pool is full, recycle instead of caching.
    if (currentBytes + bytes > maxBytes) {
      bitmap.recycle()
      return
    }

    val key = Key(bitmap.width, bitmap.height, config)
    val deque = pool.getOrPut(key) { ArrayDeque() }
    deque.addLast(bitmap)
    currentBytes += bytes
  }

  @Synchronized
  fun clear() {
    pool.values.flatten().forEach { it.recycle() }
    pool.clear()
    currentBytes = 0
  }
}

