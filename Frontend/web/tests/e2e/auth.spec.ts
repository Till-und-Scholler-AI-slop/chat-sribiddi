import { expect, test } from '@playwright/test'
import { mockBackend } from './mock-backend'

test('login page and logout flow use the mocked session backend', async ({ page }) => {
  await mockBackend(page, { authenticated: false })

  await page.goto('/login')
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('link', { name: /google/i })).toBeVisible()

  await page.goto('/chat')
  await page.waitForLoadState('networkidle')
  await expect(page).toHaveURL(/\/login$/)

  await page.unroute('**/*')
  await mockBackend(page, { authenticated: true })
  await page.goto('/chat')
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('button', { name: 'Abmelden' })).toBeVisible()

  const logoutRequest = page.waitForRequest(request => new URL(request.url()).pathname === '/logout')
  await page.getByRole('button', { name: 'Abmelden' }).click({ force: true })
  await logoutRequest
  await expect(page).toHaveURL(/\/login$/)
})
