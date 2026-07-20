import type {
  ChatCapabilities,
  ChatDocument,
  DocumentAttachment,
  MediaAttachment
} from '~/composables/useChatApi'

type FileAttachmentOptions = {
  fileReadFailedMessage: (name: string) => string
  onUploadFailed: (reason: unknown, documentName?: string) => void
  limits: () => ChatCapabilities
  attachmentLimitMessage: (limit: number) => string
  attachmentTooLargeMessage: (name: string, limit: number) => string
  attachmentTotalTooLargeMessage: (limit: number) => string
  documentTooLargeMessage: (name: string, limit: number) => string
}

const MAX_CONCURRENT_FILE_READS = 2
export const DOCUMENT_PROCESSING_POLL_INTERVAL_MS = 1_000
export const DOCUMENT_PROCESSING_TIMEOUT_MS = 60_000

type DocumentPollingOptions = {
  pollIntervalMs?: number
  timeoutMs?: number
  maxConsecutivePollErrors?: number
  now?: () => number
  sleep?: (delayMs: number) => Promise<void>
}

export class DocumentUploadError extends Error {
  readonly code: string
  readonly documentName: string | null

  constructor(code: string, documentName?: string | null) {
    super(code)
    this.name = 'DocumentUploadError'
    this.code = code
    this.documentName = documentName?.trim() || null
  }
}

const defaultSleep = (delayMs: number) => new Promise<void>(resolve => {
  setTimeout(resolve, delayMs)
})

/**
 * Waits for every document returned by the asynchronous upload endpoint to
 * become usable by RAG. The list endpoint is polled once for the whole batch,
 * so attaching multiple documents does not multiply request volume.
 */
export async function waitForDocumentsReady(
  chatId: string,
  uploadedDocuments: ChatDocument[],
  fetchDocuments: (chatId: string) => Promise<ChatDocument[]>,
  options: DocumentPollingOptions = {}
): Promise<ChatDocument[]> {
  if (uploadedDocuments.length === 0) return []

  const pollIntervalMs = Math.max(
    1,
    options.pollIntervalMs ?? DOCUMENT_PROCESSING_POLL_INTERVAL_MS
  )
  const timeoutMs = Math.max(1, options.timeoutMs ?? DOCUMENT_PROCESSING_TIMEOUT_MS)
  const maxConsecutivePollErrors = Math.max(1, options.maxConsecutivePollErrors ?? 3)
  const now = options.now ?? Date.now
  const sleep = options.sleep ?? defaultSleep
  const deadline = now() + timeoutMs
  let currentDocuments = uploadedDocuments
  let consecutivePollErrors = 0

  while (true) {
    const failed = currentDocuments.find(document => document.status.toUpperCase() === 'FAILED')
    if (failed) {
      throw new DocumentUploadError(
        failed.errorCode?.trim() || 'DOCUMENT_PROCESSING_FAILED',
        failed.filename
      )
    }
    if (currentDocuments.every(document => document.status.toUpperCase() === 'READY')) {
      return currentDocuments
    }

    const remainingMs = deadline - now()
    if (remainingMs <= 0) {
      throw new DocumentUploadError('DOCUMENT_PROCESSING_TIMEOUT')
    }
    await sleep(Math.min(pollIntervalMs, remainingMs))

    try {
      const refreshed = await fetchDocuments(chatId)
      const refreshedById = new Map(refreshed.map(document => [document.id, document]))
      currentDocuments = currentDocuments.map(
        document => refreshedById.get(document.id) ?? document
      )
      consecutivePollErrors = 0
    } catch (error) {
      consecutivePollErrors++
      if (consecutivePollErrors >= maxConsecutivePollErrors) throw error
    }
  }
}

