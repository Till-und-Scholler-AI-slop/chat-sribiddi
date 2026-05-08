import { expect, test } from '@playwright/test'
import { mockBackend } from './mock-backend'

const hydrationTimeout = 60_000

test('login page and logout flow use the mocked session backend', async ({ page }) => {
  await mockBackend(page, { authenticated: false })

  await page.goto('/login')
  // The login page runs checkAuth() on mount — wait for the skeleton to
  // disappear and the Google button to render.  CI hydration is slow, so
  // we use a generous timeout.
  await expect(page.getByRole('link', { name: /google/i })).toBeVisible({ timeout: hydrationTimeout })

  await page.goto('/chat')
  await expect(page).toHaveURL(/\/login$/, { timeout: hydrationTimeout })

  await page.unrouteAll()
  await mockBackend(page, { authenticated: true })

  await page.goto('/chat')
  // The chat page also runs ensureAuthenticated() on mount — same hydration delay.
  await expect(page.getByTestId('chat-page')).toHaveAttribute('data-ready', 'true', { timeout: hydrationTimeout })
  await expect(page.getByRole('button', { name: 'Abmelden' })).toBeEnabled({ timeout: hydrationTimeout })

  const logoutRequest = page.waitForRequest(request => {
    const pathname = new URL(request.url()).pathname
    return pathname === '/logout' || pathname === '/api/logout'
  })
  await page.getByRole('button', { name: 'Abmelden' }).click({ force: true })
  await logoutRequest
  await expect(page).toHaveURL(/\/login$/, { timeout: hydrationTimeout })
})
