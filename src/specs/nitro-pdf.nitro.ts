import {
  type HybridObject,
  type HybridView,
  type HybridViewProps,
  type HybridViewMethods,
} from 'react-native-nitro-modules'

export interface PdfMetadata {
  pageCount: number
  pageSize: PdfPageSize
  /**
   * width / height, used for deterministic letterboxing.
   * Matches the aspect ratio of a single page from the PDF file.
   */
  aspectRatio: number
  filePath: string // The local path after downloading/caching
}

export interface PdfPageSize {
  width: number
  height: number
}

export type FitPolicy = 'width' | 'height' | 'both'

export interface PdfDocument extends HybridObject<{
  ios: 'swift'
  android: 'kotlin'
}> {
  /** Loads and caches the PDF. Returns metadata for UI preparation. */
  load(url: string): Promise<PdfMetadata>
  /** Clears cached PDFs to manage storage. */
  clearCache(): void
}

export interface PdfViewProps extends HybridViewProps {
  source: string // The local file path or URL
  fitPolicy: FitPolicy
  /** Background color of the container (replaces those black bars) */
  backgroundColor?: string
  onLoadSuccess?: (metadata: PdfMetadata) => void
  onLoadError?: (message: string) => void
}

export interface NitroPdfProps extends PdfViewProps {}

export interface NitroPdfMethods extends HybridViewMethods {}

export type NitroPdf = HybridView<
  NitroPdfProps,
  NitroPdfMethods,
  {
    ios: 'swift'
    android: 'kotlin'
  }
>
