import { expect, test } from '@playwright/test'
import { mockBackend } from './mock-backend'

const hydrationTimeout = 60_000

test('creates a chat, sends a prompt, and renders the mocked streamed response', async ({ page }) => {
  await mockBackend(page, { authenticated: true })

  await page.goto('/chat')

  // Don't use networkidle — parallel model/chats requests prevent it from
  // resolving in CI.  Instead wait for the chat input to appear, which
  // only renders after ensureModels() + loadChatsFromBackend() complete.
  const prompt = page.getByRole('textbox', { name: 'Type your message here…' })
  await expect(prompt).toBeVisible({ timeout: hydrationTimeout })
  await expect(page.getByTestId('chat-page')).toHaveAttribute('data-ready', 'true', { timeout: hydrationTimeout })

  await prompt.click()
  await prompt.pressSequentially('Hallo E2E')
  await expect(page.getByTestId('chat-submit')).toBeEnabled()
  await page.getByTestId('chat-submit').click()

  await expect(page.getByText('Hallo E2E')).toBeVisible()
  await expect(page.locator('.msg-assistant > .markdown-body > .markstream-vue')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Markstream check' })).toBeVisible()
  await expect(page.getByText('Alpha item')).toBeVisible()
  await expect(page.getByText('Beta item')).toBeVisible()
  await expect(page.getByText('const ok = true')).toBeVisible()
  const highlightedCode = page.locator('.msg-assistant .code-block-render .shiki:not(.shiki-fallback)').first()
  await expect(highlightedCode).toBeVisible()
  await expect(highlightedCode).toHaveClass(/github-(light|dark-dimmed)/)
  await expect(highlightedCode.locator('span[style*="color"]').first()).toBeVisible()
  await expect(page.getByRole('heading', { name: 'E2E Chat' })).toBeVisible()
})

test('anonymous chat persists locally across reloads', async ({ page }) => {
  await mockBackend(page, { authenticated: false })

  await page.goto('/chat')

  const prompt = page.getByRole('textbox', { name: 'Type your message here…' })
  await expect(prompt).toBeVisible({ timeout: hydrationTimeout })
  await expect(page.getByRole('button', { name: /Google/i })).toBeVisible({ timeout: hydrationTimeout })

  await prompt.click()
  await prompt.pressSequentially('Hallo Gast')
  await page.getByTestId('chat-submit').click()

  await expect(page.getByText('Hallo Gast')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Markstream check' })).toBeVisible()

  await page.reload()

  await expect(page.getByText('Hallo Gast')).toBeVisible({ timeout: hydrationTimeout })
  await expect(page.getByRole('heading', { name: 'Markstream check' })).toBeVisible()
})
