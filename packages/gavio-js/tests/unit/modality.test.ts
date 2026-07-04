import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Gateway } from '../../src/gateway.js'
import { modalityGuard, type ModalityScanner } from '../../src/interceptors/pii/index.js'
import { auditInterceptor } from '../../src/interceptors/audit/index.js'
import { PiiBlockedError } from '../../src/errors.js'
import { mockProvider } from '../../src/providers/mock.js'
import type { AuditSink } from '../../src/interceptors/audit/sink.js'

const IMG = new Uint8Array([1, 2, 3])
const noopSink: AuditSink = { async write() {} }

function stub(text: string, entityTypes: string[] = []): ModalityScanner {
  return { name: 'stub', scan: () => ({ text, entityTypes }) }
}

function gw(scanner: ModalityScanner, onDetect?: 'tag' | 'block') {
  return new Gateway({ model: 'mock' })
    .withAdapter(mockProvider({ response: 'ok' }))
    .use(auditInterceptor({ sink: noopSink }))
    .use(modalityGuard({ scanners: [scanner], onDetect }))
}

async function detect(scanner: ModalityScanner): Promise<string[]> {
  const res = await gw(scanner).complete({ messages: [{ role: 'user', content: 'q' }], images: [IMG] })
  return [...(res.audit?.piiEntityTypes ?? [])].sort()
}

describe('modalityGuard (F-SEC-09)', () => {
  it('records OCR-extracted text PII in the audit record', async () => {
    expect(await detect(stub('contact jan.devries@example.com'))).toEqual(['EMAIL'])
  })

  it('records direct detections such as FACE', async () => {
    expect(await detect(stub('', ['FACE']))).toEqual(['FACE'])
  })

  it('unions OCR text PII with direct detections', async () => {
    expect(await detect(stub('mail a@b.com', ['FACE']))).toEqual(['EMAIL', 'FACE'])
  })

  it('records nothing for a clean image', async () => {
    expect(await detect(stub('a sunset over the mountains'))).toEqual([])
  })

  it('is a no-op when the request carries no images', async () => {
    const res = await gw(stub('', ['FACE'])).complete({ messages: [{ role: 'user', content: 'q' }] })
    expect(res.audit?.piiEntityTypes ?? []).toEqual([])
  })

  it('blocks with PiiBlockedError when onDetect=block and PII is found', async () => {
    const g = gw(stub('', ['FACE']), 'block')
    await expect(
      g.complete({ messages: [{ role: 'user', content: 'q' }], images: [IMG] }),
    ).rejects.toBeInstanceOf(PiiBlockedError)
  })
})

describe('shared test-vectors — pii/image-detection.json', () => {
  const url = new URL('../../../../test-vectors/pii/image-detection.json', import.meta.url)
  const vectors = JSON.parse(readFileSync(fileURLToPath(url), 'utf8')) as {
    cases: Array<{ id: string; ocrText: string; entityTypes: string[]; expectedTypes: string[] }>
  }
  for (const c of vectors.cases) {
    it(`${c.id}`, async () => {
      expect(await detect(stub(c.ocrText, c.entityTypes))).toEqual(c.expectedTypes)
    })
  }
})
