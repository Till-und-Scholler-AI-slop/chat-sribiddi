import { expect, test } from '@playwright/test'
import { mockBackend } from './mock-backend'

test('login page and logout flow use the mocked session backend', async ({ page }) => {
  await mockBackend(page, { authenticated: false })

  await page.goto('/login')
  // The login page runs checkAuth() on mount — wait for the skeleton to
  // disappear and the Google button to render.  CI hydration is slow, so
  // we use a generous timeout.
  await expect(page.getByRole('link', { name: /google/i })).toBeVisible({ timeout: 15_000 })

  await page.goto('/chat')
  await expect(page).toHaveURL(/\/login$/)

  await page.unroute('**/*')
  await mockBackend(page, { authenticated: true })

  await page.goto('/chat')
  // The chat page also runs ensureAuthenticated() on mount — same hydration delay.
  await expect(page.getByTestId('chat-page')).toHaveAttribute('data-ready', 'true', { timeout: 15_000 })
  await expect(page.getByRole('button', { name: 'Abmelden' })).toBeEnabled({ timeout: 15_000 })

  const logoutRequest = page.waitForRequest(request => new URL(request.url()).pathname === '/logout')
  await page.getByRole('button', { name: 'Abmelden' }).click({ force: true })
  await logoutRequest
  await expect(page).toHaveURL(/\/login$/)
})
