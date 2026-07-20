const mode = (__ENV.TEST_MODE || 'baseline').toLowerCase()
const isSoak = mode === 'soak'
const vus = positiveInteger(__ENV.K6_VUS, 100)
const iterationsPerVu = positiveInteger(__ENV.K6_ITERATIONS_PER_VU, 10)

const scenarios = isSoak
  ? {
      soak_streams: {
        executor: 'constant-vus',
        exec: 'streamSession',
        vus: positiveInteger(__ENV.SOAK_VUS, vus),
        duration: __ENV.SOAK_DURATION || '10m',
        gracefulStop: '30s'
      },
      ...probeScenarios()
    }
  : {
      baseline_streams: {
        executor: 'per-vu-iterations',
        exec: 'streamSession',
        vus,
        iterations: iterationsPerVu,
        maxDuration: __ENV.K6_MAX_DURATION || '10m',
        gracefulStop: '30s'
      },
      ...probeScenarios()
    }

export const byokLoadOptions = {
  scenarios,
  thresholds: {
    checks: ['rate==1'],
    normal_path_errors: ['rate==0'],
    non_provider_api_duration_ms: ['p(95)<250'],
    mock_sse_first_event_ms: ['p(95)<300'],
    'http_req_failed{normal_path:true}': ['rate==0'],
    ...(isSoak ? {} : { normal_sse_streams: [`count==${vus * iterationsPerVu}`] })
  },
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count']
}

function probeScenarios() {
  return {
    abort_propagation: {
      executor: 'shared-iterations',
      exec: 'abortProbe',
      vus: 1,
      iterations: 1,
      startTime: '500ms',
      maxDuration: '10s'
    },
    heartbeat_contract: {
      executor: 'shared-iterations',
      exec: 'heartbeatProbe',
      vus: 1,
      iterations: 1,
      startTime: '500ms',
      maxDuration: '25s'
    }
  }
}

function positiveInteger(value, fallback) {
  const parsed = Number(value)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback
}
