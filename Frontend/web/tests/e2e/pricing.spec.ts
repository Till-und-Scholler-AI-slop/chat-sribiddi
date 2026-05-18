import { expect, test } from '@playwright/test'
import { mockBackend } from './mock-backend'

test('pricing page renders the three plan cards without requiring auth', async ({ page }) => {
  await mockBackend(page, { authenticated: false })

  await page.goto('/pricing')

  await expect(page.getByRole('heading', { name: 'Preise' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Free' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Premium' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Max' })).toBeVisible()
})

test('pricing back button returns to chat', async ({ page }) => {
  await mockBackend(page, { authenticated: false })

  await page.goto('/chat')
  await page.goto('/pricing')
  await page.getByRole('button', { name: 'Zurück zum Chat' }).click()

  await expect(page).toHaveURL(/\/chat$/)
})
