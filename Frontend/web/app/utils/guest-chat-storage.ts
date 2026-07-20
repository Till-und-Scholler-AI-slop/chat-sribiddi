const DATABASE_NAME = 'chat-sribiddi'
const DATABASE_VERSION = 2
const STORE_NAME = 'chat-state'
const RECORD_KEY = 'guest-chats-v2'

type StoredRecord = {
  key: typeof RECORD_KEY
  version: 2
  updatedAt: string
  chats: unknown[]
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('IndexedDB request failed'))
  })
}

function transactionComplete(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onabort = () => reject(transaction.error || new Error('IndexedDB transaction aborted'))
    transaction.onerror = () => reject(transaction.error || new Error('IndexedDB transaction failed'))
  })
}

async function openDatabase() {
  const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION)
  request.onupgradeneeded = () => {
    const database = request.result
    if (!database.objectStoreNames.contains(STORE_NAME)) {
      database.createObjectStore(STORE_NAME, { keyPath: 'key' })
    }
  }
  return requestResult(request)
}

function readLegacy(legacyKey: string): { exists: boolean, chats: unknown[] } {
  try {
    const raw = localStorage.getItem(legacyKey)
    if (!raw) return { exists: false, chats: [] }
    const parsed = JSON.parse(raw) as { chats?: unknown[] }
    return Array.isArray(parsed.chats)
      ? { exists: true, chats: parsed.chats }
      : { exists: false, chats: [] }
  } catch {
    return { exists: false, chats: [] }
  }
}

export function stageGuestChatRecords(chats: unknown[], legacyKey: string): string {
  const updatedAt = new Date().toISOString()
  if (!import.meta.client) return updatedAt
  try {
    localStorage.setItem(legacyKey, JSON.stringify({ version: 2, updatedAt, chats }))
  } catch {
    // Persistence must never break the active chat when browser storage is full.
  }
  return updatedAt
}

function clearStagedRecords(legacyKey: string, updatedAt: string) {
  try {
    const raw = localStorage.getItem(legacyKey)
    if (!raw) return
    const parsed = JSON.parse(raw) as { updatedAt?: string }
    if (parsed.updatedAt === updatedAt) localStorage.removeItem(legacyKey)
  } catch {
    // A newer or inaccessible emergency snapshot must be left untouched.
  }
}

export function enqueueGuestChatStorageTask(
  previous: Promise<void>,
  task: () => Promise<void>
): Promise<void> {
  return previous.catch(() => undefined).then(task)
}

export async function loadGuestChatRecords(legacyKey: string): Promise<unknown[]> {
  if (!import.meta.client) return []
  const legacy = readLegacy(legacyKey)
  if (!('indexedDB' in globalThis)) return legacy.chats

  try {
    // A staged local snapshot is newer than the last completed IndexedDB write.
    if (legacy.exists) {
      await saveGuestChatRecords(legacy.chats, legacyKey)
      return legacy.chats
    }
    const database = await openDatabase()
    const transaction = database.transaction(STORE_NAME, 'readonly')
    const record = await requestResult(
      transaction.objectStore(STORE_NAME).get(RECORD_KEY) as IDBRequest<StoredRecord | undefined>
    )
    database.close()
    if (record?.version === 2 && Array.isArray(record.chats)) return record.chats
    return []
  } catch {
    return legacy.chats
  }
}

export async function saveGuestChatRecords(
  chats: unknown[],
  legacyKey: string,
  stagedAt = stageGuestChatRecords(chats, legacyKey)
): Promise<void> {
  if (!import.meta.client) return
  const record: StoredRecord = {
    key: RECORD_KEY,
    version: 2,
    updatedAt: stagedAt,
    chats
  }

  if ('indexedDB' in globalThis) {
    try {
      const database = await openDatabase()
      const transaction = database.transaction(STORE_NAME, 'readwrite')
      transaction.objectStore(STORE_NAME).put(record)
      await transactionComplete(transaction)
      database.close()
      clearStagedRecords(legacyKey, stagedAt)
      return
    } catch {
      // Fall through to the legacy store when IndexedDB is unavailable or full.
    }
  }

  // The staged local snapshot remains available as the fallback.
}

export async function clearGuestChatRecords(legacyKey: string): Promise<void> {
  if (!import.meta.client) return
  try {
    localStorage.removeItem(legacyKey)
  } catch {
    // Ignore storage access failures.
  }
  if (!('indexedDB' in globalThis)) return
  try {
    const database = await openDatabase()
    const transaction = database.transaction(STORE_NAME, 'readwrite')
    transaction.objectStore(STORE_NAME).delete(RECORD_KEY)
    await transactionComplete(transaction)
    database.close()
  } catch {
    // Best-effort cleanup; an inaccessible database cannot affect live state.
  }
}
