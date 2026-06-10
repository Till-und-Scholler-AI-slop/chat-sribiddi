import { type ChildProcess, spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { ModelMessage } from "ai";
import { env } from "../config/env.js";
import { HttpError, upstreamError } from "../errors/http-error.js";
import { hasText } from "../utils/strings.js";
import type { ChatExecutionRequest } from "./ai-chat-service.js";

const ANSI_ESCAPE_PATTERN = new RegExp(`${String.fromCharCode(27)}\\[[0-?]*[ -/]*[@-~]`, "g");
const CODEX_EXEC_KILL_GRACE_MS = 5_000;
const CODEX_EXEC_CLOSE_FALLBACK_MS = 1_000;
const CODEX_EXEC_OUTPUT_LIMIT = 20 * 1024 * 1024;

type CodexExecResult = {
  text: string;
  usage: {
    inputTokens: number | null;
    outputTokens: number | null;
    totalTokens: number | null;
  };
};

export function streamCodexExec(input: ChatExecutionRequest) {
  const result = runCodexExec(input);
  return {
    textStream: (async function* () {
      const completed = await result;
      if (completed.text) yield completed.text;
    })(),
    usage: result.then((value) => value.usage),
  };
}

export async function generateCodexExec(input: ChatExecutionRequest) {
  const result = await runCodexExec(input);
  return {
    text: result.text,
    usage: result.usage,
  };
}

async function runCodexExec(input: ChatExecutionRequest): Promise<CodexExecResult> {
  const codexHome = input.credential.connection?.codexHome?.trim();
  if (!codexHome) {
    throw upstreamError("OpenAI Codex subscription connection is missing its CODEX_HOME");
  }
  const runDir = await mkdtemp(join(tmpdir(), "chat-sribiddi-codex-run-"));
  try {
    const prompt = codexPrompt(input.messages);
    const args = [
      "exec",
      "--json",
      "--ephemeral",
      "--ignore-user-config",
      "--skip-git-repo-check",
      "--sandbox",
      "read-only",
      "-c",
      'approval_policy="never"',
      "-m",
      input.modelId,
      "-C",
      runDir,
      "-",
    ];
    const child = spawn(env.CODEX_CLI_BINARY, args, {
      cwd: runDir,
      env: {
        ...process.env,
        CODEX_HOME: codexHome,
        RUST_LOG: process.env.RUST_LOG ?? "error",
      },
      stdio: ["pipe", "pipe", "pipe"],
    });
    child.stdin.on("error", () => {
      // The CLI may close stdin early; ignore backpressure errors so we
      // still surface the assistant output.
    });
    child.stdin.end(prompt);
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    let stdoutBytes = 0;
    let outputTruncated = false;
    child.stdout.on("data", (chunk: Buffer) => {
      stdoutBytes += chunk.byteLength;
      if (stdoutBytes > CODEX_EXEC_OUTPUT_LIMIT) {
        if (!outputTruncated) {
          outputTruncated = true;
          child.kill("SIGTERM");
        }
        return;
      }
      stdout.push(chunk);
    });
    child.stderr.on("data", (chunk: Buffer) => stderr.push(chunk));
    child.on("error", (error) => stderr.push(Buffer.from(error.message, "utf8")));
    const { code, timedOut } = await waitForCodexClose(child, env.CODEX_EXEC_TIMEOUT_MS);
    const stdoutText = Buffer.concat(stdout).toString("utf8");
    const stderrText = Buffer.concat(stderr).toString("utf8");
    if (outputTruncated) {
      throw upstreamError(
        `OpenAI Codex subscription response exceeded the ${CODEX_EXEC_OUTPUT_LIMIT} byte limit`,
      );
    }
    if (timedOut) {
      throw upstreamError("OpenAI Codex subscription request timed out");
    }
    const parsed = parseCodexExecJsonl(stdoutText);
    if (parsed.error) {
      if (isCodexAuthError(parsed.error)) {
        throw new HttpError(403, "API_KEY_REQUIRED", "OpenAI Codex subscription needs reconnect");
      }
      throw upstreamError(parsed.error);
    }
    if (code !== 0) {
      const message = sanitizeCodexExecError(
        stderrText || stdoutText || `Codex exited with ${code}`,
      );
      if (isCodexAuthError(message)) {
        throw new HttpError(403, "API_KEY_REQUIRED", "OpenAI Codex subscription needs reconnect");
      }
      throw upstreamError(message);
    }
    if (!hasText(parsed.text)) {
      throw upstreamError("OpenAI Codex subscription returned no assistant message");
    }
    return {
      text: parsed.text,
      usage: parsed.usage ?? { inputTokens: null, outputTokens: null, totalTokens: null },
    };
  } finally {
    await rm(runDir, { recursive: true, force: true });
  }
}

function waitForCodexClose(
  child: ChildProcess,
  timeoutMs: number,
): Promise<{ code: number | null; timedOut: boolean }> {
  return new Promise((resolve) => {
    let settled = false;
    let timedOut = false;
    let timeoutTimer: ReturnType<typeof setTimeout> | null = null;
    let killTimer: ReturnType<typeof setTimeout> | null = null;
    let fallbackTimer: ReturnType<typeof setTimeout> | null = null;

    const finish = (code: number | null) => {
      if (settled) return;
      settled = true;
      if (timeoutTimer) clearTimeout(timeoutTimer);
      if (killTimer) clearTimeout(killTimer);
      if (fallbackTimer) clearTimeout(fallbackTimer);
      resolve({ code, timedOut });
    };

    child.once("close", (code) => finish(code));
    timeoutTimer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGTERM");
      killTimer = setTimeout(() => {
        child.kill("SIGKILL");
        fallbackTimer = setTimeout(() => finish(null), CODEX_EXEC_CLOSE_FALLBACK_MS);
        fallbackTimer.unref?.();
      }, CODEX_EXEC_KILL_GRACE_MS);
      killTimer.unref?.();
    }, timeoutMs);
    timeoutTimer.unref?.();
  });
}

