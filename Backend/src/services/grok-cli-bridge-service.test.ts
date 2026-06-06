import type { ChildProcessWithoutNullStreams } from "node:child_process";
import { EventEmitter } from "node:events";
import { chmod, mkdtemp, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PassThrough, Writable } from "node:stream";
import { afterEach, describe, expect, it, vi } from "vitest";
import { env } from "../config/env.js";
import { GrokCliBridgeService, type GrokCliSpawn } from "./grok-cli-bridge-service.js";

describe("GrokCliBridgeService", () => {
  const previous = {
    enabled: env.GROK_CLI_ENABLED,
    bin: env.GROK_CLI_BIN,
    loginPromptTimeout: env.GROK_CLI_LOGIN_PROMPT_TIMEOUT_MS,
    loginTotalTimeout: env.GROK_CLI_LOGIN_TOTAL_TIMEOUT_MS,
    requestTimeout: env.GROK_CLI_REQUEST_TIMEOUT_MS,
    maxConcurrent: env.GROK_CLI_MAX_CONCURRENT_PER_USER,
    sessionsDir: env.GROK_CLI_SESSIONS_DIR,
    httpsProxy: process.env.HTTPS_PROXY,
  };

  afterEach(() => {
    env.GROK_CLI_ENABLED = previous.enabled;
    env.GROK_CLI_BIN = previous.bin;
    env.GROK_CLI_LOGIN_PROMPT_TIMEOUT_MS = previous.loginPromptTimeout;
    env.GROK_CLI_LOGIN_TOTAL_TIMEOUT_MS = previous.loginTotalTimeout;
    env.GROK_CLI_REQUEST_TIMEOUT_MS = previous.requestTimeout;
    env.GROK_CLI_MAX_CONCURRENT_PER_USER = previous.maxConcurrent;
    env.GROK_CLI_SESSIONS_DIR = previous.sessionsDir;
    if (previous.httpsProxy == null) delete process.env.HTTPS_PROXY;
    else process.env.HTTPS_PROXY = previous.httpsProxy;
    vi.restoreAllMocks();
  });

  it("starts ACP with no terminal, file write, or MCP capability and an isolated cwd", async () => {
    env.GROK_CLI_ENABLED = true;
    env.GROK_CLI_BIN = "grok-test";
    env.GROK_CLI_REQUEST_TIMEOUT_MS = 2_000;
    process.env.HTTPS_PROXY = "http://user:pass@proxy.example.test";
    const calls: Array<{
      command: string;
      args: string[];
      options: { cwd: string; env: NodeJS.ProcessEnv };
    }> = [];
    const requests: Array<{ method: string; params: unknown }> = [];
    const spawnProcess: GrokCliSpawn = (command, args, options) => {
      calls.push({ command, args, options });
      return fakeAcpProcess((message) => {
        requests.push({ method: message.method, params: message.params });
      });
    };
    const service = new GrokCliBridgeService(spawnProcess);

    const result = await service.generate({
      userId: "user-1",
      session: {
        cliHomeDir: "/private/grok/user-1",
        cliSessionId: "session-1",
        modelId: "grok-build",
      },
      modelId: "grok-build",
      messages: [{ role: "user", content: "hello" }],
    });

    expect(result.text).toBe("safe response");
    expect(calls).toHaveLength(1);
    expect(calls[0]?.command).toBe("grok-test");
    expect(calls[0]?.args).toEqual([
      "--no-auto-update",
      "--permission-mode",
      "dontAsk",
      "--sandbox",
      "read-only",
      "agent",
      "stdio",
    ]);
    expect(calls[0]?.options.cwd).toContain("chat-sribiddi-grok-");
    expect(calls[0]?.options.cwd).not.toContain("chat-sribiddi/Backend");
    expect(calls[0]?.options.env.HOME).toBe("/private/grok/user-1");
    expect(calls[0]?.options.env.XAI_API_KEY).toBeUndefined();
    expect(calls[0]?.options.env.DATABASE_URL).toBeUndefined();
    expect(calls[0]?.options.env.HTTPS_PROXY).toBeUndefined();
    expect(requests.find((item) => item.method === "initialize")?.params).toEqual({
      protocolVersion: 1,
      clientCapabilities: {
        fs: { readTextFile: false, writeTextFile: false },
        terminal: false,
      },
    });
    expect(requests.find((item) => item.method === "session/new")?.params).toMatchObject({
      mcpServers: [],
      cwd: calls[0]?.options.cwd,
    });
  });

  it("enforces private permissions for existing Grok session directories", async () => {
    env.GROK_CLI_ENABLED = true;
    env.GROK_CLI_BIN = "grok-test";
    env.GROK_CLI_LOGIN_PROMPT_TIMEOUT_MS = 2_000;
    env.GROK_CLI_LOGIN_TOTAL_TIMEOUT_MS = 10_000;
    const sessionsDir = await mkdtemp(join(tmpdir(), "grok-sessions-"));
    await chmod(sessionsDir, 0o777);
    env.GROK_CLI_SESSIONS_DIR = sessionsDir;
    const children: ChildProcessWithoutNullStreams[] = [];
    const calls: Array<{ options: { env: NodeJS.ProcessEnv } }> = [];
    const service = new GrokCliBridgeService((_command, _args, options) => {
      calls.push({ options });
      const child = fakeDeviceLoginProcess();
      children.push(child);
      return child;
    });

    try {
      const login = await service.startDeviceLogin("user-2");

      expect(login.userCode).toBe("XAI1-1234");
      expect(await modeOf(sessionsDir)).toBe(0o700);
      const homeDir = calls[0]?.options.env.HOME;
      if (!homeDir) throw new Error("Grok HOME was not configured");
      expect(await modeOf(homeDir)).toBe(0o700);
      expect(await modeOf(join(homeDir, ".config"))).toBe(0o700);
      expect(await modeOf(join(homeDir, ".cache"))).toBe(0o700);
      expect(await modeOf(join(homeDir, ".local"))).toBe(0o700);
    } finally {
      for (const child of children) child.kill("SIGTERM");
      await rm(sessionsDir, { recursive: true, force: true });
    }
  });
});

