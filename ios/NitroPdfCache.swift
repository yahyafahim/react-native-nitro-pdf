import Foundation
import PDFKit
import CryptoKit

enum NitroPdfCacheError: Error {
  case invalidSourceURL(String)
  case pdfLoadFailed(String)
  case missingPdfPage
}

/**
 Shared cache + metadata loading for both:
 - `PdfDocument.load(url:)`
 - `NitroPdf` view rendering
 */
enum NitroPdfCache {
  private static let cacheFolderName = "nitropdf"

  private static func cacheDirectory() throws -> URL {
    let caches = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    let dir = caches.appendingPathComponent(cacheFolderName, isDirectory: true)
    if !FileManager.default.fileExists(atPath: dir.path) {
      try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    return dir
  }

  private static func sha256Hex(_ input: String) -> String {
    let data = Data(input.utf8)
    let digest = SHA256.hash(data: data)
    return digest.map { String(format: "%02x", $0) }.joined()
  }

  private static func isHttpURL(_ urlString: String) -> Bool {
    urlString.hasPrefix("http://") || urlString.hasPrefix("https://")
  }

  private static func resolveToLocalFileURL(source: String) throws -> URL {
    // 1) Explicit file:// URL
    if let maybeUrl = URL(string: source), maybeUrl.isFileURL {
      return maybeUrl
    }

    // 2) Treat `source` as an existing local path
    let pathUrl = URL(fileURLWithPath: source)
    if FileManager.default.fileExists(atPath: pathUrl.path) {
      return pathUrl
    }

    // 3) Download/cached URL
    guard isHttpURL(source) else {
      throw NitroPdfCacheError.invalidSourceURL(source)
    }

    let cacheDir = try cacheDirectory()
    let cachedFile = cacheDir.appendingPathComponent("\(sha256Hex(source)).pdf")

    if FileManager.default.fileExists(atPath: cachedFile.path) {
      return cachedFile
    }

    try downloadToFile(urlString: source, destination: cachedFile)
    return cachedFile
  }

  private static func downloadToFile(urlString: String, destination: URL) throws {
    guard let remoteURL = URL(string: urlString) else {
      throw NitroPdfCacheError.invalidSourceURL(urlString)
    }

    let semaphore = DispatchSemaphore(value: 0)
    var capturedError: Error?

    let task = URLSession.shared.downloadTask(with: remoteURL) { tempLocation, response, error in
      defer { semaphore.signal() }

      if let error {
        capturedError = error
        return
      }

      if let httpResponse = response as? HTTPURLResponse, !(200...299).contains(httpResponse.statusCode) {
        capturedError = NitroPdfCacheError.pdfLoadFailed("HTTP \(httpResponse.statusCode)")
        return
      }

      guard let tempLocation else {
        capturedError = NitroPdfCacheError.pdfLoadFailed("Download failed")
        return
      }

      do {
        if FileManager.default.fileExists(atPath: destination.path) {
          try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: tempLocation, to: destination)
      } catch {
        capturedError = error
      }
    }

    task.resume()
    semaphore.wait()

    if let capturedError {
      throw capturedError
    }
  }

  static func clearCache() throws {
    let cacheDir = try cacheDirectory()
    let files = try FileManager.default.contentsOfDirectory(at: cacheDir, includingPropertiesForKeys: nil)
    for f in files {
      try? FileManager.default.removeItem(at: f)
    }
  }

  static func loadPDFDocumentAndMetadata(source: String) throws -> (PDFDocument, PdfMetadata) {
    let localURL = try resolveToLocalFileURL(source: source)

    guard let document = PDFDocument(url: localURL) else {
      throw NitroPdfCacheError.pdfLoadFailed("Failed to open PDF at \(localURL.path)")
    }

    let pageCount = document.pageCount
    guard let firstPage = document.page(at: 0) else {
      throw NitroPdfCacheError.missingPdfPage
    }

    let pageBounds = firstPage.bounds(for: .mediaBox)
    let pageSize = PdfPageSize(width: pageBounds.size.width, height: pageBounds.size.height)
    let aspectRatio = pageBounds.size.width / pageBounds.size.height

    let metadata = PdfMetadata(
      pageCount: Double(pageCount),
      pageSize: pageSize,
      aspectRatio: aspectRatio,
      filePath: localURL.path
    )

    return (document, metadata)
  }
}

