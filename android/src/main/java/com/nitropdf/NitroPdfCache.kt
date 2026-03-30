package com.nitropdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.facebook.react.bridge.ReactApplicationContext
import com.margelo.nitro.NitroModules
import com.margelo.nitro.nitropdf.PdfMetadata
import com.margelo.nitro.nitropdf.PdfPageSize
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class NitroPdfCacheSourceType {
  LOCAL,
  REMOTE
}

/**
 * Smart caching + metadata extraction shared by:
 * - `HybridPdfDocument.load()`
 * - `HybridNitroPdf` view rendering
 */
object NitroPdfCache {
  private const val CACHE_FOLDER_NAME = "nitropdf"

  private fun sha256Hex(input: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) sb.append(String.format("%02x", b))
    return sb.toString()
  }

  private fun cacheDir(context: Context): File {
    val dir = File(context.cacheDir, CACHE_FOLDER_NAME)
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  private fun isHttpUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
  }

  private fun resolveToLocalFile(context: Context, url: String): Pair<UriFile, NitroPdfCacheSourceType> {
    // 1) file:// URLs
    if (url.startsWith("file://")) {
      val path = android.net.Uri.parse(url).path ?: throw IllegalArgumentException("Invalid file URL: $url")
      val f = File(path)
      require(f.exists()) { "File does not exist: ${f.absolutePath}" }
      return Pair(UriFile(f), NitroPdfCacheSourceType.LOCAL)
    }

    // 2) local path
    val local = File(url)
    if (local.exists()) {
      return Pair(UriFile(local), NitroPdfCacheSourceType.LOCAL)
    }

    // 3) remote URL
    require(isHttpUrl(url)) { "Invalid source (must be local path or http(s) URL): $url" }
    val cached = File(cacheDir(context), "${sha256Hex(url)}.pdf")
    if (cached.exists()) {
      return Pair(UriFile(cached), NitroPdfCacheSourceType.REMOTE)
    }

    downloadToFile(url, cached)
    return Pair(UriFile(cached), NitroPdfCacheSourceType.REMOTE)
  }

  data class UriFile(val file: File)

  private fun downloadToFile(url: String, destination: File) {
    val tmp = File(destination.parentFile, "${destination.name}.download")
    if (tmp.exists()) tmp.delete()

    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = 15000
      readTimeout = 30000
      instanceFollowRedirects = true
    }

    conn.connect()
    if (conn.responseCode !in 200..299) {
      throw RuntimeException("HTTP ${conn.responseCode} when downloading PDF: $url")
    }

    conn.inputStream.use { input ->
      tmp.outputStream().use { output ->
        val buffer = ByteArray(1024 * 64)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          output.write(buffer, 0, read)
        }
        output.flush()
      }
    }

    if (destination.exists()) destination.delete()
    if (!tmp.renameTo(destination)) {
      // fallback: copy/remove
      tmp.copyTo(destination, overwrite = true)
      tmp.delete()
    }
  }

  fun clearCache(context: Context) {
    val dir = File(cacheDir(context), CACHE_FOLDER_NAME)
    if (!dir.exists()) return
    dir.listFiles()?.forEach { it.delete() }
  }

  private fun metadataFromFile(context: Context, file: File): PdfMetadata {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    try {
      val pageCount = renderer.pageCount
      val page = renderer.openPage(0)
      try {
        val width = page.width.toDouble()
        val height = page.height.toDouble()
        val aspectRatio = width / height
        val pageSize = PdfPageSize(width = width, height = height)
        return PdfMetadata(
          pageCount = pageCount.toDouble(),
          pageSize = pageSize,
          aspectRatio = aspectRatio,
          filePath = file.absolutePath,
        )
      } finally {
        page.close()
      }
    } finally {
      renderer.close()
      pfd.close()
    }
  }

  /**
   * Load metadata for `url`, downloading to the app cache directory if needed.
   */
  fun loadMetadata(context: Context, url: String): PdfMetadata {
    val (resolved, _) = resolveToLocalFile(context, url)
    return metadataFromFile(context, resolved.file)
  }

  fun fileForSource(context: Context, url: String): File {
    val (resolved, _) = resolveToLocalFile(context, url)
    return resolved.file
  }

  /**
   * Convenience overload using Nitro's app context.
   */
  fun loadMetadata(url: String): PdfMetadata {
    val ctx = NitroModules.applicationContext
      ?: throw IllegalStateException("NitroModules.applicationContext is null")
    return loadMetadata(ctx, url)
  }

  fun fileForSource(url: String): File {
    val ctx = NitroModules.applicationContext
      ?: throw IllegalStateException("NitroModules.applicationContext is null")
    return fileForSource(ctx, url)
  }
}

