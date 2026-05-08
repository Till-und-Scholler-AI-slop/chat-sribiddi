import type { MediaAttachment } from '~/composables/useChatApi'

type FileAttachmentOptions = {
  fileReadFailedMessage: (name: string) => string
  onUploadFailed: (reason: unknown) => void
}

export function useFileAttachments(options: FileAttachmentOptions) {
  const attachments = ref<MediaAttachment[]>([])
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
    if (fileInput.value) {
      fileInput.value.value = ''
    }
  }

  const removeAttachment = (index: number) => {
    attachments.value.splice(index, 1)
  }

  const processFileList = async (files: File[]) => {
    if (files.length === 0) return
    const results = await Promise.allSettled(
      files.map(async file => ({
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
    fileInput,
    isDragOver,
    clearAttachments,
    removeAttachment,
    processFileList,
    handleFiles
  }
}
