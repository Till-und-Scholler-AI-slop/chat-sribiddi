import { defineConfig, devices } from '@playwright/test'

const webServerEnv = Object.fromEntries(
  Object.entries(process.env).filter((entry): entry is [string, string] => typeof entry[1] === 'string')
)

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 30_000,
  workers: 1,
  expect: {
    timeout: 5_000
  },
  use: {
    baseURL: 'http://127.0.0.1:3000',
    locale: 'de-DE',
    trace: 'retain-on-failure'
  },
  webServer: {
    command: 'bun run dev -- --host 127.0.0.1 --port 3000',
    env: {
      ...webServerEnv,
      NUXT_PUBLIC_BACKEND_BASE_URL: '/api'
    },
    url: 'http://127.0.0.1:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
