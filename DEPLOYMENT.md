# Deployment Runbook (germinai.de)

Diese Repo nutzt zwei getrennte GitHub-Actions:

1. Backend-Workflow: `.github/workflows/deploy-backend.yml`
   - Trigger: `push` auf `main` mit Änderungen in `Backend/**` oder `infra/caddy/**`
2. Frontend-Workflow: `.github/workflows/deploy-frontend.yml`
   - Trigger: `push` auf `main` mit Änderungen in `Frontend/**`
3. Beide Workflows unterstützen `workflow_dispatch` für manuelle Auslösung.
4. Zielserver: `root@45.81.235.217`

## 1) Zielarchitektur

1. Caddy läuft auf dem Host und terminiert TLS (Let's Encrypt) für `germinai.de`.
2. Frontend-Container lauscht nur lokal auf `127.0.0.1:3000`.
3. Backend-Container lauscht nur lokal auf `127.0.0.1:8080`.
4. Redis-Container dient als Cache-Backend für Model-Catalog-Daten.
5. Caddy-Routing:
   - `https://germinai.de/` -> Frontend (`127.0.0.1:3000`)
   - `https://germinai.de/api/*` -> Backend (`127.0.0.1:8080`)
   - `https://germinai.de/login/oauth2/*` -> Backend (`127.0.0.1:8080`)

## 2) Was auf dem Server abgelegt wird

Deploy-Zielpfade:

1. `/opt/chat-sribiddi/Backend`
2. `/opt/chat-sribiddi/Frontend-web`

Wichtige Dateien:

1. Backend Env: `/opt/chat-sribiddi/Backend/.env`
2. Frontend Env: `/opt/chat-sribiddi/Frontend-web/.env`
3. Caddy config: `/etc/caddy/Caddyfile`
4. Caddy cert data: `/var/lib/caddy/.local/share/caddy`

Temporäre Upload-Pfade beim CI-Deploy:

1. Backend-Pipeline: `/tmp/chat-sribiddi-deploy-backend`
2. Frontend-Pipeline: `/tmp/chat-sribiddi-deploy-frontend`

## 3) GitHub-Secrets

Pfad:

1. `GitHub -> Repository -> Settings -> Secrets and variables -> Actions`

Repository Secrets:

1. `VPS_HOST=45.81.235.217`
2. `VPS_USER=root`
3. `VPS_PASSWORD=<server-passwort>`
4. `BACKEND_ENV_FILE=<kompletter Backend-.env Inhalt>`
5. `FRONTEND_ENV_FILE=<kompletter Frontend-.env Inhalt>`

## 4) Env-Inhalte (Domain-Betrieb)

Frontend (`FRONTEND_ENV_FILE`):

```dotenv
NUXT_PUBLIC_BACKEND_BASE_URL=https://germinai.de/api
```

Backend (`BACKEND_ENV_FILE`) wichtige Felder:

```dotenv
SPRING_PROFILES_ACTIVE=production
APP_FRONTEND_URL=https://germinai.de
ALLOWED_ORIGINS=https://germinai.de
OPENAI_API_KEY=not-configured
GOOGLE_API_KEY=not-configured
ENCRYPTION_SECRET=<openssl rand -base64 32>
ENCRYPTION_ACTIVE_KEY_VERSION=1
ENCRYPTION_DECRYPTION_SECRETS=
```

Hinweis:

1. Nutzer können ihre Modell-API-Keys später selbst in der App speichern.
2. `ENCRYPTION_SECRET` ist Pflicht und muss auf 32 Bytes dekodieren.

## 5) Google OAuth (für Login)

In Google Cloud Console (OAuth Client, Typ `Webanwendung`):

1. Autorisierte JavaScript-Quelle: `https://germinai.de`
2. Autorisierte Weiterleitungs-URI: `https://germinai.de/login/oauth2/code/google`

Dann `GOOGLE_CLIENT_ID` und `GOOGLE_CLIENT_SECRET` in `BACKEND_ENV_FILE` setzen.

## 6) Caddy + Let's Encrypt

Caddyfile-Vorlage in Repo:

1. `infra/caddy/Caddyfile`

## 7) CI-Deploy-Ablauf

Bei Push auf `main` mit Änderungen in `Backend/**` oder `infra/caddy/**` (Backend-Pipeline):

1. Backend-Archiv und Caddy-Archiv bauen
2. Auf Server nach `/tmp/chat-sribiddi-deploy-backend` hochladen
3. `.env` Datei aus Secret schreiben
4. Backend per Docker Compose neu bauen/starten
5. Caddyfile nach `/etc/caddy/Caddyfile` kopieren und Caddy reloaden

Bei Push auf `main` mit Änderungen in `Frontend/**` (Frontend-Pipeline):

1. Frontend-Archiv bauen
2. Auf Server nach `/tmp/chat-sribiddi-deploy-frontend` hochladen
3. `.env` Datei aus Secret schreiben
4. Frontend per Docker Compose neu bauen/starten

Bei Änderungen an beiden wird jede Pipeline unabhängig voneinander ausgelöst.

## 8) Verifikation

1. `https://germinai.de` lädt Frontend
2. `https://germinai.de/api/login` antwortet (Backend erreichbar über Proxy)
3. `docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"` zeigt laufende Container

## 9) Offene manuelle To-dos

1. DNS A-Record `germinai.de` auf `45.81.235.217` setzen.
2. Optional DNS A-Record `www.germinai.de` auf `45.81.235.217` setzen.
3. Falls ein AAAA-Record gesetzt ist: auf die korrekte IPv6 dieses Servers ändern oder entfernen.
4. Google OAuth Consent Screen finalisieren (falls noch nicht publiziert/konfiguriert).
5. Repository Secrets in GitHub mit Domain-Werten aktualisieren.
6. SSH-Key-basiertes Deploy statt Root-Passwort einrichten.
