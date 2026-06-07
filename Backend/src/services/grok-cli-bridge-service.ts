import { type ChildProcessWithoutNullStreams, spawn as nodeSpawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { chmod, mkdir, mkdtemp, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";
import type { ModelMessage } from "ai";
import { env } from "../config/env.js";
import { badRequest, upstreamError } from "../errors/http-error.js";

export type GrokCliSession = {
  cliHomeDir: string;
  cliSessionId: string;
  modelId: string;
};

export type GrokCliDeviceStart = {
  mode: "DEVICE_CODE";
  loginId: string;
  userCode: string;
  verificationUri: string;
  verificationUriComplete: string | null;
  expiresAt: string;
  intervalSeconds: number;
};

export type GrokCliDevicePoll =
  | {
      mode: "PENDING";
      expiresAt: string;
      intervalSeconds: number;
      message: string | null;
    }
  | {
      mode: "READY";
      session: GrokCliSession;
      accountLabel: string;
    };

export type GrokCliGenerateResult = {
  text: string;
  usage: null;
};

export type GrokCliBridge = {
  startDeviceLogin(userId: string): Promise<GrokCliDeviceStart>;
  pollDeviceLogin(userId: string, loginId: string): Promise<GrokCliDevicePoll>;
  generate(input: {
    userId: string;
    session: GrokCliSession;
    modelId: string;
    messages: ModelMessage[];
  }): Promise<GrokCliGenerateResult>;
};

type PendingLogin = {
  loginId: string;
  userId: string;
  homeDir: string;
  workspaceDir: string;
  child: ChildProcessWithoutNullStreams;
  output: string;
  expiresAt: number;
  intervalSeconds: number;
  exitCode: number | null;
  signal: NodeJS.Signals | null;
  error: Error | null;
};

type AcpPending = {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
};

export type GrokCliSpawn = (
  command: string,
  args: string[],
  options: {
    cwd: string;
    env: NodeJS.ProcessEnv;
    stdio: "pipe";
  },
) => ChildProcessWithoutNullStreams;

const defaultSpawn: GrokCliSpawn = (command, args, options) => nodeSpawn(command, args, options);

export class GrokCliUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GrokCliUnavailableError";
  }
}

export class GrokCliBridgeService implements GrokCliBridge {
  private readonly pendingLogins = new Map<string, PendingLogin>();
  private readonly activeByUser = new Map<string, number>();

  constructor(private readonly spawnProcess: GrokCliSpawn = defaultSpawn) {}

  async startDeviceLogin(userId: string): Promise<GrokCliDeviceStart> {
    this.requireEnabled();
    this.cancelPendingLoginsForUser(userId);
    const loginId = randomToken(24);
    const homeDir = await this.userHomeDir(userId);
    const workspaceDir = join(homeDir, "workspace");
    await ensurePrivateDir(workspaceDir);
    const child = this.spawnProcess(
      env.GROK_CLI_BIN,
      ["--no-auto-update", "login", "--device-auth"],
      {
        cwd: workspaceDir,
        env: safeProcessEnv(homeDir),
        stdio: "pipe",
      },
    );
    const pending: PendingLogin = {
      loginId,
      userId,
      homeDir,
      workspaceDir,
      child,
      output: "",
      expiresAt: Date.now() + env.GROK_CLI_LOGIN_TOTAL_TIMEOUT_MS,
      intervalSeconds: 5,
      exitCode: null,
      signal: null,
      error: null,
    };
    this.pendingLogins.set(loginId, pending);
    child.stdout.on("data", (chunk) => appendOutput(pending, chunk));
    child.stderr.on("data", (chunk) => appendOutput(pending, chunk));
    child.once("error", (error) => {
      pending.error = error;
    });
    child.once("exit", (code, signal) => {
      pending.exitCode = code;
      pending.signal = signal;
      if (this.pendingLogins.get(loginId) === pending && Date.now() > pending.expiresAt) {
        this.pendingLogins.delete(loginId);
      }
    });
    setTimeout(() => {
      if (this.pendingLogins.get(loginId) === pending) {
        this.pendingLogins.delete(loginId);
        if (pending.exitCode == null) pending.child.kill("SIGTERM");
      }
    }, env.GROK_CLI_LOGIN_TOTAL_TIMEOUT_MS).unref();

    try {
      const challenge = await waitForDeviceChallenge(pending);
      return {
        mode: "DEVICE_CODE",
        loginId,
        userCode: challenge.userCode,
        verificationUri: challenge.verificationUri,
        verificationUriComplete: challenge.verificationUriComplete,
        expiresAt: new Date(pending.expiresAt).toISOString(),
        intervalSeconds: pending.intervalSeconds,
      };
    } catch (error) {
      this.pendingLogins.delete(loginId);
      pending.child.kill("SIGTERM");
      throw error;
    }
  }

