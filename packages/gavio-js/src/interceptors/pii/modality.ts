/**
 * ModalityScanner + modalityGuard (F-SEC-09) — image PII detection.
 *
 * Extends the PII pipeline to image inputs (`request.images`). Each
 * ModalityScanner extracts text (OCR) and/or direct detections (e.g. faces);
 * extracted text is run through the standard tier-1 PII text scanners. Detected
 * entity types are recorded on the context, so they land in the AuditRecord's
 * `piiEntityTypes`. Images are scanned in the `before` hook — before any
 * provider call.
 */

import type { InterceptorContext } from '../../context.js'
import { PiiBlockedError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { Interceptor } from '../base.js'
import { ScanContext } from './context.js'
import type { PiiScanner } from './scanner.js'
import { defaultScanners } from './scanners/index.js'

export interface ModalityScanResult {
  /** OCR-extracted text (empty when none). Fed to the text PII scanners. */
  text: string
  /** Direct entity detections, e.g. `['FACE']` from face detection. */
  entityTypes: string[]
}

/** Detects PII in a non-text modality (images today; audio/video later). */
export interface ModalityScanner {
  readonly name: string
  scan(image: Uint8Array): ModalityScanResult | Promise<ModalityScanResult>
}

export type ModalityAction = 'tag' | 'block'

export interface ModalityGuardOptions {
  /** Modality scanners run over each image (e.g. `ocrModalityScanner()`). */
  scanners: ModalityScanner[]
  /** Text PII scanners applied to OCR-extracted text (default: the tier-1 set). */
  textScanners?: PiiScanner[]
  /** `'tag'` records detections (default); `'block'` also raises PiiBlockedError. */
  onDetect?: ModalityAction
}

/** Scan `request.images` for PII before the provider call (F-SEC-09). */
export function modalityGuard(options: ModalityGuardOptions): Interceptor {
  const { scanners, onDetect = 'tag' } = options
  const textScanners = options.textScanners ?? defaultScanners()
  return {
    name: 'modality_guard',
    async before(request: GavioRequest, ctx: InterceptorContext): Promise<GavioRequest> {
      if (request.images.length === 0) return request
      const found = new Set<string>()
      for (const image of request.images) {
        for (const scanner of scanners) {
          const result = await scanner.scan(image)
          for (const entityType of result.entityTypes) found.add(entityType)
          if (result.text) {
            const scanCtx = new ScanContext()
            for (const ts of textScanners) {
              const matches = await ts.scan(result.text, scanCtx)
              if (matches.length > 0) found.add(ts.entityType)
            }
          }
        }
      }
      if (found.size > 0) {
        ctx.recordPii([...found])
        if (onDetect === 'block') throw new PiiBlockedError([...found].sort())
      }
      return request
    },
  }
}

/**
 * Reference OCR ModalityScanner backed by the optional `tesseract.js` dependency.
 *
 * Extracts text from an image for the text PII scanners; performs no face
 * detection. Throws a clear error if the optional dependency is not installed.
 */
export function ocrModalityScanner(options: { lang?: string } = {}): ModalityScanner {
  const lang = options.lang ?? 'eng'
  return {
    name: 'ocr',
    async scan(image: Uint8Array): Promise<ModalityScanResult> {
      const spec = 'tesseract.js'
      let mod: { recognize(img: unknown, lang: string): Promise<{ data?: { text?: string } }> }
      try {
        mod = await import(spec)
      } catch {
        throw new Error(
          "ocrModalityScanner requires the optional 'tesseract.js' dependency — " +
            'install it to enable image OCR',
        )
      }
      const result = await mod.recognize(image, lang)
      return { text: result.data?.text ?? '', entityTypes: [] }
    },
  }
}
