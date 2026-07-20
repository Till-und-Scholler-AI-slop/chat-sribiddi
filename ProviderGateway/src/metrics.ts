type Labels = Record<string, string | number>;

export class GatewayMetrics {
  private readonly startedAt = Date.now();
  private readonly requests = new Map<string, number>();
  private readonly streams = new Map<string, number>();
  private readonly errors = new Map<string, number>();
  private readonly durationSum = new Map<string, number>();
  private readonly durationCount = new Map<string, number>();
  private readonly ttftSum = new Map<string, number>();
  private readonly ttftCount = new Map<string, number>();
  private activeStreams = 0;

  observeRequest(method: string, route: string, status: number) {
    increment(this.requests, labels({ method, route, status }));
  }

  beginStream(provider: string) {
    this.activeStreams += 1;
    const startedAt = performance.now();
    let finished = false;
    let firstDeltaObserved = false;
    return {
      observeFirstDelta: () => {
        if (firstDeltaObserved) return;
        firstDeltaObserved = true;
        const key = labels({ provider });
        incrementBy(this.ttftSum, key, (performance.now() - startedAt) / 1_000);
        increment(this.ttftCount, key);
      },
      finish: (outcome: "success" | "error" | "aborted") => {
        if (finished) return;
        finished = true;
        this.activeStreams = Math.max(0, this.activeStreams - 1);
        increment(this.streams, labels({ provider, outcome }));
        incrementBy(
          this.durationSum,
          labels({ provider }),
          (performance.now() - startedAt) / 1_000,
        );
        increment(this.durationCount, labels({ provider }));
      },
    };
  }

  observeProviderError(provider: string, code: string) {
    increment(this.errors, labels({ provider, code }));
  }

  render(ready: boolean) {
    const lines = [
      "# HELP provider_gateway_ready Whether the gateway accepts new provider requests.",
      "# TYPE provider_gateway_ready gauge",
      `provider_gateway_ready ${ready ? 1 : 0}`,
      "# HELP provider_gateway_active_streams Currently active provider streams.",
      "# TYPE provider_gateway_active_streams gauge",
      `provider_gateway_active_streams ${this.activeStreams}`,
      "# HELP provider_gateway_uptime_seconds Process uptime in seconds.",
      "# TYPE provider_gateway_uptime_seconds gauge",
      `provider_gateway_uptime_seconds ${((Date.now() - this.startedAt) / 1_000).toFixed(3)}`,
      "# HELP provider_gateway_process_resident_memory_bytes Resident memory used by the gateway process.",
      "# TYPE provider_gateway_process_resident_memory_bytes gauge",
      `provider_gateway_process_resident_memory_bytes ${process.memoryUsage().rss}`,
      "# HELP provider_gateway_http_requests_total HTTP requests handled by route, method, and status.",
      "# TYPE provider_gateway_http_requests_total counter",
      ...samples("provider_gateway_http_requests_total", this.requests),
      "# HELP provider_gateway_streams_total Completed provider streams by provider and outcome.",
      "# TYPE provider_gateway_streams_total counter",
      ...samples("provider_gateway_streams_total", this.streams),
      "# HELP provider_gateway_provider_errors_total Provider errors by provider and stable error code.",
      "# TYPE provider_gateway_provider_errors_total counter",
      ...samples("provider_gateway_provider_errors_total", this.errors),
      "# HELP provider_gateway_stream_duration_seconds Total provider stream duration in seconds.",
      "# TYPE provider_gateway_stream_duration_seconds summary",
      ...samples("provider_gateway_stream_duration_seconds_sum", this.durationSum),
      ...samples("provider_gateway_stream_duration_seconds_count", this.durationCount),
      "# HELP provider_gateway_ttft_seconds Time from provider request start to the first text, reasoning, or tool delta.",
      "# TYPE provider_gateway_ttft_seconds summary",
      ...samples("provider_gateway_ttft_seconds_sum", this.ttftSum),
      ...samples("provider_gateway_ttft_seconds_count", this.ttftCount),
      "",
    ];
    return lines.join("\n");
  }
}

function labels(values: Labels) {
  return Object.entries(values)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}="${escapeLabel(String(value))}"`)
    .join(",");
}

function samples(name: string, values: Map<string, number>) {
  return [...values.entries()].map(([key, value]) => `${name}{${key}} ${value}`);
}

function increment(values: Map<string, number>, key: string) {
  incrementBy(values, key, 1);
}

function incrementBy(values: Map<string, number>, key: string, amount: number) {
  values.set(key, (values.get(key) ?? 0) + amount);
}

function escapeLabel(value: string) {
  return value.replace(/\\/g, "\\\\").replace(/\n/g, "\\n").replace(/"/g, '\\"');
}