  async pollDeviceLogin(userId: string, loginId: string): Promise<GrokCliDevicePoll> {
    this.requireEnabled();
    const pending = this.pendingLogins.get(loginId);
    if (!pending || pending.userId !== userId) {
      const recovered = await this.recoverCompletedLogin(userId, loginId);
      if (recovered) return recovered;
      throw badRequest(
        "Grok login state is invalid or expired. Start a new xAI login and keep this window open.",
      );
    }
    if (Date.now() > pending.expiresAt && pending.exitCode == null) {
      this.pendingLogins.delete(loginId);
      pending.child.kill("SIGTERM");
      throw badRequest("Grok login timed out before authorization completed");
    }
    if (pending.error) {
      this.pendingLogins.delete(loginId);
      throw new GrokCliUnavailableError(`Grok CLI failed to start: ${pending.error.message}`);
    }
    if (pending.exitCode == null) {
      return {
        mode: "PENDING",
        expiresAt: new Date(pending.expiresAt).toISOString(),
        intervalSeconds: pending.intervalSeconds,
        message: "Waiting for xAI account authorization.",
      };
    }
    this.pendingLogins.delete(loginId);
    if (pending.exitCode !== 0) {
      throw badRequest(
        `Grok login failed with exit code ${pending.exitCode}${pending.signal ? ` (${pending.signal})` : ""}`,
      );
    }
    const session = {
      cliHomeDir: pending.homeDir,
      cliSessionId: loginId,
      modelId: "grok-build",
    };
    await this.validateSession(userId, session);
    return {
      mode: "READY",
      session,
      accountLabel: "Grok Build subscription",
    };
  }

  private async recoverCompletedLogin(
    userId: string,
    loginId: string,
  ): Promise<Extract<GrokCliDevicePoll, { mode: "READY" }> | null> {
    const session = {
      cliHomeDir: await this.userHomeDir(userId),
      cliSessionId: loginId,
      modelId: "grok-build",
    };
    try {
      await this.validateSession(userId, session);
      return {
        mode: "READY",
        session,
        accountLabel: "Grok Build subscription",
      };
    } catch {
      return null;
    }
  }

  private cancelPendingLoginsForUser(userId: string) {
    for (const [loginId, pending] of this.pendingLogins.entries()) {
      if (pending.userId !== userId) continue;
      this.pendingLogins.delete(loginId);
      if (pending.exitCode == null) pending.child.kill("SIGTERM");
    }
  }

  async generate(input: {
    userId: string;
    session: GrokCliSession;
    modelId: string;
    messages: ModelMessage[];
  }): Promise<GrokCliGenerateResult> {
    this.requireEnabled();
    return this.withUserSlot(input.userId, async () => {
      const workspaceDir = await mkdtemp(join(tmpdir(), "chat-sribiddi-grok-"));
      try {
        const text = await this.generateWithAcp({
          homeDir: input.session.cliHomeDir,
          workspaceDir,
          prompt: formatPrompt(input.messages),
        });
        return { text, usage: null };
      } finally {
        await rm(workspaceDir, { recursive: true, force: true });
      }
    });
  }

  private async validateSession(userId: string, session: GrokCliSession) {
    const result = await this.generate({
      userId,
      session,
      modelId: session.modelId,
      messages: [{ role: "user", content: "Reply with OK." }],
    });
    if (!result.text.trim()) throw badRequest("Grok subscription validation returned no text");
  }

