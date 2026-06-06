import { type ChildProcess, spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { chmod, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { env } from "../config/env.js";
import { badRequest } from "../errors/http-error.js";
import { hasText } from "../utils/strings.js";

const ANSI_ESCAPE_PATTERN = new RegExp(`${String.fromCharCode(27)}\\[[0-?]*[ -/]*[@-~]`, "g");

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
};

export class CodexCliService {
  private readonly pending = new Map<string, PendingCodexDeviceAuth>();

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
    };
    this.pending.set(state, pending);
    child.stdout.on("data", (chunk: Buffer) => {
      pending.output += chunk.toString("utf8");
    });
    child.stderr.on("data", (chunk: Buffer) => {
      pending.errorOutput += chunk.toString("utf8");
    });
    child.on("error", (error) => {
      pending.errorOutput += `\n${error.message}`;
      pending.exitCode = -1;
    });
    child.on("close", (code, signal) => {
      pending.exitCode = code;
      pending.exitSignal = signal;
    });

    try {
      const challenge = await waitForDeviceAuthChallenge(pending);
      pending.expiresAt = challenge.expiresAt.getTime();
      pending.intervalSeconds = challenge.intervalSeconds;
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
      this.pending.delete(state);
      child.kill("SIGTERM");
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
      this.pending.delete(state);
      pending.child.kill("SIGTERM");
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
    this.pending.delete(state);
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
    return {
      mode: "READY",
      codexHome: pending.codexHome,
      modelId: env.OPENAI_CODEX_SUBSCRIPTION_MODEL.trim() || "gpt-5-codex",
      accountLabel: "ChatGPT/Codex subscription",
      message: null,
    };
  }

  async deleteCodexHome(codexHome: string | null | undefined) {
    if (!hasText(codexHome)) return;
    await rm(codexHome.trim(), { recursive: true, force: true });
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
      `model = ${JSON.stringify(env.OPENAI_CODEX_SUBSCRIPTION_MODEL.trim() || "gpt-5-codex")}`,
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

function randomToken(bytes: number) {
  return randomBytes(bytes).toString("base64url");
}
