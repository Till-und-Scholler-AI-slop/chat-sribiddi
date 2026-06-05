import { lookup } from "node:dns/promises";
import { type ToolSet, tool } from "ai";
import { z } from "zod";
import { env } from "../config/env.js";
import { badRequest, notFound } from "../errors/http-error.js";
import type { ChatRepository } from "../repositories/chat-repository.js";
import type { ChatToolRepository } from "../repositories/tool-repository.js";

export type ToolDefinition = {
  name: string;
  displayName: string;
  description: string;
  riskLevel: "LOW" | "HIGH";
  enabledByDefault: boolean;
};

export type ExecutedToolRecord = {
  toolName: string;
  inputPayload: string;
  outputPayload: string | null;
  startedAt: Date;
  durationMs: number;
  contextExcerpt: string | null;
  status: string;
  errorMessage: string | null;
  displayType: string | null;
};

const TOOL_DEFINITIONS: ToolDefinition[] = [
  {
    name: "calculator",
    displayName: "Calculator",
    description: "Evaluate arithmetic expressions.",
    riskLevel: "LOW",
    enabledByDefault: true,
  },
  {
    name: "current-date-time",
    displayName: "Current date and time",
    description: "Provide the current date and time.",
    riskLevel: "LOW",
    enabledByDefault: true,
  },
  {
    name: "url-fetcher",
    displayName: "URL fetcher",
    description: "Fetch public web pages.",
    riskLevel: "HIGH",
    enabledByDefault: false,
  },
  {
    name: "brave-search",
    displayName: "Brave Search",
    description: "Search the web using the user's Brave Search key.",
    riskLevel: "HIGH",
    enabledByDefault: false,
  },
];

export class ToolService {
  constructor(
    private readonly chats: ChatRepository,
    private readonly chatTools: ChatToolRepository,
  ) {}

  async listAvailableTools(chatId: string, userId: string) {
    await this.requireChat(chatId, userId);
    const overrides = new Map(
      (await this.chatTools.list(chatId)).map((row) => [row.tool_name, row.enabled]),
    );
    return TOOL_DEFINITIONS.map((toolDefinition) => ({
      name: toolDefinition.name,
      displayName: toolDefinition.displayName,
      description: toolDefinition.description,
      riskLevel: toolDefinition.riskLevel,
      enabled: overrides.get(toolDefinition.name) ?? toolDefinition.enabledByDefault,
    }));
  }

  async setToolEnabled(chatId: string, userId: string, toolName: string, enabled: boolean) {
    await this.requireChat(chatId, userId);
    const definition = TOOL_DEFINITIONS.find((toolDefinition) => toolDefinition.name === toolName);
    if (!definition) throw badRequest(`Unknown tool: ${toolName}`);
    const row = await this.chatTools.upsert(chatId, toolName, enabled);
    return {
      chatId: row.chat_id,
      toolName: row.tool_name,
      enabled: row.enabled,
    };
  }

  async aiTools(chatId: string, userId: string) {
    const available = await this.listAvailableTools(chatId, userId);
    const enabled = new Set(available.filter((item) => item.enabled).map((item) => item.name));
    const records: ExecutedToolRecord[] = [];
    const tools: ToolSet = {};

    if (enabled.has("calculator")) {
      tools.calculator = tool({
        description:
          "Evaluate an arithmetic expression. Supports +, -, *, /, ^, %, parentheses, and decimal numbers.",
        inputSchema: z.object({
          expression: z
            .string()
            .describe("Arithmetic expression to evaluate, for example '2 + 3 * (4 - 1)'"),
        }),
        execute: async ({ expression }) =>
          executeRecorded(records, "calculator", { expression }, () =>
            calculateExpression(expression),
          ),
      });
    }

    if (enabled.has("current-date-time")) {
      tools["current-date-time"] = tool({
        description:
          "Get the current date and time in the server time zone. Use this for current date, time, or day questions.",
        inputSchema: z.object({}),
        execute: async () =>
          executeRecorded(records, "current-date-time", {}, () =>
            new Intl.DateTimeFormat("en-US", {
              dateStyle: "full",
              timeStyle: "long",
              timeZoneName: "short",
            }).format(new Date()),
          ),
      });
    }

    if (enabled.has("url-fetcher")) {
      tools["url-fetcher"] = tool({
        description:
          "Fetch the text content of a public HTTP web page. Private, localhost, and HTTPS URLs are blocked.",
        inputSchema: z.object({
          url: z.string().describe("Public HTTP URL to fetch"),
        }),
        execute: async ({ url }) =>
          executeRecorded(records, "url-fetcher", { url }, () => fetchPublicUrl(url)),
      });
    }

    return {
      tools: Object.keys(tools).length > 0 ? tools : undefined,
      records,
    };
  }

  private async requireChat(chatId: string, userId: string) {
    const chat = await this.chats.findByIdAndUser(chatId, userId);
    if (!chat) throw notFound(`Chat ${chatId} not found`);
    return chat;
  }
}

async function executeRecorded(
  records: ExecutedToolRecord[],
  toolName: string,
  input: Record<string, unknown>,
  execute: () => Promise<string> | string,
) {
  const startedAt = new Date();
  const started = Date.now();
  try {
    const output = await execute();
    records.push({
      toolName,
      inputPayload: JSON.stringify(input),
      outputPayload: output,
      startedAt,
      durationMs: Date.now() - started,
      contextExcerpt: output.slice(0, 1000),
      status: "SUCCESS",
      errorMessage: null,
      displayType: toolName === "url-fetcher" ? "document" : "text",
    });
    return output;
  } catch (error) {
    const message = error instanceof Error ? error.message : "Tool failed";
    records.push({
      toolName,
      inputPayload: JSON.stringify(input),
      outputPayload: null,
      startedAt,
      durationMs: Date.now() - started,
      contextExcerpt: null,
      status: "ERROR",
      errorMessage: message,
      displayType: "text",
    });
    return message;
  }
}

