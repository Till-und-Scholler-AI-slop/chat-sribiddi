import { expect, test } from '@playwright/test'
import { mockBackend } from './mock-backend'

test('creates a chat, sends a prompt, and renders the mocked streamed response', async ({ page }) => {
  await mockBackend(page, { authenticated: true })

  await page.goto('/chat')

  // Don't use networkidle — parallel model/chats requests prevent it from
  // resolving in CI.  Instead wait for the chat input to appear, which
  // only renders after ensureModels() + loadChatsFromBackend() complete.
  const prompt = page.getByRole('textbox', { name: 'Type your message here…' })
  await expect(prompt).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('chat-page')).toHaveAttribute('data-ready', 'true', { timeout: 15_000 })

  await prompt.click()
  await prompt.pressSequentially('Hallo E2E')
  await expect(page.getByTestId('chat-submit')).toBeEnabled()
  await page.getByTestId('chat-submit').click()

  await expect(page.getByText('Hallo E2E')).toBeVisible()
  await expect(page.getByText('Mocked response')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'E2E Chat' })).toBeVisible()
})
