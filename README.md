<div align="center">

# 🗣️ chat-sribiddi

**The open-source AI chat platform with personality.**

[![GitHub release](https://img.shields.io/github/release/Till-und-Scholler-AI-slop/chat-sribiddi?include_prereleases)](https://github.com/Till-und-Scholler-AI-slop/chat-sribiddi/releases)
[![Pull Requests](https://img.shields.io/github/issues-pr/Till-und-Scholler-AI-slop/chat-sribiddi?color=blue)](https://github.com/Till-und-Scholler-AI-slop/chat-sribiddi/pulls)
[![License](https://img.shields.io/github/license/Till-und-Scholler-AI-slop/chat-sribiddi)](LICENSE)
[![Docker](https://img.shields.io/badge/Docker-ready-2496ED?logo=docker&logoColor=white)](#-quick-start)

A lightweight, blazing-fast AI chat wrapper that supports multiple models with bring-your-own provider keys and runs anywhere — from your phone to your server.

👉 **Live at [germinai.de](https://germinai.de)**

</div>

---

## 📸 Preview

<!-- Add your screenshots here -->
|                      Web                       |                        Mobile                        |
|:----------------------------------------------:|:----------------------------------------------------:|
| ![Web screenshot](docs/assets/web-preview.png) | ![Mobile screenshot](docs/assets/mobile-preview.png) |

> *Replace placeholders above with real screenshots after first deployment.*

---

## ✨ Features

- **🤖 Multi-Model Chat** — Switch between AI models on the fly. One interface, unlimited intelligence.
- **🔑 BYOK Access** — Signed-in users chat with encrypted provider API keys; guests can use OpenRouter Free when configured.
- **📱 Mobile + Web** — Fully responsive PWA. Chat on any device, any screen size.
- **🔐 OAuth2 Auth** — Secure login via OAuth2. No passwords to manage, no accounts to remember.
- **⚡ Lightning Fast** — Hono backend with PostgreSQL and Redis keeps responses snappy.
- **🐳 Self-Hostable** — One `docker compose up` and you're live. Own your data.
- **🔄 Active Development** — 201+ PRs merged. We ship daily.

---

## 🛠 Tech Stack

| Layer | Tech |
|:---:|:---|
| **Backend** | TypeScript · Hono · Kysely |
| **Frontend** | Vue 3 · Nuxt 3 · Nuxt UI · TypeScript |
| **Cache** | Redis |
| **Reverse Proxy** | Caddy |
| **Provider access** | BYOK keys + optional guest OpenRouter Free |
| **Hosting** | Docker & Docker Compose |

---

## 🚀 Quick Start

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) & Docker Compose
- An API key for at least one AI provider (OpenAI, Anthropic, etc.)

### 1. Clone the repo

```bash
git clone https://github.com/Till-und-Scholler-AI-slop/chat-sribiddi.git
cd chat-sribiddi
```

### 2. Configure environment

```bash
cp .env.example .env
# Edit .env — set OAuth2 credentials, database config, and optional guest OpenRouter key
```

### 3. Spin it up

```bash
docker compose up -d
```

That's it. The app will be available at `https://localhost` (Caddy handles TLS automatically).

> For production deployments, see the [full deployment guide](docs/deployment.md).

---

## 🤝 Contributing

We love contributions! chat-sribiddi moves fast and there's always room for more.

### Getting Started

1. **Fork** the repo and create your branch: `git checkout -b feat/my-feature`
2. **Make your changes** and ensure tests pass: `cd Backend && npm test`
3. **Commit** with a clear message: `git commit -m "feat: add my awesome feature"`
4. **Push** and open a Pull Request

### Guidelines

- Follow [Conventional Commits](https://www.conventionalcommits.org/)
- Write meaningful PR descriptions
- Keep changes focused — one feature per PR
- Be respectful in discussions

### Contributors

Thanks to everyone who's shipped code and ideas:

<a href="https://github.com/Till-und-Scholler-AI-slop/chat-sribiddi/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=Till-und-Scholler-AI-slop/chat-sribiddi" alt="Contributors" />
</a>

---

## 📜 License

This project is licensed under the terms of the [LICENSE](LICENSE) file in the root directory.

---

## 🔗 Links

|                 |                                                                                        |
|:----------------|:---------------------------------------------------------------------------------------|
| 🌐 **Live App** | [germinai.de](https://germinai.de)                                                     |
| 📦 **Releases** | [GitHub Releases](https://github.com/Till-und-Scholler-AI-slop/chat-sribiddi/releases) |
| 🐛 **Issues**   | [GitHub Issues](https://github.com/Till-und-Scholler-AI-slop/chat-sribiddi/issues)     |

---

<div align="center">

*Built with ☕ and questionable amounts of AI assistance by [Till-und-Scholler-AI-slop](https://github.com/Till-und-Scholler-AI-slop)*

</div>
