# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: auth.spec.ts >> login page and logout flow use the mocked session backend
- Location: tests/e2e/auth.spec.ts:4:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByRole('link', { name: /google/i })
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByRole('link', { name: /google/i })

```

# Page snapshot

```yaml
- generic [ref=e6]:
  - generic [ref=e7]:
    - heading "Chat Sribiddi" [level=1] [ref=e10]
    - paragraph [ref=e11]: Dein intelligenter KI-Assistent
  - paragraph [ref=e16]: Sitzung wird geprüft …
  - generic [ref=e17]:
    - generic [ref=e21]: Mehrere KI-Modelle
    - generic [ref=e25]: Web-Suche
    - generic [ref=e29]: Dateianalyse
  - paragraph [ref=e30]:
    - link "Impressum" [ref=e31] [cursor=pointer]:
      - /url: /impressum
    - generic [ref=e32]: ·
    - link "Datenschutz" [ref=e33] [cursor=pointer]:
      - /url: /datenschutz
    - generic [ref=e34]: ·
    - link "AGB" [ref=e35] [cursor=pointer]:
      - /url: /agb
```

# Test source

```ts
  1  | import { expect, test } from '@playwright/test'
  2  | import { mockBackend } from './mock-backend'
  3  | 
  4  | test('login page and logout flow use the mocked session backend', async ({ page }) => {
  5  |   await mockBackend(page, { authenticated: false })
  6  | 
  7  |   await page.goto('/login')
> 8  |   await expect(page.getByRole('link', { name: /google/i })).toBeVisible()
     |                                                             ^ Error: expect(locator).toBeVisible() failed
  9  | 
  10 |   await page.goto('/chat')
  11 |   await expect(page).toHaveURL(/\/login$/)
  12 | 
  13 |   await page.unroute('**/*')
  14 |   await mockBackend(page, { authenticated: true })
  15 | 
  16 |   await page.goto('/chat')
  17 |   await expect(page.getByRole('button', { name: 'Abmelden' })).toBeVisible()
  18 | 
  19 |   const logoutRequest = page.waitForRequest(request => new URL(request.url()).pathname === '/logout')
  20 |   await page.getByRole('button', { name: 'Abmelden' }).click({ force: true })
  21 |   await logoutRequest
  22 |   await expect(page).toHaveURL(/\/login$/)
  23 | })
  24 | 
```