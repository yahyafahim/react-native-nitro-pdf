//
//  HybridNitroPdf.swift
//  Pods
//
//  Created by Yahya Fahim on 3/30/2026.
//

import Foundation
import UIKit
import PDFKit

class HybridNitroPdf : HybridNitroPdfSpec {
  private final class PdfContainerView: UIView {
    weak var owner: HybridNitroPdf?

    override func layoutSubviews() {
      super.layoutSubviews()
      owner?.updateScaleFactor()
    }
  }

  // UIView
  private let containerView = PdfContainerView()
  private let pdfView = PDFView()
  var view: UIView = UIView()

  // Props
  private var _source: String = ""
  var source: String {
    get { _source }
    set {
      guard _source != newValue else { return }
      _source = newValue
      startLoad()
    }
  }

  private var _fitPolicy: FitPolicy = .both
  var fitPolicy: FitPolicy {
    get { _fitPolicy }
    set {
      guard _fitPolicy != newValue else { return }
      _fitPolicy = newValue
      updateScaleFactor()
    }
  }

  private var _backgroundColor: String? = nil
  var backgroundColor: String? {
    get { _backgroundColor }
    set {
      _backgroundColor = newValue
      applyBackgroundColor()
    }
  }

  var onLoadSuccess: ((PdfMetadata) -> Void)? = nil
  var onLoadError: ((String) -> Void)? = nil

  private var currentMetadata: PdfMetadata?
  private var currentDocument: PDFDocument?
  private var loadGeneration: UInt64 = 0

  override init() {
    super.init()

    view = containerView
    containerView.owner = self

    pdfView.autoScales = true
    pdfView.displayMode = .singlePage
    pdfView.displayDirection = .vertical
    pdfView.displaysPageBreaks = false
    pdfView.backgroundColor = .clear
    pdfView.translatesAutoresizingMaskIntoConstraints = false

    containerView.addSubview(pdfView)
    NSLayoutConstraint.activate([
      pdfView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
      pdfView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
      pdfView.topAnchor.constraint(equalTo: containerView.topAnchor),
      pdfView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor),
    ])

    applyBackgroundColor()
  }

  private func applyBackgroundColor() {
    guard let colorString = backgroundColor,
          let color = Self.colorFromHex(colorString)
    else {
      // Ensure we don't keep default black bars from the scrollView.
      setScrollViewBackgroundColor(.clear)
      pdfView.backgroundColor = .clear
      return
    }

    pdfView.backgroundColor = color
    setScrollViewBackgroundColor(color)
  }

  private func setScrollViewBackgroundColor(_ color: UIColor) {
    // PDFKit internally uses a UIScrollView for scrolling.
    // We need to set its backgroundColor so letterbox bars are filled.
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      if let scrollView = self.findScrollView(in: self.pdfView) {
        scrollView.backgroundColor = color
      }
    }
  }

  private func findScrollView(in view: UIView) -> UIScrollView? {
    if let scrollView = view as? UIScrollView { return scrollView }
    for subview in view.subviews {
      if let found = findScrollView(in: subview) { return found }
    }
    return nil
  }

  private func startLoad() {
    let generation = loadGeneration &+ 1
    loadGeneration = generation

    let source = self.source

    // Parse + PDFKit document creation must not block the UI thread.
    DispatchQueue.global(qos: .userInitiated).async { [weak self] in
      guard let self else { return }

      do {
        let (document, metadata) = try NitroPdfCache.loadPDFDocumentAndMetadata(source: source)

        DispatchQueue.main.async {
          guard generation == self.loadGeneration else { return }
          self.currentDocument = document
          self.currentMetadata = metadata
          self.pdfView.document = document
          self.updateScaleFactor()
          self.onLoadSuccess?(metadata)
        }
      } catch {
        DispatchQueue.main.async {
          guard generation == self.loadGeneration else { return }
          self.onLoadError?(error.localizedDescription)
        }
      }
    }
  }

  private func updateScaleFactor() {
    guard let metadata = currentMetadata else { return }

    let pageW = metadata.pageSize.width
    let pageH = metadata.pageSize.height
    let viewW = Double(containerView.bounds.width)
    let viewH = Double(containerView.bounds.height)

    guard pageW > 0, pageH > 0, viewW > 0, viewH > 0 else { return }

    let widthScale = viewW / pageW
    let heightScale = viewH / pageH
    let aspect = metadata.aspectRatio

    let chosenScale: Double
    switch fitPolicy {
      case .width:
        chosenScale = widthScale
      case .height:
        chosenScale = heightScale
      case .both:
        // Use aspect ratio to choose which dimension limits the scaling.
        // This prevents the "black bar" alignment drift seen in react-native-pdf.
        let containerAspect = viewW / viewH
        chosenScale = containerAspect > aspect ? heightScale : widthScale
    }

    let scaleFactor = CGFloat(chosenScale)
    pdfView.minScaleFactor = scaleFactor
    pdfView.maxScaleFactor = scaleFactor
    pdfView.scaleFactor = scaleFactor
  }

  private static func colorFromHex(_ hex: String) -> UIColor? {
    var hexStr = hex.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    if hexStr.hasPrefix("#") {
      hexStr.removeFirst()
    }

    // Supports RRGGBB / RRGGBBAA / RGB
    switch hexStr.count {
      case 6, 8:
        guard let value = UInt64(hexStr, radix: 16) else { return nil }
        let r, g, b, a: UInt64
        if hexStr.count == 6 {
          r = (value >> 16) & 0xFF
          g = (value >> 8) & 0xFF
          b = value & 0xFF
          a = 0xFF
        } else {
          r = (value >> 24) & 0xFF
          g = (value >> 16) & 0xFF
          b = (value >> 8) & 0xFF
          a = value & 0xFF
        }

        return UIColor(
          red: CGFloat(r) / 255.0,
          green: CGFloat(g) / 255.0,
          blue: CGFloat(b) / 255.0,
          alpha: CGFloat(a) / 255.0
        )
      case 3:
        guard let value = UInt64(hexStr, radix: 16) else { return nil }
        // RGB -> RRGGBB
        let r = (value >> 8) & 0xF
        let g = (value >> 4) & 0xF
        let b = value & 0xF
        return UIColor(
          red: CGFloat(r) / 15.0,
          green: CGFloat(g) / 15.0,
          blue: CGFloat(b) / 15.0,
          alpha: 1.0
        )
      default:
        return nil
    }
  }
}
