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
  await expect(page.locator('.msg-user').filter({ hasText: 'Hallo E2E' })).toHaveCSS('user-select', 'none')
  await expect(page.locator('.msg-assistant')).toHaveCSS('user-select', 'none')
  await expect(page.locator('.msg-assistant .code-block-container')).toHaveCSS('user-select', 'text')
})

test('renders quoted chat markdown without filled grey panels', async ({ page }) => {
  await mockBackend(page, {
    authenticated: true,
    markdownResponseChunks: [
      '> Quoted assistant text\n\n',
      'Regular assistant text'
    ]
  })

  await page.goto('/chat')

  const prompt = page.getByRole('textbox', { name: 'Type your message here…' })
  await expect(prompt).toBeVisible({ timeout: hydrationTimeout })
  await expect(page.getByTestId('chat-page')).toHaveAttribute('data-ready', 'true', { timeout: hydrationTimeout })

  await prompt.click()
  await prompt.pressSequentially('> Quoted user text')
  await expect(page.getByTestId('chat-submit')).toBeEnabled()
  await page.getByTestId('chat-submit').click()

  const userQuote = page.locator('.msg-user .blockquote')
  const assistantQuote = page.locator('.msg-assistant .blockquote')
  await expect(userQuote).toBeVisible()
  await expect(assistantQuote).toBeVisible()
  await expect(page.getByText('Regular assistant text')).toBeVisible()

  for (const quote of [userQuote, assistantQuote]) {
    await expect(quote).toHaveCSS('background-color', 'rgba(0, 0, 0, 0)')
    await expect(quote).toHaveCSS('box-shadow', 'none')
  }
})

test('anonymous chat persists locally across reloads', async ({ page }) => {
  await mockBackend(page, { authenticated: false })

  await page.goto('/chat')

  const prompt = page.getByRole('textbox', { name: 'Type your message here…' })
  await expect(prompt).toBeVisible({ timeout: hydrationTimeout })
  await expect(page.getByTestId('chat-page')).toHaveAttribute('data-ready', 'true', { timeout: hydrationTimeout })
  await expect(page.getByRole('button', { name: /Google/i })).toBeVisible({ timeout: hydrationTimeout })

  await prompt.click()
  await prompt.pressSequentially('Hallo Gast')
  await expect(page.getByTestId('chat-submit')).toBeEnabled()
  await page.getByTestId('chat-submit').click()

  await expect(page.locator('.msg-user').filter({ hasText: 'Hallo Gast' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Markstream check' })).toBeVisible()

  await page.reload()

  await expect(page.locator('.msg-user').filter({ hasText: 'Hallo Gast' })).toBeVisible({ timeout: hydrationTimeout })
  await expect(page.getByRole('heading', { name: 'Markstream check' })).toBeVisible()
})