  private async generateWithAcp(input: {
    homeDir: string;
    workspaceDir: string;
    prompt: string;
  }): Promise<string> {
    const child = this.spawnProcess(
      env.GROK_CLI_BIN,
      [
        "--no-auto-update",
        "--permission-mode",
        "dontAsk",
        "--sandbox",
        "read-only",
        "agent",
        "stdio",
      ],
      {
        cwd: input.workspaceDir,
        env: safeProcessEnv(input.homeDir),
        stdio: "pipe",
      },
    );
    let nextId = 1;
    let outputBytes = 0;
    let assistantText = "";
    const pending = new Map<number, AcpPending>();
    const stderrChunks: string[] = [];
    const rl = createInterface({ input: child.stdout });

    const cleanup = (error?: Error) => {
      for (const item of pending.values()) {
        clearTimeout(item.timer);
        if (error) item.reject(error);
      }
      pending.clear();
      rl.close();
      if (!child.killed) child.kill("SIGTERM");
    };

    child.stderr.on("data", (chunk) => {
      const text = String(chunk);
      stderrChunks.push(text);
      outputBytes += Buffer.byteLength(text);
      if (outputBytes > env.GROK_CLI_MAX_OUTPUT_BYTES) {
        cleanup(upstreamError("Grok CLI output exceeded the configured limit"));
      }
    });
    child.once("error", (error) => {
      cleanup(new GrokCliUnavailableError(`Grok CLI failed to start: ${error.message}`));
    });
    child.once("exit", (code, signal) => {
      if (pending.size > 0) {
        cleanup(
          upstreamError(
            `Grok CLI exited before completing the request: ${code ?? signal ?? "unknown"}`,
            stderrChunks.join("").slice(-2000),
          ),
        );
      }
    });

    rl.on("line", (line) => {
      outputBytes += Buffer.byteLength(line);
      if (outputBytes > env.GROK_CLI_MAX_OUTPUT_BYTES) {
        cleanup(upstreamError("Grok CLI output exceeded the configured limit"));
        return;
      }
      let message: {
        id?: number;
        method?: string;
        params?: { update?: { sessionUpdate?: string; content?: { text?: string } } };
        result?: unknown;
        error?: { message?: string };
      };
      try {
        message = JSON.parse(line);
      } catch {
        return;
      }
      const update = message.params?.update;
      if (
        message.method === "session/update" &&
        update?.sessionUpdate === "agent_message_chunk" &&
        typeof update.content?.text === "string"
      ) {
        assistantText += update.content.text;
        return;
      }
      if (typeof message.id !== "number") return;
      const item = pending.get(message.id);
      if (!item) return;
      pending.delete(message.id);
      clearTimeout(item.timer);
      if (message.error) {
        item.reject(upstreamError(message.error.message ?? "Grok ACP request failed"));
      } else {
        item.resolve(message.result ?? {});
      }
    });

    const request = (method: string, params: unknown) => {
      const id = nextId;
      nextId += 1;
      return new Promise<unknown>((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(upstreamError(`${method} timed out`));
        }, env.GROK_CLI_REQUEST_TIMEOUT_MS);
        timer.unref();
        pending.set(id, { resolve, reject, timer });
        child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
      });
    };

    try {
      const init = (await request("initialize", {
        protocolVersion: 1,
        clientCapabilities: {
          fs: { readTextFile: false, writeTextFile: false },
          terminal: false,
        },
      })) as { authMethods?: Array<{ id?: string }> };
      const authMethods = new Set((init.authMethods ?? []).map((method) => method.id));
      if (!authMethods.has("cached_token")) {
        throw badRequest("Run the xAI Grok subscription connection flow before chatting");
      }
      await request("authenticate", { methodId: "cached_token", _meta: { headless: true } });
      const session = (await request("session/new", {
        cwd: input.workspaceDir,
        mcpServers: [],
      })) as { sessionId?: string };
      if (!session.sessionId) throw upstreamError("Grok ACP did not return a session id");
      await request("session/prompt", {
        sessionId: session.sessionId,
        prompt: [{ type: "text", text: input.prompt }],
      });
      await delay(250);
      return assistantText.trim();
    } finally {
      cleanup();
    }
  }

  private requireEnabled() {
    if (!env.GROK_CLI_ENABLED) {
      throw new GrokCliUnavailableError("Grok Build subscription bridge is disabled.");
    }
  }

  private async userHomeDir(userId: string) {
    await ensurePrivateDir(env.GROK_CLI_SESSIONS_DIR);
    const homeDir = join(env.GROK_CLI_SESSIONS_DIR, sha256(userId));
    await ensurePrivateDir(homeDir);
    await ensurePrivateDir(join(homeDir, ".config"));
    await ensurePrivateDir(join(homeDir, ".cache"));
    await ensurePrivateDir(join(homeDir, ".local"));
    return homeDir;
  }

  private async withUserSlot<T>(userId: string, fn: () => Promise<T>) {
    const active = this.activeByUser.get(userId) ?? 0;
    if (active >= env.GROK_CLI_MAX_CONCURRENT_PER_USER) {
      throw upstreamError("A Grok subscription request is already running for this user");
    }
    this.activeByUser.set(userId, active + 1);
    try {
      return await fn();
    } finally {
      const next = (this.activeByUser.get(userId) ?? 1) - 1;
      if (next > 0) this.activeByUser.set(userId, next);
      else this.activeByUser.delete(userId);
    }
  }
}

