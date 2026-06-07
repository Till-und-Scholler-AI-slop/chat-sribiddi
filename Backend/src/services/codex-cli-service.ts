import { type ChildProcess, spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { chmod, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { env } from "../config/env.js";
import { badRequest } from "../errors/http-error.js";
import { hasText } from "../utils/strings.js";

const ANSI_ESCAPE_PATTERN = new RegExp(`${String.fromCharCode(27)}\\[[0-?]*[ -/]*[@-~]`, "g");
const CODEX_DEVICE_AUTH_OUTPUT_LIMIT = 16_384;
const CODEX_DEVICE_AUTH_RESULT_TTL_MS = 60_000;
const CODEX_DEVICE_AUTH_KILL_GRACE_MS = 5_000;
const CODEX_MODEL_CATALOG_CACHE_TTL_MS = 10 * 60_000;
const CODEX_MODEL_CATALOG_TIMEOUT_MS = 30_000;
const CODEX_MODEL_CATALOG_OUTPUT_LIMIT = 20 * 1024 * 1024;

export type CodexCliModel = {
  id: string;
  label: string;
  description: string | null;
};

export type CodexDeviceAuthChallenge = {
  mode: "DEVICE_CODE";
  state: string;
  deviceCode: string;
  userCode: string;
  verificationUri: string;
  verificationUriComplete: string | null;
  expiresAt: string;
  intervalSeconds: number;
  codexHome: string;
  message: string | null;
};

export type CodexDeviceAuthPollResult =
  | {
      mode: "PENDING";
      expiresAt: string;
      intervalSeconds: number;
      message: string | null;
    }
  | {
      mode: "READY";
      codexHome: string;
      modelId: string;
      modelLabel: string;
      models: CodexCliModel[];
      accountLabel: string;
      message: string | null;
    }
  | {
      mode: "UNAVAILABLE";
      message: string;
    };

type PendingCodexDeviceAuth = {
  userId: string;
  connectionProfileId: string;
  codexHome: string;
  child: ChildProcess;
  output: string;
  errorOutput: string;
  expiresAt: number;
  intervalSeconds: number;
  exitCode: number | null;
  exitSignal: NodeJS.Signals | null;
  cleanupTimer: ReturnType<typeof setTimeout> | null;
  forceKillTimer: ReturnType<typeof setTimeout> | null;
};

export class CodexCliService {
  private readonly pending = new Map<string, PendingCodexDeviceAuth>();
  private readonly modelCatalogCache = new Map<
    string,
    { expiresAt: number; models: CodexCliModel[] }
  >();

  enabled() {
    return env.OPENAI_CODEX_SUBSCRIPTION_ENABLED;
  }

  async startDeviceAuth(
    userId: string,
    connectionProfileId: string,
  ): Promise<CodexDeviceAuthChallenge | { mode: "UNAVAILABLE"; message: string }> {
    if (!this.enabled()) {
      return {
        mode: "UNAVAILABLE" as const,
        message: "OpenAI Codex subscription login is disabled on this server.",
      };
    }

    const codexHome = await prepareCodexHome(userId, connectionProfileId);
    const child = spawn(env.CODEX_CLI_BINARY, ["login", "--device-auth"], {
      env: codexEnv(codexHome),
      stdio: ["ignore", "pipe", "pipe"],
    });
    const state = randomToken(32);
    const pending: PendingCodexDeviceAuth = {
      userId,
      connectionProfileId,
      codexHome,
      child,
      output: "",
      errorOutput: "",
      expiresAt: Date.now() + 15 * 60_000,
      intervalSeconds: 5,
      exitCode: null,
      exitSignal: null,
      cleanupTimer: null,
      forceKillTimer: null,
    };
    this.pending.set(state, pending);
    this.schedulePendingExpiry(state, pending);
    child.stdout.on("data", (chunk: Buffer) => {
      pending.output = appendBounded(pending.output, chunk.toString("utf8"));
    });
    child.stderr.on("data", (chunk: Buffer) => {
      pending.errorOutput = appendBounded(pending.errorOutput, chunk.toString("utf8"));
    });
    child.on("error", (error) => {
      pending.errorOutput = appendBounded(pending.errorOutput, `\n${error.message}`);
      pending.exitCode = -1;
    });
    child.on("close", (code, signal) => {
      pending.exitCode = code;
      pending.exitSignal = signal;
      if (this.pending.get(state) === pending) {
        this.schedulePendingRemoval(state, pending);
      }
    });

    try {
      const challenge = await waitForDeviceAuthChallenge(pending);
      pending.expiresAt = challenge.expiresAt.getTime();
      pending.intervalSeconds = challenge.intervalSeconds;
      if (pending.exitCode == null && pending.exitSignal == null) {
        this.schedulePendingExpiry(state, pending);
      } else {
        this.schedulePendingRemoval(state, pending);
      }
      return {
        mode: "DEVICE_CODE" as const,
        state,
        deviceCode: state,
        userCode: challenge.userCode,
        verificationUri: challenge.verificationUri,
        verificationUriComplete: challenge.verificationUri,
        expiresAt: challenge.expiresAt.toISOString(),
        intervalSeconds: challenge.intervalSeconds,
        codexHome,
        message: null,
      } satisfies CodexDeviceAuthChallenge;
    } catch (error) {
      this.deletePending(state, true);
      throw badRequest(error instanceof Error ? error.message : "Codex device login did not start");
    }
  }

  async completeDeviceAuth(
    userId: string,
    state: string,
    connectionProfileId: string | null | undefined,
  ): Promise<CodexDeviceAuthPollResult> {
    const pending = this.pending.get(state);
    if (
      !pending ||
      pending.userId !== userId ||
      (hasText(connectionProfileId) && pending.connectionProfileId !== connectionProfileId.trim())
    ) {
      throw badRequest("Codex device-code state is invalid or expired");
    }
    if (pending.expiresAt < Date.now()) {
      this.deletePending(state, true);
      return {
        mode: "UNAVAILABLE",
        message: "Codex device-code login expired. Start the ChatGPT/Codex login again.",
      };
    }
    if (pending.exitCode == null && pending.exitSignal == null) {
      return {
        mode: "PENDING",
        expiresAt: new Date(pending.expiresAt).toISOString(),
        intervalSeconds: pending.intervalSeconds,
        message: "Waiting for ChatGPT/Codex sign-in to finish.",
      };
    }
    this.deletePending(state, false);
    if (pending.exitCode !== 0) {
      return {
        mode: "UNAVAILABLE",
        message: sanitizedCodexFailure(pending),
      };
    }
    const loggedIn = await codexLoginStatus(pending.codexHome);
    if (!loggedIn) {
      return {
        mode: "UNAVAILABLE",
        message: "Codex login finished, but the CLI does not report an active ChatGPT session.",
      };
    }
    let models: CodexCliModel[];
    try {
      models = await this.listModels(pending.codexHome, { forceRefresh: true });
    } catch (error) {
      return {
        mode: "UNAVAILABLE",
        message: `Codex login finished, but the CLI model catalog could not be loaded: ${
          error instanceof Error ? error.message : "unknown error"
        }`,
      };
    }
    const selected = selectCodexDefaultModel(models);
    return {
      mode: "READY",
      codexHome: pending.codexHome,
      modelId: selected.id,
      modelLabel: selected.label,
      models,
      accountLabel: "ChatGPT/Codex subscription",
      message: null,
    };
  }

  async listModels(
    codexHome: string,
    options: { forceRefresh?: boolean } = {},
  ): Promise<CodexCliModel[]> {
    const key = codexHome.trim();
    const cached = this.modelCatalogCache.get(key);
    if (!options.forceRefresh && cached && cached.expiresAt > Date.now()) return cached.models;
    const models = await loadCodexModelsFromCli(key);
    this.modelCatalogCache.set(key, {
      expiresAt: Date.now() + CODEX_MODEL_CATALOG_CACHE_TTL_MS,
      models,
    });
    return models;
  }

  async deleteCodexHome(codexHome: string | null | undefined) {
    if (!hasText(codexHome)) return;
    await rm(codexHome.trim(), { recursive: true, force: true });
    this.modelCatalogCache.delete(codexHome.trim());
  }

  private schedulePendingExpiry(state: string, pending: PendingCodexDeviceAuth) {
    this.replacePendingTimer(state, pending, Math.max(pending.expiresAt - Date.now(), 1_000));
  }

  private schedulePendingRemoval(state: string, pending: PendingCodexDeviceAuth) {
    this.replacePendingTimer(state, pending, CODEX_DEVICE_AUTH_RESULT_TTL_MS);
  }

  private replacePendingTimer(state: string, pending: PendingCodexDeviceAuth, delayMs: number) {
    clearPendingTimer(pending);
    pending.cleanupTimer = setTimeout(() => {
      this.deletePending(state, true);
    }, delayMs);
    pending.cleanupTimer.unref?.();
  }

  private deletePending(state: string, killChild: boolean) {
    const pending = this.pending.get(state);
    if (!pending) return;
    this.pending.delete(state);
    clearPendingTimer(pending);
    if (!killChild || pending.exitCode != null || pending.exitSignal != null) return;
    pending.child.kill("SIGTERM");
    pending.forceKillTimer = setTimeout(() => {
      if (pending.exitCode == null && pending.exitSignal == null) {
        pending.child.kill("SIGKILL");
      }
    }, CODEX_DEVICE_AUTH_KILL_GRACE_MS);
    pending.forceKillTimer.unref?.();
    pending.child.once("close", () => {
      if (pending.forceKillTimer) clearTimeout(pending.forceKillTimer);
      pending.forceKillTimer = null;
    });
  }
}

export function parseCodexDeviceAuthChallenge(output: string) {
  const text = stripAnsi(output);
  const verificationUri = text.match(/https:\/\/auth\.openai\.com\/codex\/device\b\S*/)?.[0];
  const userCode = text.match(/\b[A-Z0-9]{4,}-[A-Z0-9-]{4,}\b/)?.[0];
  if (!verificationUri || !userCode) return null;
  const expiresIn = text.match(/expires in\s+(\d+)\s+(seconds?|minutes?)/i);
  const amount = expiresIn ? Number(expiresIn[1]) : 15;
  const unit = expiresIn?.[2]?.toLowerCase() ?? "minutes";
  const expiresMs = amount * (unit.startsWith("second") ? 1_000 : 60_000);
  return {
    userCode,
    verificationUri,
    expiresAt: new Date(Date.now() + Math.max(expiresMs, 60_000)),
    intervalSeconds: 5,
  };
}

async function waitForDeviceAuthChallenge(pending: PendingCodexDeviceAuth) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < env.CODEX_DEVICE_AUTH_START_TIMEOUT_MS) {
    const parsed = parseCodexDeviceAuthChallenge(`${pending.output}\n${pending.errorOutput}`);
    if (parsed) return parsed;
    if (pending.exitCode != null || pending.exitSignal != null) {
      throw new Error(sanitizedCodexFailure(pending));
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error("Codex device login did not print a verification code in time.");
}

async function prepareCodexHome(userId: string, connectionProfileId: string) {
  const root = env.CODEX_CONNECTIONS_ROOT.trim();
  const userHash = createHash("sha256").update(userId).digest("hex").slice(0, 32);
  const codexHome = join(root, userHash, connectionProfileId);
  await mkdir(codexHome, { recursive: true, mode: 0o700 });
  await chmod(codexHome, 0o700);
  await writeFile(
    join(codexHome, "config.toml"),
    [
      'cli_auth_credentials_store = "file"',
      'forced_login_method = "chatgpt"',
      ...optionalConfigLine("model", env.OPENAI_CODEX_SUBSCRIPTION_MODEL),
      'sandbox_mode = "read-only"',
      'approval_policy = "never"',
      "",
    ].join("\n"),
    { mode: 0o600 },
  );
  return codexHome;
}

function codexEnv(codexHome: string) {
  return {
    ...process.env,
    CODEX_HOME: codexHome,
    RUST_LOG: process.env.RUST_LOG ?? "error",
  };
}

async function codexLoginStatus(codexHome: string) {
  const child = spawn(env.CODEX_CLI_BINARY, ["login", "status"], {
    env: codexEnv(codexHome),
    stdio: ["ignore", "ignore", "ignore"],
  });
  const code = await new Promise<number | null>((resolve) => child.on("close", resolve));
  return code === 0;
}

async function loadCodexModelsFromCli(codexHome: string) {
  let primaryError: unknown = null;
  try {
    const primary = await runCodexDebugModels(codexHome, []);
    const parsed = parseCodexModelCatalog(primary.stdout);
    if (parsed.length > 0) return parsed;
  } catch (error) {
    primaryError = error;
  }

  const bundled = await runCodexDebugModels(codexHome, ["--bundled"]);
  const bundledParsed = parseCodexModelCatalog(bundled.stdout);
  if (bundledParsed.length > 0) return bundledParsed;
  if (primaryError instanceof Error) throw primaryError;
  throw new Error("Codex CLI returned an empty model catalog");
}

async function runCodexDebugModels(codexHome: string, extraArgs: string[]) {
  const child = spawn(env.CODEX_CLI_BINARY, ["debug", "models", ...extraArgs], {
    env: codexEnv(codexHome),
    stdio: ["ignore", "pipe", "pipe"],
  });
  const stdout: Buffer[] = [];
  const stderr: Buffer[] = [];
  let stdoutBytes = 0;
  child.stdout.on("data", (chunk: Buffer) => {
    stdoutBytes += chunk.byteLength;
    if (stdoutBytes <= CODEX_MODEL_CATALOG_OUTPUT_LIMIT) stdout.push(chunk);
    if (stdoutBytes > CODEX_MODEL_CATALOG_OUTPUT_LIMIT) child.kill("SIGTERM");
  });
  child.stderr.on("data", (chunk: Buffer) => {
    stderr.push(chunk);
  });
  child.on("error", (error) => {
    stderr.push(Buffer.from(error.message, "utf8"));
  });
  const code = await waitForProcessClose(child, CODEX_MODEL_CATALOG_TIMEOUT_MS);
  const stderrText = stripAnsi(Buffer.concat(stderr).toString("utf8")).replace(/\s+/g, " ").trim();
  if (stdoutBytes > CODEX_MODEL_CATALOG_OUTPUT_LIMIT) {
    throw new Error("Codex CLI model catalog output was too large");
  }
  if (code !== 0) {
    throw new Error(stderrText || `Codex CLI model catalog failed with exit code ${code}`);
  }
  return { stdout: Buffer.concat(stdout).toString("utf8") };
}

function waitForProcessClose(child: ChildProcess, timeoutMs: number): Promise<number | null> {
  return new Promise((resolve) => {
    let settled = false;
    let forceKillTimer: ReturnType<typeof setTimeout> | null = null;
    let fallbackTimer: ReturnType<typeof setTimeout> | null = null;
    const finish = (code: number | null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeoutTimer);
      if (forceKillTimer) clearTimeout(forceKillTimer);
      if (fallbackTimer) clearTimeout(fallbackTimer);
      resolve(code);
    };
    const timeoutTimer = setTimeout(() => {
      child.kill("SIGTERM");
      forceKillTimer = setTimeout(() => {
        child.kill("SIGKILL");
        fallbackTimer = setTimeout(() => finish(null), 1_000);
        fallbackTimer.unref?.();
      }, CODEX_DEVICE_AUTH_KILL_GRACE_MS);
      forceKillTimer.unref?.();
    }, timeoutMs);
    timeoutTimer.unref?.();
    child.once("close", (code) => finish(code));
  });
}

export function parseCodexModelCatalog(output: string): CodexCliModel[] {
  let json: unknown;
  try {
    json = JSON.parse(output);
  } catch {
    return [];
  }
  const models = Array.isArray((json as { models?: unknown }).models)
    ? (json as { models: unknown[] }).models
    : [];
  const parsed = models
    .map((item): (CodexCliModel & { priority: number }) | null => {
      if (!item || typeof item !== "object") return null;
      const source = item as Record<string, unknown>;
      const id = text(source.slug);
      if (!id || text(source.visibility) === "hide") return null;
      return {
        id,
        label: text(source.display_name) ?? id,
        description: text(source.description),
        priority: numberOrMax(source.priority),
      };
    })
    .filter((item): item is CodexCliModel & { priority: number } => item !== null)
    .sort((left, right) => left.priority - right.priority || left.label.localeCompare(right.label));
  const seen = new Set<string>();
  return parsed
    .filter((model) => {
      if (seen.has(model.id)) return false;
      seen.add(model.id);
      return true;
    })
    .map(({ priority: _priority, ...model }) => model);
}

function sanitizedCodexFailure(pending: PendingCodexDeviceAuth) {
  const output = stripAnsi(`${pending.errorOutput}\n${pending.output}`)
    .replace(/\b[A-Z0-9]{4,}-[A-Z0-9-]{4,}\b/g, "[device-code]")
    .replace(/\s+/g, " ")
    .trim();
  if (output) return `Codex login failed: ${output.slice(0, 300)}`;
  return pending.exitSignal
    ? `Codex login was interrupted with ${pending.exitSignal}`
    : `Codex login failed with exit code ${pending.exitCode ?? "unknown"}`;
}

function stripAnsi(value: string) {
  return value.replace(ANSI_ESCAPE_PATTERN, "");
}

function selectCodexDefaultModel(models: CodexCliModel[]) {
  const configured = env.OPENAI_CODEX_SUBSCRIPTION_MODEL.trim();
  if (configured) {
    return (
      models.find((model) => model.id === configured) ?? {
        id: configured,
        label: configured,
        description: null,
      }
    );
  }
  const selected = models[0];
  if (!selected) throw new Error("Codex CLI returned no selectable models");
  return selected;
}

function optionalConfigLine(key: string, value: string | null | undefined) {
  if (!hasText(value)) return [];
  return [`${key} = ${JSON.stringify(value.trim())}`];
}

function appendBounded(current: string, next: string) {
  const value = current + next;
  if (value.length <= CODEX_DEVICE_AUTH_OUTPUT_LIMIT) return value;
  return value.slice(value.length - CODEX_DEVICE_AUTH_OUTPUT_LIMIT);
}

function clearPendingTimer(pending: PendingCodexDeviceAuth) {
  if (pending.cleanupTimer) {
    clearTimeout(pending.cleanupTimer);
    pending.cleanupTimer = null;
  }
}

function randomToken(bytes: number) {
  return randomBytes(bytes).toString("base64url");
}

function text(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function numberOrMax(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : Number.MAX_SAFE_INTEGER;
}
