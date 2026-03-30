import {
  getHostComponent,
  getHybridObjectConstructor,
  type HybridRef,
} from 'react-native-nitro-modules'
import NitroPdfConfig from '../nitrogen/generated/shared/json/NitroPdfConfig.json'
import type {
  NitroPdfProps,
  NitroPdfMethods,
  PdfDocument as PdfDocumentType,
} from './specs/nitro-pdf.nitro'


export const NitroPdf = getHostComponent<NitroPdfProps, NitroPdfMethods>(
  'NitroPdf',
  () => NitroPdfConfig
)

export type NitroPdfRef = HybridRef<NitroPdfProps, NitroPdfMethods>

export const PdfDocument = getHybridObjectConstructor<PdfDocumentType>('PdfDocument')
