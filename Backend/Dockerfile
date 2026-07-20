FROM node:22-bookworm-slim AS deps
WORKDIR /workspace/Backend

COPY packages/provider-contract/package.json packages/provider-contract/package-lock.json packages/provider-contract/index.js packages/provider-contract/index.d.ts /workspace/packages/provider-contract/
COPY Backend/package.json Backend/package-lock.json ./
RUN npm ci --omit=dev --prefix /workspace/packages/provider-contract && npm ci

FROM deps AS build
COPY Backend/tsconfig.json Backend/vitest.config.ts Backend/biome.json ./
COPY Backend/src ./src
RUN npm run build

FROM node:22-bookworm-slim AS runtime
WORKDIR /workspace/Backend

ENV NODE_ENV=production

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*

RUN groupadd -r appuser && useradd -r -g appuser appuser

COPY packages/provider-contract/package.json packages/provider-contract/package-lock.json packages/provider-contract/index.js packages/provider-contract/index.d.ts /workspace/packages/provider-contract/
COPY Backend/package.json Backend/package-lock.json ./
RUN npm ci --omit=dev --prefix /workspace/packages/provider-contract \
  && npm ci --omit=dev \
  && npm cache clean --force

COPY --from=build /workspace/Backend/dist ./dist
COPY Backend/src/config/model-catalog.yaml ./src/config/model-catalog.yaml
COPY Backend/src/main/resources ./src/main/resources

RUN chown -R appuser:appuser /workspace

EXPOSE 8080

USER appuser

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/health/ready || exit 1

CMD ["node", "dist/index.js"]
