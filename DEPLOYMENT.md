# Deployment Runbook

Diese Repo nutzt eine GitHub-Action:

1. Workflow: `.github/workflows/deploy-main.yml`
2. Trigger: jeder `push` auf Branch `main` (inkl. Merge in `main`)
3. Zielserver: `root@45.81.235.217`

## 1) Was auf dem Server abgelegt wird

Deploy-Zielpfad ist immer:

1. `/opt/chat-sribiddi/Backend` (Spring Boot + Postgres Compose Stack)
2. `/opt/chat-sribiddi/Frontend-web` (Nuxt Compose Stack)

Quellpfad -> Zielpfad:

1. `Backend/*` -> `/opt/chat-sribiddi/Backend/*`
2. `Frontend/web/*` -> `/opt/chat-sribiddi/Frontend-web/*`

Wichtige Dateien auf dem Server:

1. Backend Env: `/opt/chat-sribiddi/Backend/.env`
2. Frontend Env: `/opt/chat-sribiddi/Frontend-web/.env`
3. Backend Compose: `/opt/chat-sribiddi/Backend/deploy/docker-compose.prod.yml`
4. Frontend Compose: `/opt/chat-sribiddi/Frontend-web/deploy/docker-compose.prod.yml`

Temporärer Upload-Pfad während eines Deployments:

1. `/tmp/chat-sribiddi-deploy` (wird am Ende wieder gelöscht)

## 2) Was du in GitHub machen musst

Öffne:

1. `GitHub -> Repository -> Settings -> Secrets and variables -> Actions`

Lege diese Repository-Secrets an:

1. `VPS_HOST` = `45.81.235.217`
2. `VPS_USER` = `root`
3. `VPS_PASSWORD` = `<dein Server-Passwort>`
4. `BACKEND_ENV_FILE` = kompletter Inhalt der Backend-`.env`
5. `FRONTEND_ENV_FILE` = kompletter Inhalt der Frontend-`.env`

## 3) Inhalte für die Env-Secrets

Backend-Vorlage liegt in:

1. `Backend/.env.example`

Frontend-Vorlage liegt in:

1. `Frontend/web/deploy/.env.example`

Minimaler Frontend-Inhalt:

```dotenv
NUXT_PUBLIC_BACKEND_BASE_URL=http://45.81.235.217:8080
```

Wichtiger Backend-Hinweis:

1. `ENCRYPTION_SECRET` muss ein Base64-Secret mit 32 Bytes sein.
2. Erzeugen mit `openssl rand -base64 32`.

## 4) Was der Workflow automatisch macht

Bei Push auf `main`:

1. baut zwei Archive (`Backend` und `Frontend/web`)
2. lädt sie auf den Server (`/tmp/chat-sribiddi-deploy`)
3. schreibt `.env` aus `BACKEND_ENV_FILE` und `FRONTEND_ENV_FILE`
4. startet Backend-Stack neu:
   `docker compose --env-file .env -f deploy/docker-compose.prod.yml up -d --build --remove-orphans`
5. startet Frontend-Stack neu:
   `docker compose --env-file .env -f deploy/docker-compose.prod.yml up -d --build --remove-orphans`

## 5) Erster Lauf / Verifikation

1. Commit + Push nach `main` ausführen.
2. In GitHub `Actions -> Deploy App To VPS` öffnen.
3. Nach Erfolg prüfen:
   - Frontend: `http://45.81.235.217`
   - Backend: `http://45.81.235.217:8080`

Server-Check:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

## 6) Sicherheits- und Betriebs-Hinweise

1. Root-Passwort sollte nach Einrichtung rotiert werden.
2. Besser auf SSH-Key-Deploy umstellen (statt Passwort in Secret).
3. Optional später TLS/Reverse-Proxy vorschalten (Caddy/Nginx + Domain).
