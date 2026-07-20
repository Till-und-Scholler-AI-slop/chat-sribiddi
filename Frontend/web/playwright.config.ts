import { defineConfig, devices } from '@playwright/test'

const webServerEnv = Object.fromEntries(
  Object.entries(process.env).filter((entry): entry is [string, string] => typeof entry[1] === 'string')
)
const isCI = !!process.env.CI
const executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH
const webServerCommand = isCI
  ? 'node .output/server/index.mjs'
  : 'bun run dev -- --host 127.0.0.1 --port 3000'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 90_000,
  workers: 1,
  retries: isCI ? 1 : 0,
  expect: {
    timeout: 10_000
  },
  use: {
    baseURL: 'http://127.0.0.1:3000',
    locale: 'de-DE',
    trace: 'retain-on-failure'
  },
  webServer: {
    command: webServerCommand,
    env: {
      ...webServerEnv,
      HOST: '127.0.0.1',
      NITRO_HOST: '127.0.0.1',
      NITRO_PORT: '3000',
      NUXT_DEVTOOLS: 'false',
      NUXT_PUBLIC_BACKEND_BASE_URL: 'http://127.0.0.1:8080',
      PORT: '3000'
    },
    url: 'http://127.0.0.1:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        ...(executablePath ? { launchOptions: { executablePath } } : {})
      }
    }
  ]
})
