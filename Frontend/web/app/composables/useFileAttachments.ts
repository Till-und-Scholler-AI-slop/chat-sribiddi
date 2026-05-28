import type { ChatDocument, DocumentAttachment, MediaAttachment } from '~/composables/useChatApi'

type FileAttachmentOptions = {
  fileReadFailedMessage: (name: string) => string
  onUploadFailed: (reason: unknown, documentName?: string) => void
}

export function useFileAttachments(options: FileAttachmentOptions) {
  const attachments = ref<MediaAttachment[]>([])
  const documentAttachments = ref<DocumentAttachment[]>([])
  const fileInput = ref<HTMLInputElement | null>(null)
  const isDragOver = ref(false)

  const toDataUrl = (file: File) =>
    new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
      reader.onerror = () => reject(new Error(options.fileReadFailedMessage(file.name)))
      reader.readAsDataURL(file)
    })

  const clearAttachments = () => {
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

  const processFileList = async (files: File[]) => {
    if (files.length === 0) return
    const documentFiles = files.filter(isDocumentFile)
    documentAttachments.value.push(
      ...documentFiles.map(file => ({
        name: file.name,
        mimeType: file.type || 'application/octet-stream',
        file
      }))
    )
    const inlineFiles = files.filter(file => !isDocumentFile(file))
    const results = await Promise.allSettled(
      inlineFiles.map(async file => ({
        name: file.name,
        mimeType: file.type || 'application/octet-stream',
        data: await toDataUrl(file)
      }))
    )
    for (const result of results) {
      if (result.status === 'fulfilled') {
        attachments.value.push(result.value)
      } else {
        options.onUploadFailed(result.reason)
      }
    }
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
      uploadDocument: (chatId: string, file: File) => Promise<ChatDocument | null>
    ): Promise<Array<{ mimeType: string; name?: string }>> {
      const result: Array<{ mimeType: string; name?: string }> = []
      for (const document of documentAttachments.value) {
        try {
          const uploaded = await uploadDocument(chatId, document.file)
          result.push({
            mimeType: uploaded?.mimeType || document.mimeType,
            name: uploaded?.filename || document.name
          })
        } catch (err) {
          options.onUploadFailed(err, document.name)
        }
      }
      return result
    }
  }
}
