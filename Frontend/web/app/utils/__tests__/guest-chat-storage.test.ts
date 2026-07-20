import { describe, expect, it } from 'vitest'
import { enqueueGuestChatStorageTask } from '../guest-chat-storage'

describe('enqueueGuestChatStorageTask', () => {
  it('does not run a delete until an older write has completed', async () => {
    const order: string[] = []
    let releaseWrite!: () => void
    const pendingWrite = new Promise<void>(resolve => {
      releaseWrite = () => {
        order.push('write')
        resolve()
      }
    })

    const queuedDelete = enqueueGuestChatStorageTask(pendingWrite, async () => {
      order.push('delete')
    })
    await Promise.resolve()
    expect(order).toEqual([])

    releaseWrite()
    await queuedDelete
    expect(order).toEqual(['write', 'delete'])
  })

  it('still runs cleanup after a failed older write', async () => {
    const order: string[] = []
    await enqueueGuestChatStorageTask(Promise.reject(new Error('quota exceeded')), async () => {
      order.push('delete')
    })

    expect(order).toEqual(['delete'])
  })
})
