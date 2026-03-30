package com.nitropdf

import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitropdf.HybridPdfDocumentSpec
import com.margelo.nitro.nitropdf.PdfMetadata

class HybridPdfDocument : HybridPdfDocumentSpec() {
  override fun load(url: String): Promise<PdfMetadata> {
    return Promise.parallel {
      val ctx = NitroModules.applicationContext
        ?: throw IllegalStateException("NitroModules.applicationContext is null")
      NitroPdfCache.loadMetadata(ctx, url)
    }
  }

  override fun clearCache(): Unit {
    val ctx = NitroModules.applicationContext
      ?: throw IllegalStateException("NitroModules.applicationContext is null")
    NitroPdfCache.clearCache(ctx)
  }
}

