package com.nitropdf

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.view.View
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.nitropdf.HybridNitroPdfSpec
import com.margelo.nitro.NitroModules
import com.margelo.nitro.nitropdf.FitPolicy
import com.margelo.nitro.nitropdf.PdfMetadata
import com.margelo.nitro.nitropdf.PdfPageSize
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Keep
@DoNotStrip
class HybridNitroPdf(val context: ThemedReactContext): HybridNitroPdfSpec() {
    // View
    private val scrollView = ScrollView(context).apply {
      isVerticalScrollBarEnabled = false
      overScrollMode = ScrollView.OVER_SCROLL_NEVER
      setBackgroundColor(Color.TRANSPARENT)
    }

    private val contentLayout = FrameLayout(context).apply {
      // We'll absolutely position a small set of ImageViews (visible pages + buffer).
      clipToPadding = false
      clipChildren = false
    }

    override val view: View = scrollView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private val bitmapBudgetBytes = (Runtime.getRuntime().maxMemory() / 8).toLong()
    private val bitmapPool = BitmapPool(maxBytes = bitmapBudgetBytes)

    // Props
    private var _source: String = ""
    private var sourceDirty: Boolean = false
    override var source: String
      get() = _source
      set(value) {
        if (_source != value) {
          _source = value
          sourceDirty = true
        }
      }

    private var _fitPolicy: FitPolicy = FitPolicy.BOTH
    private var fitPolicyDirty: Boolean = false
    override var fitPolicy: FitPolicy
      get() = _fitPolicy
      set(value) {
        if (_fitPolicy != value) {
          _fitPolicy = value
          fitPolicyDirty = true
        }
      }

    private var _backgroundColor: String? = null
    override var backgroundColor: String?
      get() = _backgroundColor
      set(value) {
        _backgroundColor = value
        applyBackgroundColor()
      }

    override var onLoadSuccess: ((metadata: PdfMetadata) -> Unit)? = null
    override var onLoadError: ((message: String) -> Unit)? = null

    // PDF rendering state
    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var metadata: PdfMetadata? = null
    private var pageCount: Int = 0
    private var aspectRatio: Double = 1.0

    // Layout derived from metadata + view size + fitPolicy
    private var pageHeightPx: Int = 0
    private var contentWidthPx: Int = 0
    private var contentHeightPx: Int = 0

    // Only keep ImageViews for the pages currently in (visible + buffer) range.
    private val renderedPages: MutableMap<Int, ImageView> = HashMap()
    private val renderedBitmaps: MutableMap<Int, Bitmap> = HashMap()
    private val inFlightPages: MutableSet<Int> = HashSet()

    private var renderGeneration: Long = 0

