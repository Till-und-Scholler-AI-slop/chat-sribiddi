export const byokLoadOptions = {
  scenarios: {
    byok_chat_load: {
      executor: 'shared-iterations',
      vus: Number(__ENV.K6_VUS || 100),
      iterations: Number(__ENV.K6_ITERATIONS || 1000),
      maxDuration: __ENV.K6_MAX_DURATION || '10m'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    http_req_duration: ['p(95)<5000'],
    checks: ['rate>0.99']
  }
}