function fakeAcpProcess(
  onRequest: (message: { id: number; method: string; params: unknown }) => void,
): ChildProcessWithoutNullStreams {
  const stdout = new PassThrough();
  const stderr = new PassThrough();
  const child = new EventEmitter() as ChildProcessWithoutNullStreams;
  let killed = false;
  child.stdout = stdout;
  child.stderr = stderr;
  Object.defineProperty(child, "killed", { get: () => killed });
  child.kill = vi.fn(() => {
    killed = true;
    child.emit("exit", 0, null);
    return true;
  });
  child.stdin = new Writable({
    write(chunk, _encoding, callback) {
      const message = JSON.parse(String(chunk)) as { id: number; method: string; params: unknown };
      onRequest(message);
      if (message.method === "initialize") {
        stdout.write(
          `${JSON.stringify({ id: message.id, result: { authMethods: [{ id: "cached_token" }] } })}\n`,
        );
      } else if (message.method === "authenticate") {
        stdout.write(`${JSON.stringify({ id: message.id, result: {} })}\n`);
      } else if (message.method === "session/new") {
        stdout.write(`${JSON.stringify({ id: message.id, result: { sessionId: "session-1" } })}\n`);
      } else if (message.method === "session/prompt") {
        stdout.write(
          `${JSON.stringify({
            method: "session/update",
            params: {
              update: {
                sessionUpdate: "agent_message_chunk",
                content: { text: "safe response" },
              },
            },
          })}\n`,
        );
        stdout.write(`${JSON.stringify({ id: message.id, result: { stopReason: "end" } })}\n`);
      }
      callback();
    },
  });
  return child;
}

function fakeDeviceLoginProcess(): ChildProcessWithoutNullStreams {
  const stdout = new PassThrough();
  const stderr = new PassThrough();
  const stdin = new PassThrough();
  const child = new EventEmitter() as ChildProcessWithoutNullStreams;
  let killed = false;
  child.stdout = stdout;
  child.stderr = stderr;
  child.stdin = stdin;
  Object.defineProperty(child, "killed", { get: () => killed });
  child.kill = vi.fn(() => {
    killed = true;
    child.emit("exit", 0, null);
    return true;
  });
  process.nextTick(() => {
    stdout.write("Open https://auth.x.ai/device and enter XAI1-1234\n");
  });
  return child;
}

async function modeOf(path: string) {
  return (await stat(path)).mode & 0o777;
}
