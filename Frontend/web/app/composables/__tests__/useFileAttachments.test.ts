import { describe, expect, it, vi } from 'vitest'
import type { ChatDocument } from '../useChatApi'
import type { DocumentUploadError } from '../useFileAttachments'
import { waitForDocumentsReady } from '../useFileAttachments'

const document = (
  id: number,
  status: ChatDocument['status'],
  overrides: Partial<ChatDocument> = {}
): ChatDocument => ({
  id,
  filename: `document-${id}.pdf`,
  mimeType: 'application/pdf',
  byteSize: 100,
  status,
  ...overrides
})

describe('waitForDocumentsReady', () => {
  it('returns immediately when every uploaded document is already ready', async () => {
    const ready = [document(1, 'READY'), document(2, 'READY')]
    const fetchDocuments = vi.fn()

    await expect(waitForDocumentsReady('chat-1', ready, fetchDocuments)).resolves.toEqual(ready)
    expect(fetchDocuments).not.toHaveBeenCalled()
  })

  it('waits until every document in the uploaded batch is ready', async () => {
    let now = 0
    const fetchDocuments = vi.fn().mockResolvedValue([
      document(1, 'READY'),
      document(2, 'READY')
    ])

    await expect(waitForDocumentsReady(
      'chat-1',
      [document(1, 'READY'), document(2, 'PROCESSING')],
      fetchDocuments,
      {
        now: () => now,
        sleep: async delayMs => { now += delayMs }
      }
    )).resolves.toEqual([document(1, 'READY'), document(2, 'READY')])
    expect(fetchDocuments).toHaveBeenCalledOnce()
    expect(fetchDocuments).toHaveBeenCalledWith('chat-1')
  })

  it('aborts the batch with the backend error code when processing fails', async () => {
    const failed = document(1, 'FAILED', { errorCode: 'DOCUMENT_EMPTY' })

    await expect(waitForDocumentsReady('chat-1', [failed], vi.fn())).rejects.toMatchObject({
      name: 'DocumentUploadError',
      code: 'DOCUMENT_EMPTY',
      documentName: 'document-1.pdf'
    } satisfies Partial<DocumentUploadError>)
  })

  it('stops polling at the processing deadline', async () => {
    let now = 0
    const fetchDocuments = vi.fn().mockResolvedValue([document(1, 'PROCESSING')])

    await expect(waitForDocumentsReady(
      'chat-1',
      [document(1, 'PROCESSING')],
      fetchDocuments,
      {
        timeoutMs: 250,
        pollIntervalMs: 100,
        now: () => now,
        sleep: async delayMs => { now += delayMs }
      }
    )).rejects.toMatchObject({
      name: 'DocumentUploadError',
      code: 'DOCUMENT_PROCESSING_TIMEOUT'
    } satisfies Partial<DocumentUploadError>)
    expect(fetchDocuments).toHaveBeenCalledTimes(3)
  })

  it('tolerates a transient polling error without sending early', async () => {
    let now = 0
    const fetchDocuments = vi.fn()
      .mockRejectedValueOnce(new Error('temporary network failure'))
      .mockResolvedValueOnce([document(1, 'READY')])

    await expect(waitForDocumentsReady(
      'chat-1',
      [document(1, 'PROCESSING')],
      fetchDocuments,
      {
        now: () => now,
        sleep: async delayMs => { now += delayMs }
      }
    )).resolves.toEqual([document(1, 'READY')])
    expect(fetchDocuments).toHaveBeenCalledTimes(2)
  })
})
