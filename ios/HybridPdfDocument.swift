import Foundation
import NitroModules

class HybridPdfDocument: HybridPdfDocumentSpec {
  func load(url: String) throws -> Promise<PdfMetadata> {
    return Promise.parallel {
      let (_, metadata) = try NitroPdfCache.loadPDFDocumentAndMetadata(source: url)
      return metadata
    }
  }

  func clearCache() throws -> Void {
    try NitroPdfCache.clearCache()
  }
}

