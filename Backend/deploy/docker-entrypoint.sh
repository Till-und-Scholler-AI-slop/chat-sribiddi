#!/bin/sh
set -eu

CODEX_ROOT="${CODEX_CONNECTIONS_ROOT:-/var/lib/chat-sribiddi/codex-connections}"
GROK_ROOT="${GROK_CLI_SESSIONS_DIR:-/var/lib/chat-sribiddi/grok-sessions}"

mkdir -p "$CODEX_ROOT" "$GROK_ROOT"
chown -R appuser:appuser "$CODEX_ROOT" "$GROK_ROOT"
chmod 700 "$CODEX_ROOT" "$GROK_ROOT"

exec gosu appuser "$@"