function calculateExpression(expression: string) {
  if (!/^[\d+\-*/^%().\s]+$/.test(expression)) {
    throw new Error(
      "Invalid expression. Only digits, operators, parentheses, spaces, and decimal points are allowed.",
    );
  }
  const sanitized = expression.replace(/\s+/g, "");
  if (!sanitized) throw new Error("Expression is empty.");
  const parser = new ExpressionParser(sanitized);
  const result = parser.parse();
  if (!Number.isFinite(result)) throw new Error("Result is not a finite number.");
  if (Number.isInteger(result) && Math.abs(result) < 1e15) return result.toFixed(0);
  return String(result);
}

class ExpressionParser {
  private position = 0;

  constructor(private readonly input: string) {}

  parse() {
    const result = this.parseAddSub();
    if (this.position < this.input.length) {
      throw new Error(
        `Unexpected character at position ${this.position}: ${this.input[this.position]}`,
      );
    }
    return result;
  }

  private parseAddSub(): number {
    let result = this.parseMulDiv();
    while (this.position < this.input.length) {
      const char = this.input[this.position];
      if (char === "+") {
        this.position += 1;
        result += this.parseMulDiv();
      } else if (char === "-") {
        this.position += 1;
        result -= this.parseMulDiv();
      } else {
        break;
      }
    }
    return result;
  }

  private parseMulDiv(): number {
    let result = this.parsePower();
    while (this.position < this.input.length) {
      const char = this.input[this.position];
      if (char === "*") {
        this.position += 1;
        result *= this.parsePower();
      } else if (char === "/") {
        this.position += 1;
        const divisor = this.parsePower();
        if (divisor === 0) throw new Error("Division by zero");
        result /= divisor;
      } else if (char === "%") {
        this.position += 1;
        const divisor = this.parsePower();
        if (divisor === 0) throw new Error("Division by zero");
        result %= divisor;
      } else {
        break;
      }
    }
    return result;
  }

  private parsePower(): number {
    let result = this.parseUnary();
    if (this.input[this.position] === "^") {
      this.position += 1;
      result **= this.parsePower();
    }
    return result;
  }

  private parseUnary(): number {
    const char = this.input[this.position];
    if (char === "+") {
      this.position += 1;
      return this.parseUnary();
    }
    if (char === "-") {
      this.position += 1;
      return -this.parseUnary();
    }
    return this.parsePrimary();
  }

  private parsePrimary(): number {
    if (this.input[this.position] === "(") {
      this.position += 1;
      const result = this.parseAddSub();
      if (this.input[this.position] !== ")") throw new Error("Missing closing parenthesis");
      this.position += 1;
      return result;
    }
    return this.parseNumber();
  }

  private parseNumber() {
    const start = this.position;
    while (this.position < this.input.length && /[\d.]/.test(this.input[this.position] ?? "")) {
      this.position += 1;
    }
    if (start === this.position) throw new Error(`Expected number at position ${this.position}`);
    const value = Number(this.input.slice(start, this.position));
    if (!Number.isFinite(value)) throw new Error("Invalid number");
    return value;
  }
}

async function fetchPublicUrl(rawUrl: string) {
  const url = normalizeToolUrl(rawUrl);
  await assertPublicHttpHost(url);
  const response = await fetch(url, {
    headers: {
      Accept: "text/html,text/plain,application/json",
      "User-Agent": env.TOOLS_USER_AGENT,
    },
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} fetching URL`);
  const text = htmlToText(await response.text());
  return text.length > 50_000 ? `${text.slice(0, 50_000)}\n\n[Content truncated at 50KB]` : text;
}

function normalizeToolUrl(rawUrl: string) {
  const withScheme =
    rawUrl.startsWith("http://") || rawUrl.startsWith("https://") ? rawUrl : `http://${rawUrl}`;
  const url = new URL(withScheme);
  if (url.protocol !== "http:") {
    throw new Error("Only public HTTP URLs are supported by this tool.");
  }
  url.username = "";
  url.password = "";
  url.hash = "";
  return url;
}

async function assertPublicHttpHost(url: URL) {
  const host = url.hostname.toLowerCase();
  if (!host || host === "localhost" || host.endsWith(".localhost")) {
    throw new Error("Localhost URLs are not allowed.");
  }
  const addresses = await lookup(host, { all: true });
  if (addresses.length === 0) throw new Error("Could not resolve host.");
  for (const address of addresses) {
    if (isPrivateAddress(address.address)) {
      throw new Error("URL resolves to a private or reserved IP address.");
    }
  }
}

function isPrivateAddress(address: string) {
  if (address.includes(":")) {
    const normalized = address.toLowerCase();
    return (
      normalized === "::1" ||
      normalized.startsWith("fc") ||
      normalized.startsWith("fd") ||
      normalized.startsWith("fe80:")
    );
  }
  const parts = address.split(".").map(Number);
  const [a, b] = parts;
  if (parts.length !== 4 || a == null || b == null) return true;
  return (
    a === 10 ||
    a === 127 ||
    a === 0 ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 169 && b === 254)
  );
}

function htmlToText(html: string) {
  return html
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\s+/g, " ")
    .trim();
}
