# Deployment Runbook

This repository uses two separate Docker-based GitHub Actions pipelines:

1. Backend pipeline: `.github/workflows/backend-deploy.yml`
2. Frontend pipeline: `.github/workflows/frontend-deploy.yml`

Each pipeline deploys independently to the same Linux server.

## 1) Current target architecture

1. Frontend runs as a Nuxt container on port `80`.
2. Backend runs as a Spring Boot container on port `8080`.
3. PostgreSQL runs as a container inside the backend stack.
4. Public URLs:
   - Frontend: `http://45.81.235.217`
   - Backend: `http://45.81.235.217:8080`

Server directories used by pipelines:

1. Backend: `/opt/chat-sribiddi/Backend`
2. Frontend: `/opt/chat-sribiddi/Frontend-web`

## 2) One-time setup in GitHub

Go to `GitHub -> Repository -> Settings -> Secrets and variables -> Actions`.
Create these repository secrets:

1. `VPS_HOST`
2. `VPS_USER`
3. `VPS_PASSWORD`
4. `BACKEND_ENV_FILE`
5. `FRONTEND_ENV_FILE`

Use these values:

1. `VPS_HOST=45.81.235.217`
2. `VPS_USER=root`
3. `VPS_PASSWORD=<your server password>`

## 3) Prepare backend environment secret

Use `Backend/deploy/.env.example` as the template.

Minimum backend env for immediate deployment without OAuth setup:

```dotenv
SPRING_PROFILES_ACTIVE=dev
POSTGRES_DB=chat_sribiddi
POSTGRES_USER=chat_sribiddi
POSTGRES_PASSWORD=<strong_password>

GOOGLE_CLIENT_ID=not-configured
GOOGLE_CLIENT_SECRET=not-configured
GOOGLE_PROJECT_ID=demo-project
GOOGLE_LOCATION=us-central1
GOOGLE_API_KEY=not-configured
OPENAI_API_KEY=sk-not-configured
OPENAI_CONNECT_TIMEOUT=10s
OPENAI_READ_TIMEOUT=180s

APP_FRONTEND_URL=http://45.81.235.217
ALLOWED_ORIGINS=http://45.81.235.217

ENCRYPTION_SECRET=<base64_32_byte_secret>
ENCRYPTION_ACTIVE_KEY_VERSION=1
ENCRYPTION_DECRYPTION_SECRETS=
```

Generate `ENCRYPTION_SECRET` with:

```bash
openssl rand -base64 32
```

If you want Google OAuth login enabled, switch to production config:

1. `SPRING_PROFILES_ACTIVE=production`
2. Set real `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`
3. Keep `APP_FRONTEND_URL` and `ALLOWED_ORIGINS` as `http://45.81.235.217`

Paste the full backend env text into `BACKEND_ENV_FILE` secret.

## 4) Prepare frontend environment secret

Use `Frontend/web/deploy/.env.example` as the template.

```dotenv
NUXT_PUBLIC_BACKEND_BASE_URL=http://45.81.235.217:8080
```

Paste that text into `FRONTEND_ENV_FILE` secret.

## 5) Push deployment files to GitHub

From repo root:

```bash
git add .github/workflows Backend Frontend/web DEPLOYMENT.md README.md
git commit -m "Add Dockerized frontend/backend pipelines and deployment runbook"
git push
```

Pipelines trigger on push to `main` or `master` when matching paths change.

## 6) First deployment

Option A (recommended): run manually once from GitHub Actions UI.

1. Open `Actions -> Deploy Backend -> Run workflow`.
2. After success, open `Actions -> Deploy Frontend -> Run workflow`.

Option B: push changes to trigger automatically.

## 7) Verify deployment

From your machine:

```bash
curl -I http://45.81.235.217
```

Expected: `HTTP/1.1 200 OK`.

From the server:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
curl -i http://127.0.0.1:8080/
```

Expected backend response includes status `200`.

## 8) Day-2 operations (normal updates)

1. Change backend code and push.
2. Backend workflow rebuilds and redeploys backend stack.
3. Change frontend code and push.
4. Frontend workflow rebuilds and redeploys frontend stack.

Both workflows:

1. Sync files to server via `rsync`
2. Preserve remote `.env`
3. Rebuild images with `docker compose up -d --build --remove-orphans`

## 9) Rollback procedure

Fast rollback:

1. Checkout previous commit locally.
2. Push that commit to `main`/`master` or run workflow from that commit.
3. Pipeline redeploys previous version.

Manual rollback on server (if needed):

```bash
cd /opt/chat-sribiddi/Backend
docker compose --env-file .env -f deploy/docker-compose.prod.yml up -d --build

cd /opt/chat-sribiddi/Frontend-web
docker compose --env-file .env -f deploy/docker-compose.prod.yml up -d --build
```

## 10) Troubleshooting

Check workflow logs:

1. `GitHub -> Actions -> failed run -> step logs`

Check server logs:

```bash
docker logs --tail 200 chat_sribiddi_backend-backend-1
docker logs --tail 200 chat_sribiddi_frontend-frontend-1
docker logs --tail 200 chat_sribiddi_backend-db-1
```

Check compose state:

```bash
cd /opt/chat-sribiddi/Backend
docker compose --env-file .env -f deploy/docker-compose.prod.yml ps

cd /opt/chat-sribiddi/Frontend-web
docker compose --env-file .env -f deploy/docker-compose.prod.yml ps
```

If port `80` is busy:

```bash
ss -tulpen | grep ':80'
```

Stop conflicting container/service and redeploy frontend.

## 11) Security tasks you should do next

1. Rotate root password (recommended immediately).
2. Move GitHub Actions deploy auth from password to SSH key.
3. Disable root-password SSH login after key auth is tested.
4. Add HTTPS reverse proxy later (Nginx/Caddy + domain + TLS cert).
5. Restrict backend `:8080` exposure if frontend will be the only public entry.

## 12) File map

1. Backend workflow: `.github/workflows/backend-deploy.yml`
2. Frontend workflow: `.github/workflows/frontend-deploy.yml`
3. Backend Docker image: `Backend/Dockerfile`
4. Frontend Docker image: `Frontend/web/Dockerfile`
5. Backend compose: `Backend/deploy/docker-compose.prod.yml`
6. Frontend compose: `Frontend/web/deploy/docker-compose.prod.yml`
7. Backend env template: `Backend/deploy/.env.example`
8. Frontend env template: `Frontend/web/deploy/.env.example`