function safeProcessEnv(homeDir: string) {
  const result: NodeJS.ProcessEnv = {
    HOME: homeDir,
    XDG_CONFIG_HOME: join(homeDir, ".config"),
    XDG_CACHE_HOME: join(homeDir, ".cache"),
    XDG_DATA_HOME: join(homeDir, ".local", "share"),
    TMPDIR: tmpdir(),
    PATH: process.env.PATH ?? "/usr/bin:/bin",
    LANG: process.env.LANG ?? "C.UTF-8",
    LC_ALL: process.env.LC_ALL,
    HTTPS_PROXY: safeProxyEnv(process.env.HTTPS_PROXY),
    HTTP_PROXY: safeProxyEnv(process.env.HTTP_PROXY),
    NO_PROXY: process.env.NO_PROXY,
  };
  for (const key of Object.keys(result)) {
    if (result[key] == null) delete result[key];
  }
  return result;
}

function safeProxyEnv(value: string | undefined) {
  if (!value) return undefined;
  try {
    const parsed = new URL(value);
    return parsed.username || parsed.password ? undefined : value;
  } catch {
    return value.includes("@") ? undefined : value;
  }
}

async function ensurePrivateDir(path: string) {
  await mkdir(path, { recursive: true, mode: 0o700 });
  const info = await stat(path);
  if (!info.isDirectory()) throw upstreamError(`${path} is not a directory`);
  if ((info.mode & 0o777) !== 0o700) {
    await chmod(path, 0o700);
  }
}

function appendOutput(pending: PendingLogin, chunk: Buffer | string) {
  pending.output += String(chunk);
  if (Buffer.byteLength(pending.output) > env.GROK_CLI_MAX_OUTPUT_BYTES) {
    pending.error = upstreamError("Grok CLI login output exceeded the configured limit");
    pending.child.kill("SIGTERM");
  }
}

async function waitForDeviceChallenge(pending: PendingLogin) {
  const started = Date.now();
  while (Date.now() - started < env.GROK_CLI_LOGIN_PROMPT_TIMEOUT_MS) {
    if (pending.error) {
      if ("code" in pending.error && pending.error.code === "ENOENT") {
        throw new GrokCliUnavailableError(`Grok CLI binary not found: ${env.GROK_CLI_BIN}`);
      }
      throw pending.error;
    }
    const challenge = extractDeviceChallenge(pending.output);
    if (challenge) return challenge;
    if (pending.exitCode != null) {
      throw badRequest(`Grok login exited before returning a device code (${pending.exitCode})`);
    }
    await delay(100);
  }
  throw badRequest("Grok login did not return a device code in time");
}

function extractDeviceChallenge(output: string) {
  const verificationUri = extractVerificationUri(output);
  const queryUserCode = verificationUri ? userCodeFromUri(verificationUri) : null;
  const userCode =
    queryUserCode ??
    output.match(/\b[A-Z0-9]{4,}(?:-[A-Z0-9]{4,})+\b/)?.[0] ??
    output.match(/(?:user\s*)?code[:\s]+([A-Z0-9-]{6,})/i)?.[1] ??
    null;
  if (!verificationUri || !userCode) return null;
  return {
    userCode,
    verificationUri,
    verificationUriComplete: verificationUri.includes(userCode) ? verificationUri : null,
  };
}

function extractVerificationUri(output: string) {
  const urls = output.match(/https:\/\/[^\s)'"<>]+/gi) ?? [];
  return (
    urls.map((url) => url.replace(/[.,;:]+$/, "")).find((url) => Boolean(userCodeFromUri(url))) ??
    urls[0]?.replace(/[.,;:]+$/, "") ??
    null
  );
}

function userCodeFromUri(value: string) {
  try {
    return new URL(value).searchParams.get("user_code")?.trim() || null;
  } catch {
    return null;
  }
}

function formatPrompt(messages: ModelMessage[]) {
  return messages
    .map((message) => {
      const text = messageText(message);
      return text ? `${message.role.toUpperCase()}:\n${text}` : "";
    })
    .filter(Boolean)
    .join("\n\n");
}

function messageText(message: ModelMessage) {
  const content = message.content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  const textParts: string[] = [];
  for (const part of content) {
    if (part.type === "text" && part.text.trim()) {
      textParts.push(part.text);
      continue;
    }
    throw upstreamError("Grok subscription bridge currently supports text-only chat messages");
  }
  return textParts.join("\n");
}

function randomToken(bytes: number) {
  return randomBytes(bytes).toString("base64url");
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
