import { defineConfig } from 'vitest/config'
import { resolve } from 'path'

export default defineConfig({
  resolve: {
    alias: {
      '~': resolve(__dirname, 'app'),
      '#imports': resolve(__dirname, 'app/composables')
    }
  },
  test: {
    include: ['app/**/__tests__/**/*.test.ts']
  }
})
