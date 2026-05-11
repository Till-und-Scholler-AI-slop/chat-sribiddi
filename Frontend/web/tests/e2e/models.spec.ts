import { expect, test } from '@playwright/test'
import { mockBackend } from './mock-backend'

const hydrationTimeout = 60_000

test('filters the model catalog with the search input', async ({ page }) => {
  await mockBackend(page, { authenticated: true })

  await page.goto('/models')

  const search = page.getByPlaceholder('Modelle, Anbieter oder Parameter suchen')
  await expect(search).toBeVisible({ timeout: hydrationTimeout })
  await expect(page.getByText('OPENAI Mock')).toBeVisible()
  await expect(page.getByText('CLAUDE Mock')).toBeVisible()

  await search.fill('claude')

  await expect(page.getByText('CLAUDE Mock')).toBeVisible()
  await expect(page.getByText('OPENAI Mock')).toBeHidden()
  await expect(page.getByText('GOOGLE Mock')).toBeHidden()
  await expect(page.getByText('OPENROUTER Mock')).toBeHidden()
})