    init {
      scrollView.addView(
        contentLayout,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      )

      scrollView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val widthChanged = (right - left) != (oldRight - oldLeft)
        val heightChanged = (bottom - top) != (oldBottom - oldTop)
        if (widthChanged || heightChanged) {
          onSizeChangedInternal()
        }
      }
    }

    override fun afterUpdate() {
      if (sourceDirty) {
        sourceDirty = false
        reloadPdf()
        return
      }

      if (fitPolicyDirty) {
        fitPolicyDirty = false
        invalidateLayoutAndRerender(reason = "fitPolicy changed")
      }
    }

    private fun applyBackgroundColor() {
      val color = try {
        _backgroundColor?.let { Color.parseColor(it) } ?: Color.TRANSPARENT
      } catch {
        Color.TRANSPARENT
      }
      mainHandler.post {
        scrollView.setBackgroundColor(color)
        contentLayout.setBackgroundColor(color)
      }
    }

    private fun onSizeChangedInternal() {
      // When the RN view bounds change, we need to recompute the letterboxed content size.
      if (metadata == null) return
      invalidateLayoutAndRerender(reason = "size changed")
    }

    private fun invalidateLayoutAndRerender(reason: String) {
      // Bump generation so in-flight rendering jobs don't accidentally update stale views.
      renderGeneration += 1
      val generation = renderGeneration

      // Clear currently visible bitmaps immediately on the UI thread.
      mainHandler.post {
        if (generation != renderGeneration) return@post
        clearRenderedPages()
        relayoutContent()
        updateDesiredPages()
      }
    }

    private fun relayoutContent() {
      val md = metadata ?: return
      if (scrollView.width <= 0 || scrollView.height <= 0) return

      aspectRatio = md.aspectRatio

      val containerW = scrollView.width
      val containerH = scrollView.height

      val (wPx, hPx) = computeContentSize(
        containerW = containerW,
        containerH = containerH,
        aspectRatio = aspectRatio,
        fitPolicy = fitPolicy,
      )
      contentWidthPx = wPx.coerceAtLeast(1)
      contentHeightPx = hPx.coerceAtLeast(1)

      // Safety cap: prevent single huge bitmaps from causing OOM.
      val (cappedW, cappedH) = capToBitmapBudget(contentWidthPx, contentHeightPx)
      contentWidthPx = cappedW
      contentHeightPx = cappedH

      // Each page is stacked with its scaled height, so pages never overlap.
      pageHeightPx = contentHeightPx

      // The scroll container's content height must be pageCount * pageHeightPx
      val totalHeight = pageCount * pageHeightPx
      val lp = contentLayout.layoutParams
      lp.height = totalHeight
      contentLayout.layoutParams = lp
    }

    private fun capToBitmapBudget(w: Int, h: Int): Pair<Int, Int> {
      // ARGB_8888 is 4 bytes per pixel.
      val bytesNeeded = w.toLong() * h.toLong() * 4L
      if (bytesNeeded <= bitmapBudgetBytes) return Pair(w, h)

      val maxPixels = bitmapBudgetBytes / 4L
      if (maxPixels <= 0L) return Pair(1, 1)

      val currentPixels = w.toLong() * h.toLong()
      val scale = sqrt(maxPixels.toDouble() / currentPixels.toDouble())
      val newW = max(1, (w.toDouble() * scale).toInt())
      val newH = max(1, (h.toDouble() * scale).toInt())
      return Pair(newW, newH)
    }

    private fun computeContentSize(
      containerW: Int,
      containerH: Int,
      aspectRatio: Double,
      fitPolicy: FitPolicy,
    ): Pair<Int, Int> {
      val containerAspect = containerW.toDouble() / containerH.toDouble()
      return when (fitPolicy) {
        FitPolicy.WIDTH -> {
          val w = containerW
          val h = (w.toDouble() / aspectRatio).toInt()
          Pair(w, h)
        }
        FitPolicy.HEIGHT -> {
          val h = containerH
          val w = (h.toDouble() * aspectRatio).toInt()
          Pair(w, h)
        }
        FitPolicy.BOTH -> {
          // Contain (aspect-fit): choose the limiting dimension using aspect ratio.
          if (containerAspect > aspectRatio) {
            val h = containerH
            val w = (h.toDouble() * aspectRatio).toInt()
            Pair(w, h)
          } else {
            val w = containerW
            val h = (w.toDouble() / aspectRatio).toInt()
            Pair(w, h)
          }
        }
      }
    }

    private fun clearRenderedPages() {
      for ((_, imageView) in renderedPages) {
        contentLayout.removeView(imageView)
      }
      renderedPages.clear()

      for ((_, bmp) in renderedBitmaps) {
        bitmapPool.put(bmp)
      }
      renderedBitmaps.clear()
      inFlightPages.clear()
    }

    private fun reloadPdf() {
      val source = this.source
      val generation = renderGeneration + 1
      renderGeneration = generation

      clearRenderedPages()

      renderExecutor.execute {
        // Resolve + open renderer off the UI thread.
        val appContext = NitroModules.applicationContext
          ?: throw IllegalStateException("NitroModules.applicationContext is null")

        try {
          // 1) Smart caching + metadata extraction
          val file = NitroPdfCache.fileForSource(appContext, source)
          val newPfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
          val renderer = PdfRenderer(newPfd)

          // 2) Extract first page size to compute aspect ratio
          val page = renderer.openPage(0)
          try {
            val width = page.width.toDouble()
            val height = page.height.toDouble()
            val aspect = width / height

            val md = PdfMetadata(
              pageCount = renderer.pageCount.toDouble(),
              pageSize = PdfPageSize(width = width, height = height),
              aspectRatio = aspect,
              filePath = file.absolutePath,
            )

            if (generation != renderGeneration) {
              // Stale load, dispose what we opened.
              renderer.close()
              return@execute
            }

            // Close previous renderer (if any) after we know this generation is current.
            pfd?.close()
            pdfRenderer?.close()
            pfd = newPfd
            pdfRenderer = renderer
            metadata = md
            pageCount = renderer.pageCount

            mainHandler.post {
              if (generation != renderGeneration) {
                return@post
              }
              metadata = md
              relayoutContent()
              onLoadSuccess?.invoke(md)
              updateDesiredPages()
            }
          } finally {
            // Ensure page is always closed even if we bailed early.
            // (If generation is stale, we might already have closed it above.)
            try { page.close() } catch (_: Throwable) {}
          }
        } catch (e: Throwable) {
          mainHandler.post {
            if (generation != renderGeneration) return@post
            onLoadError?.invoke(e.localizedMessage ?: "Failed to load PDF")
          }
        }
      }
    }

    // NOTE: Must be called on UI thread
    private fun updateDesiredPages() {
      val md = metadata ?: return
      if (pageHeightPx <= 0) return
      if (pdfRenderer == null) return
      if (scrollView.height <= 0) return
      if (pageCount <= 0) return

      val buffer = 2
      val y = scrollView.scrollY
      val first = (y / pageHeightPx).toInt().coerceIn(0, pageCount - 1)
      val last = ((y + scrollView.height) / pageHeightPx).toInt().coerceIn(0, pageCount - 1)
      val start = max(0, first - buffer)
      val end = min(pageCount - 1, last + buffer)

      // Recycle pages outside desired range.
      val toRemove = renderedPages.keys.filter { it < start || it > end }
      toRemove.forEach { pageIndex ->
        val imageView = renderedPages.remove(pageIndex) ?: return@forEach
        contentLayout.removeView(imageView)
        val bmp = renderedBitmaps.remove(pageIndex)
        if (bmp != null) {
          bitmapPool.put(bmp)
        }
      }

      // Render desired pages.
      for (pageIndex in start..end) {
        if (renderedPages.containsKey(pageIndex)) continue
        if (inFlightPages.contains(pageIndex)) continue

        ensureImageView(pageIndex)
        inFlightPages.add(pageIndex)
        scheduleRender(pageIndex, renderGeneration)
      }
    }

    private fun ensureImageView(pageIndex: Int) {
      val existing = renderedPages[pageIndex]
      if (existing != null) return
      val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.TRANSPARENT)
      }

      val left = (scrollView.width - contentWidthPx) / 2
      val top = pageIndex * pageHeightPx

      val lp = FrameLayout.LayoutParams(contentWidthPx, contentHeightPx).apply {
        this.leftMargin = left
        this.topMargin = top
      }
      contentLayout.addView(imageView, lp)
      renderedPages[pageIndex] = imageView
    }

    private fun scheduleRender(pageIndex: Int, generation: Long) {
      val desiredW = contentWidthPx
      val desiredH = contentHeightPx

      // Render off the UI thread.
      renderExecutor.execute {
        val renderer = pdfRenderer ?: return@execute
        var bitmap: Bitmap? = null
        try {
          val page = renderer.openPage(pageIndex)
          bitmap = bitmapPool.get(desiredW, desiredH, Bitmap.Config.ARGB_8888)
          try {
            page.render(bitmap!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          } finally {
            page.close()
          }

          mainHandler.post {
            val bmp = bitmap
            // If this render is stale, recycle.
            if (generation != renderGeneration) {
              bmp?.let { bitmapPool.put(it) }
              inFlightPages.remove(pageIndex)
              return@post
            }

            val imageView = renderedPages[pageIndex]
            if (imageView == null) {
              bmp?.let { bitmapPool.put(it) }
              inFlightPages.remove(pageIndex)
              return@post
            }

            val finalBitmap = bmp
            if (finalBitmap == null) {
              inFlightPages.remove(pageIndex)
              return@post
            }

            imageView.setImageBitmap(finalBitmap)
            renderedBitmaps[pageIndex] = finalBitmap
            inFlightPages.remove(pageIndex)
          }
        } catch (e: Throwable) {
          bitmap?.let { bitmapPool.put(it) }
          mainHandler.post {
            inFlightPages.remove(pageIndex)
          }
        }
      }
    }

    override fun onDropView() {
      super.onDropView()
      try {
        renderGeneration += 1
        clearRenderedPages()
        renderExecutor.shutdownNow()
      } catch (_: Throwable) { }

      try { pfd?.close() } catch (_: Throwable) { }
      try { pdfRenderer?.close() } catch (_: Throwable) { }
      pfd = null
      pdfRenderer = null

      try { bitmapPool.clear() } catch (_: Throwable) { }
    }
}