function isCodexAuthError(message: string) {
  return /not logged in|sign in|login|authentication|unauthorized/i.test(message);
}

export function parseCodexExecJsonl(output: string) {
  let text = "";
  let error: string | null = null;
  let usage: CodexExecResult["usage"] | null = null;
  for (const line of output.split(/\r?\n/)) {
    if (!line.trim()) continue;
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(line) as Record<string, unknown>;
    } catch {
      continue;
    }
    if (event.type === "error") {
      error = textValue(event.message) ?? textValue(event.error) ?? "Codex request failed";
      continue;
    }
    if (event.type === "turn.failed") {
      error = textValue(event.message) ?? "Codex turn failed";
      continue;
    }
    if (event.type === "turn.completed") {
      usage = usageFrom(event.usage);
      continue;
    }
    if (event.type === "item.completed") {
      const item = event.item as Record<string, unknown> | undefined;
      if (item?.type === "agent_message") {
        text = textValue(item.text) ?? text;
      }
    }
  }
  return { text, error: error ? sanitizeCodexExecError(error) : null, usage };
}

function codexPrompt(messages: ModelMessage[]) {
  return messages
    .map((message) => {
      const text = messageText(message);
      if (!text.trim()) return null;
      return `${message.role.toUpperCase()}:\n${text.trim()}`;
    })
    .filter((item): item is string => Boolean(item))
    .join("\n\n");
}

function messageText(message: ModelMessage) {
  const content = message.content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content
    .map((part) => {
      if ("text" in part && typeof part.text === "string") return part.text;
      if ("type" in part && typeof part.type === "string")
        return `[${part.type} attachment omitted]`;
      return "";
    })
    .filter(Boolean)
    .join("\n");
}

function usageFrom(value: unknown): CodexExecResult["usage"] | null {
  if (!value || typeof value !== "object") return null;
  const source = value as Record<string, unknown>;
  return {
    inputTokens: numberOrNull(source.input_tokens ?? source.inputTokens),
    outputTokens: numberOrNull(source.output_tokens ?? source.outputTokens),
    totalTokens: numberOrNull(source.total_tokens ?? source.totalTokens),
  };
}

function numberOrNull(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function textValue(value: unknown) {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function sanitizeCodexExecError(message: string) {
  return message
    .replace(ANSI_ESCAPE_PATTERN, "")
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer [redacted]")
    .replace(/\b[A-Z0-9]{4,}-[A-Z0-9-]{4,}\b/g, "[device-code]")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 500);
}
