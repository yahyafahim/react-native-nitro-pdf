# react-native-nitro-pdf

react-native-nitro-pdf is a react native package built with Nitro

[![Version](https://img.shields.io/npm/v/react-native-nitro-pdf.svg)](https://www.npmjs.com/package/react-native-nitro-pdf)
[![Downloads](https://img.shields.io/npm/dm/react-native-nitro-pdf.svg)](https://www.npmjs.com/package/react-native-nitro-pdf)
[![License](https://img.shields.io/npm/l/react-native-nitro-pdf.svg)](https://github.com/patrickkabwe/react-native-nitro-pdf/LICENSE)

## Requirements

- React Native v0.76.0 or higher
- Node 18.0.0 or higher

> [!IMPORTANT]  
> To Support `Nitro Views` you need to install React Native version v0.78.0 or higher.

## Installation

```bash
bun add react-native-nitro-pdf react-native-nitro-modules
```

## Usage

### Import

```ts
import { NitroPdf, PdfDocument } from 'react-native-nitro-pdf'
```

### `NitroPdf` (view)

Render a PDF by passing a local file path or an `http(s)` URL.

```tsx
import React from 'react'
import { NitroPdf } from 'react-native-nitro-pdf'
import { View, StyleSheet } from 'react-native'

export function Example() {
  return (
    <View style={styles.container}>
      <NitroPdf
        source="https://example.com/document.pdf"
        fitPolicy="both"
        backgroundColor="#F5F5F5"
        onLoadSuccess={(metadata) => {
          console.log('pageCount', metadata.pageCount)
          console.log('aspectRatio', metadata.aspectRatio)
        }}
        onLoadError={(message) => {
          console.warn('Failed to load PDF:', message)
        }}
      />
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    height: 400,
    width: 300,
  },
})
```

Props:

- `source: string` - Local file path or `http(s)` URL to the PDF.
- `fitPolicy: 'width' | 'height' | 'both'` - How the PDF is fit inside the view (letterboxed using the computed page aspect ratio).
- `backgroundColor?: string` - Background for the letterbox bars (examples: `'#FFFFFF'`, `'#AARRGGBB'`).
- `onLoadSuccess?: (metadata: PdfMetadata) => void` - Called after the PDF is opened and metadata is extracted.
- `onLoadError?: (message: string) => void` - Called when downloading/opening/parsing fails.

`PdfMetadata` includes:

- `pageCount: number`
- `pageSize: { width: number; height: number }`
- `aspectRatio: number`
- `filePath: string` - Resolved local path (cached/downloaded when needed)

### `PdfDocument` (object)

Use `PdfDocument` to pre-load metadata and manage the on-device cache.

```ts
import { PdfDocument } from 'react-native-nitro-pdf'

const doc = new PdfDocument()

async function preload() {
  const metadata = await doc.load('https://example.com/document.pdf')
  console.log(metadata.pageCount)
}

function clear() {
  doc.clearCache()
}
```

## Credits

Bootstrapped with [create-nitro-module](https://github.com/patrickkabwe/create-nitro-module).

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.
