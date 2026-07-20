# Deterministic full-stack SSE load and soak harness

The acceptance gate runs the real TypeScript Backend and ProviderGateway
together with PostgreSQL 18 and Redis. Only the external
OpenRouter-compatible provider is replaced by `mock-provider.mjs`, so database
transactions, Redis limits, credentials, gateway contracts, cancellation,
stream slots, SSE serialization and process metrics remain on the production
code paths.

The baseline uses exactly 100 virtual user sessions with 10 iterations each:
1,000 persisted SSE streams. Every session stores a test BYOK credential,
creates a database-backed chat, reads the capability endpoint and sends ten
messages. Separate authenticated probes verify the 15-second heartbeat and
that client cancellation reaches the provider and frees the user's stream slot
within one second.

## Local run

Docker and k6 are required. From the repository root:

```bash
docker compose -f Backend/src/test/k6/docker-compose.load.yml up -d --build --wait
k6 run Backend/src/test/k6/byok-load-test.js
docker compose -f Backend/src/test/k6/docker-compose.load.yml down --volumes
```

The backend is published at `127.0.0.1:18080`; provider-only observations are
available at `127.0.0.1:18081`. The Compose project and its PostgreSQL volume
are isolated from development and production projects.

Use `TEST_MODE=soak SOAK_DURATION=10m` for the scheduled/manual soak profile.
The mock provider captures the backend RSS after the first 100 completed
streams and the soak rejects final growth above 20%. Teardown also requires
zero backend streams, provider streams and active document jobs.

The workflow archives the k6 summary, real backend Prometheus metrics,
provider observations, database drain state, Compose state and logs before it
removes the ephemeral stack.