export function useFileAttachments(options: FileAttachmentOptions) {
  const attachments = ref<MediaAttachment[]>([])
  const documentAttachments = ref<DocumentAttachment[]>([])
  const fileInput = ref<HTMLInputElement | null>(null)
  const isDragOver = ref(false)
  let attachmentGeneration = 0
  let processingChain: Promise<void> = Promise.resolve()

  const toDataUrl = (file: File) =>
    new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
      reader.onerror = () => reject(new Error(options.fileReadFailedMessage(file.name)))
      reader.readAsDataURL(file)
    })

  const clearAttachments = () => {
    attachmentGeneration++
    attachments.value = []
    documentAttachments.value = []
    if (fileInput.value) {
      fileInput.value.value = ''
    }
  }

  const removeAttachment = (index: number) => {
    attachments.value.splice(index, 1)
  }

  const removeDocumentAttachment = (index: number) => {
    documentAttachments.value.splice(index, 1)
  }

  const isDocumentFile = (file: File) => {
    const mimeType = file.type || 'application/octet-stream'
    const name = file.name.toLowerCase()
    return mimeType === 'application/pdf' ||
      mimeType.startsWith('text/') ||
      mimeType === 'application/json' ||
      mimeType === 'application/xml' ||
      mimeType === 'application/x-ndjson' ||
      mimeType === 'application/yaml' ||
      name.endsWith('.pdf') ||
      name.endsWith('.txt') ||
      name.endsWith('.md') ||
      name.endsWith('.markdown') ||
      name.endsWith('.json') ||
      name.endsWith('.xml') ||
      name.endsWith('.ndjson') ||
      name.endsWith('.yaml') ||
      name.endsWith('.yml') ||
      name.endsWith('.log') ||
      name.endsWith('.csv')
  }

  const processFileListNow = async (files: File[], generation: number) => {
    if (files.length === 0) return
    if (generation !== attachmentGeneration) return
    const limits = options.limits()
    const availableSlots = Math.max(
      0,
      limits.maxAttachments - attachments.value.length - documentAttachments.value.length
    )
    if (files.length > availableSlots) {
      options.onUploadFailed(new Error(options.attachmentLimitMessage(limits.maxAttachments)))
    }
    const candidates = files.slice(0, availableSlots)
    const acceptedDocuments: File[] = []
    const acceptedInline: File[] = []
    let totalInlineBytes = attachments.value.reduce(
      (total, attachment) => total + (attachment.byteSize || 0),
      0
    )

    for (const file of candidates) {
      if (isDocumentFile(file)) {
        if (file.size > limits.maxDocumentBytes) {
          options.onUploadFailed(
            new Error(options.documentTooLargeMessage(file.name, limits.maxDocumentBytes)),
            file.name
          )
        } else {
          acceptedDocuments.push(file)
        }
        continue
      }

      if (file.size > limits.maxAttachmentBytes) {
        options.onUploadFailed(
          new Error(options.attachmentTooLargeMessage(file.name, limits.maxAttachmentBytes)),
          file.name
        )
        continue
      }
      if (totalInlineBytes + file.size > limits.maxTotalAttachmentBytes) {
        options.onUploadFailed(
          new Error(options.attachmentTotalTooLargeMessage(limits.maxTotalAttachmentBytes)),
          file.name
        )
        continue
      }
      totalInlineBytes += file.size
      acceptedInline.push(file)
    }

    if (generation === attachmentGeneration) {
      documentAttachments.value.push(
        ...acceptedDocuments.map(file => ({
          name: file.name,
          mimeType: file.type || 'application/octet-stream',
          file
        }))
      )
    }

    const loaded: Array<MediaAttachment | null> = Array.from(
      { length: acceptedInline.length },
      () => null
    )
    let cursor = 0
    const readNext = async () => {
      while (cursor < acceptedInline.length) {
        const index = cursor++
        const file = acceptedInline[index]!
        try {
          loaded[index] = {
            name: file.name,
            mimeType: file.type || 'application/octet-stream',
            data: await toDataUrl(file),
            byteSize: file.size
          }
        } catch (error) {
          options.onUploadFailed(error, file.name)
        }
      }
    }
    await Promise.all(
      Array.from(
        { length: Math.min(MAX_CONCURRENT_FILE_READS, acceptedInline.length) },
        () => readNext()
      )
    )
    if (generation === attachmentGeneration) {
      attachments.value.push(...loaded.filter((item): item is MediaAttachment => item !== null))
    }
  }

  const processFileList = (files: File[]) => {
    const generation = attachmentGeneration
    const pending = processingChain.then(
      () => processFileListNow(files, generation),
      () => processFileListNow(files, generation)
    )
    processingChain = pending.catch(() => undefined)
    return pending
  }

  const handleFiles = async (event: Event) => {
    const inputNode = event.target as HTMLInputElement
    const files = Array.from(inputNode.files || [])
    await processFileList(files)
    inputNode.value = ''
  }

  const handleDragOver = (event: DragEvent) => {
    const types = event.dataTransfer?.types
    if (types && Array.from(types).includes('Files')) {
      event.preventDefault()
      isDragOver.value = true
    }
  }

  const handleDragLeave = (event: DragEvent) => {
    // Clear the drag state only when the drag pointer leaves the browser window.
    if (!event.relatedTarget) {
      isDragOver.value = false
    }
  }

  const handleDrop = async (event: DragEvent) => {
    event.preventDefault()
    isDragOver.value = false
    const types = event.dataTransfer?.types
    if (!types || !Array.from(types).includes('Files')) {
      return
    }
    const files = Array.from(event.dataTransfer?.files || [])
    await processFileList(files)
  }

  const handlePaste = async (event: ClipboardEvent) => {
    const files = Array.from(event.clipboardData?.files || [])
    if (files.length > 0) {
      event.preventDefault()
      await processFileList(files)
    }
  }

  onMounted(() => {
    document.addEventListener('paste', handlePaste)
    document.addEventListener('dragover', handleDragOver)
    document.addEventListener('dragleave', handleDragLeave)
    document.addEventListener('drop', handleDrop)
  })

  onUnmounted(() => {
    document.removeEventListener('paste', handlePaste)
    document.removeEventListener('dragover', handleDragOver)
    document.removeEventListener('dragleave', handleDragLeave)
    document.removeEventListener('drop', handleDrop)
  })

  return {
    attachments,
    documentAttachments,
    fileInput,
    isDragOver,
    clearAttachments,
    removeAttachment,
    removeDocumentAttachment,
    processFileList,
    handleFiles,
    async uploadDocuments(
      chatId: string,
      uploadDocument: (chatId: string, file: File) => Promise<ChatDocument | null>,
      fetchDocuments: (chatId: string) => Promise<ChatDocument[]>
    ): Promise<Array<{ mimeType: string; name?: string }>> {
      const uploadedDocuments: ChatDocument[] = []
      for (const document of documentAttachments.value) {
        try {
          const uploaded = await uploadDocument(chatId, document.file)
          if (!uploaded) {
            throw new DocumentUploadError('DOCUMENT_UPLOAD_EMPTY_RESPONSE', document.name)
          }
          uploadedDocuments.push(uploaded)
        } catch (err) {
          options.onUploadFailed(err, document.name)
        }
      }

      const readyDocuments = await waitForDocumentsReady(
        chatId,
        uploadedDocuments,
        fetchDocuments
      )
      return readyDocuments.map(document => ({
        mimeType: document.mimeType,
        name: document.filename
      }))
    }
  }
}
