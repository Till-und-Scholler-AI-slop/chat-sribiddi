import { randomUUID } from "node:crypto";
import { lookup } from "node:dns/promises";
import { type ToolSet, tool } from "ai";
import { z } from "zod";
import { env } from "../config/env.js";
import { badRequest, notFound } from "../errors/http-error.js";
import type { ChatRepository } from "../repositories/chat-repository.js";
import type { ChatToolRepository } from "../repositories/tool-repository.js";
import type { ApiKeyService } from "./api-key-service.js";
import type { WebSearchService } from "./web-search-service.js";

export type ToolDefinition = {
  name: string;
  displayName: string;
  description: string;
  riskLevel: "LOW" | "HIGH";
  enabledByDefault: boolean;
};

export type ExecutedToolRecord = {
  clientId: string;
  toolName: string;
  inputPayload: string;
  outputPayload: string | null;
  startedAt: Date;
  durationMs: number;
  contextExcerpt: string | null;
  status: "SUCCESS" | "ERROR";
  errorMessage: string | null;
  displayType: string | null;
};

export type ToolExecutionEvent = {
  clientId: string;
  toolName: string;
  inputPayload: string;
  outputPayload: string | null;
  startedAt: Date;
  durationMs: number | null;
  contextExcerpt: string | null;
  status: "RUNNING" | "SUCCESS" | "ERROR";
  errorMessage: string | null;
  displayType: string | null;
};

export type ToolEventSink = (event: ToolExecutionEvent) => void | Promise<void>;

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
  {
    name: "chart-generator",
    displayName: "Chart generator",
    description: "Generate simple SVG charts from tabular data.",
    riskLevel: "LOW",
    enabledByDefault: false,
  },
  {
    name: "spreadsheet-analyzer",
    displayName: "Spreadsheet analyzer",
    description: "Analyze pasted CSV or TSV data and summarize columns.",
    riskLevel: "LOW",
    enabledByDefault: false,
  },
  {
    name: "agent-plan",
    displayName: "Agent plan",
    description: "Create a structured plan for multi-step work.",
    riskLevel: "LOW",
    enabledByDefault: false,
  },
];

export class ToolService {
  constructor(
    private readonly chats: ChatRepository,
    private readonly chatTools: ChatToolRepository,
    private readonly apiKeys?: ApiKeyService | undefined,
    private readonly webSearch?: WebSearchService | undefined,
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

  async aiTools(chatId: string, userId: string, eventSink?: ToolEventSink) {
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
          executeRecorded(
            records,
            "calculator",
            { expression },
            () => calculateExpression(expression),
            eventSink,
          ),
      });
    }

    if (enabled.has("current-date-time")) {
      tools["current-date-time"] = tool({
        description:
          "Get the current date and time in the server time zone. Use this for current date, time, or day questions.",
        inputSchema: z.object({}),
        execute: async () =>
          executeRecorded(
            records,
            "current-date-time",
            {},
            () =>
              new Intl.DateTimeFormat("en-US", {
                dateStyle: "full",
                timeStyle: "long",
                timeZoneName: "short",
              }).format(new Date()),
            eventSink,
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
          executeRecorded(records, "url-fetcher", { url }, () => fetchPublicUrl(url), eventSink),
      });
    }

    if (enabled.has("brave-search")) {
      tools["brave-search"] = tool({
        description:
          "Search the web using the user's configured Brave Search API key. Use when current or source-backed web information is needed.",
        inputSchema: z.object({
          query: z.string().trim().min(1).max(500).describe("Search query"),
        }),
        execute: async ({ query }) =>
          executeRecorded(
            records,
            "brave-search",
            { query },
            async () => {
              if (!this.apiKeys || !this.webSearch) {
                throw new Error("Brave Search tool is not configured on this server.");
              }
              const apiKey = await this.apiKeys.getKey(userId, "BRAVE_SEARCH");
              if (!apiKey?.trim()) {
                throw new Error(
                  "Connect a Brave Search API key in settings before using this tool.",
                );
              }
              const results = await this.webSearch.search(query, apiKey);
              return JSON.stringify({ query, results }, null, 2);
            },
            eventSink,
          ),
      });
    }

    if (enabled.has("chart-generator")) {
      tools["chart-generator"] = tool({
        description:
          "Generate a simple SVG bar or line chart from numeric data. Return the generated chart to the user.",
        inputSchema: z.object({
          title: z.string().trim().min(1).max(120).describe("Chart title"),
          chartType: z.enum(["bar", "line"]).default("bar").describe("Chart style"),
          xLabel: z.string().trim().max(80).optional().describe("X axis label"),
          yLabel: z.string().trim().max(80).optional().describe("Y axis label"),
          data: z
            .array(
              z.object({
                label: z.string().trim().min(1).max(80),
                value: z.number(),
              }),
            )
            .min(1)
            .max(24)
            .describe("Numeric data points"),
        }),
        execute: async (input) =>
          executeRecorded(
            records,
            "chart-generator",
            input,
            () => JSON.stringify(generateChart(input), null, 2),
            eventSink,
          ),
      });
    }

    if (enabled.has("spreadsheet-analyzer")) {
      tools["spreadsheet-analyzer"] = tool({
        description:
          "Analyze pasted CSV or TSV data. Use for small tables copied from spreadsheets; do not use for binary files.",
        inputSchema: z.object({
          data: z.string().min(1).max(100_000).describe("CSV, TSV, or delimited text"),
          delimiter: z.enum(["auto", "comma", "semicolon", "tab", "pipe"]).default("auto"),
          hasHeader: z.boolean().default(true),
        }),
        execute: async (input) =>
          executeRecorded(
            records,
            "spreadsheet-analyzer",
            { delimiter: input.delimiter, hasHeader: input.hasHeader },
            () => JSON.stringify(analyzeDelimitedData(input), null, 2),
            eventSink,
          ),
      });
    }

    if (enabled.has("agent-plan")) {
      tools["agent-plan"] = tool({
        description:
          "Create a concise structured plan for multi-step work. Use when a task benefits from visible step tracking.",
        inputSchema: z.object({
          objective: z.string().trim().min(1).max(500),
          steps: z
            .array(
              z.object({
                title: z.string().trim().min(1).max(120),
                notes: z.string().trim().max(500).optional(),
              }),
            )
            .min(1)
            .max(12),
        }),
        execute: async (input) =>
          executeRecorded(
            records,
            "agent-plan",
            input,
            () => JSON.stringify(formatAgentPlan(input), null, 2),
            eventSink,
          ),
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
  eventSink?: ToolEventSink,
) {
  const clientId = `tool-${randomUUID()}`;
  const startedAt = new Date();
  const started = Date.now();
  const inputPayload = JSON.stringify(input);
  const displayType = displayTypeForTool(toolName);
  await emitToolEvent(eventSink, {
    clientId,
    toolName,
    inputPayload,
    outputPayload: null,
    startedAt,
    durationMs: null,
    contextExcerpt: null,
    status: "RUNNING",
    errorMessage: null,
    displayType,
  });
  try {
    const output = await execute();
    const record = {
      clientId,
      toolName,
      inputPayload,
      outputPayload: output,
      startedAt,
      durationMs: Date.now() - started,
      contextExcerpt: output.slice(0, 1000),
      status: "SUCCESS",
      errorMessage: null,
      displayType,
    } satisfies ExecutedToolRecord;
    records.push(record);
    await emitToolEvent(eventSink, record);
    return output;
  } catch (error) {
    const message = error instanceof Error ? error.message : "Tool failed";
    const record = {
      clientId,
      toolName,
      inputPayload,
      outputPayload: null,
      startedAt,
      durationMs: Date.now() - started,
      contextExcerpt: null,
      status: "ERROR",
      errorMessage: message,
      displayType,
    } satisfies ExecutedToolRecord;
    records.push(record);
    await emitToolEvent(eventSink, record);
    return message;
  }
}

async function emitToolEvent(eventSink: ToolEventSink | undefined, event: ToolExecutionEvent) {
  if (!eventSink) return;
  try {
    await eventSink(event);
  } catch {
    // Streaming tool events are best-effort; the model invocation should not fail
    // just because the client disconnected while a tool was still running.
  }
}

function displayTypeForTool(toolName: string) {
  if (toolName === "url-fetcher") return "document";
  if (toolName === "brave-search") return "search-results";
  if (toolName === "chart-generator") return "chart";
  if (toolName === "spreadsheet-analyzer") return "table";
  if (toolName === "agent-plan") return "plan";
  return "text";
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

const URL_FETCH_TIMEOUT_MS = 10_000;
const URL_FETCH_MAX_BODY_BYTES = 1_000_000;

async function fetchPublicUrl(rawUrl: string) {
  const url = normalizeToolUrl(rawUrl);
  const safeAddress = await resolvePublicAddress(url);
  if (safeAddress.family === 6) {
    url.hostname = `[${safeAddress.address}]`;
  } else {
    url.hostname = safeAddress.address;
  }
  const response = await fetch(url, {
    headers: {
      Accept: "text/html,text/plain,application/json",
      "User-Agent": env.TOOLS_USER_AGENT,
    },
    signal: AbortSignal.timeout(URL_FETCH_TIMEOUT_MS),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} fetching URL`);
  const body = await readBoundedText(response, URL_FETCH_MAX_BODY_BYTES);
  const text = htmlToText(body);
  return text.length > 50_000 ? `${text.slice(0, 50_000)}\n\n[Content truncated at 50KB]` : text;
}

function normalizeToolUrl(rawUrl: string) {
  const withScheme =
    rawUrl.startsWith("http://") || rawUrl.startsWith("https://") ? rawUrl : `http://${rawUrl}`;
  const url = new URL(withScheme);
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("Only public HTTP(S) URLs are supported by this tool.");
  }
  url.username = "";
  url.password = "";
  url.hash = "";
  return url;
}

async function resolvePublicAddress(url: URL) {
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (!host || host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
    throw new Error("Localhost URLs are not allowed.");
  }
  if (isLikelyIpAddress(host)) {
    if (isPrivateAddress(host)) {
      throw new Error("URL resolves to a private or reserved IP address.");
    }
    return { address: stripIpv6Brackets(host), family: host.includes(":") ? 6 : 4 };
  }
  const addresses = await lookup(host, { all: true });
  if (addresses.length === 0) throw new Error("Could not resolve host.");
  for (const address of addresses) {
    if (isPrivateAddress(address.address)) {
      throw new Error("URL resolves to a private or reserved IP address.");
    }
  }
  const preferred = addresses.find((item) => item.family === 4) ?? addresses[0];
  if (!preferred) throw new Error("Could not resolve host.");
  return { address: preferred.address, family: preferred.family };
}

function isLikelyIpAddress(host: string) {
  if (host.includes(":")) return true;
  return /^\d{1,3}(?:\.\d{1,3}){3}$/.test(host);
}

function stripIpv6Brackets(host: string) {
  return host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host;
}

function isPrivateAddress(address: string) {
  const normalized = stripIpv6Brackets(address).toLowerCase();
  if (normalized.includes(":")) return isPrivateIpv6(normalized);
  return isPrivateIpv4(normalized);
}

function isPrivateIpv4(address: string) {
  const parts = address.split(".").map(Number);
  if (parts.length !== 4) return true;
  const [a, b, c, d] = parts;
  if (
    a == null ||
    b == null ||
    c == null ||
    d == null ||
    a > 255 ||
    b > 255 ||
    c > 255 ||
    d > 255
  ) {
    return true;
  }
  if (a === 0) return true; // current network
  if (a === 10) return true; // 10.0.0.0/8
  if (a === 100 && b >= 64 && b <= 127) return true; // 100.64.0.0/10 CGNAT
  if (a === 127) return true; // 127.0.0.0/8 loopback
  if (a === 169 && b === 254) return true; // 169.254.0.0/16 link-local
  if (a === 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12 private
  if (a === 192 && b === 0 && c === 0) return true; // 192.0.0.0/24 IETF
  if (a === 192 && b === 0 && c === 2) return true; // 192.0.2.0/24 TEST-NET-1
  if (a === 192 && b === 168) return true; // 192.168.0.0/16 private
  if (a === 198 && (b === 18 || b === 19)) return true; // 198.18.0.0/15 benchmarking
  if (a === 198 && b === 51 && c === 100) return true; // 198.51.100.0/24 TEST-NET-2
  if (a === 203 && b === 0 && c === 113) return true; // 203.0.113.0/24 TEST-NET-3
  if (a >= 224 && a <= 239) return true; // 224.0.0.0/4 multicast
  if (a >= 240) return true; // 240.0.0.0/4 reserved + 255.255.255.255 broadcast
  return false;
}

function isPrivateIpv6(address: string) {
  // Normalize zone identifier if present (fe80::1%eth0).
  const withoutZone = address.split("%", 1)[0] ?? "";
  if (withoutZone === "::" || withoutZone === "::1") return true;
  // 64:ff9b:1::/48 — well-known NAT64 prefix, treat as suspicious public mapping
  if (withoutZone.startsWith("64:ff9b:")) return false;
  // Unique local addresses (fc00::/7)
  if (
    withoutZone.startsWith("fc") ||
    withoutZone.startsWith("fd") ||
    withoutZone.startsWith("fe") ||
    withoutZone.startsWith("ff")
  ) {
    // fe80::/10 link-local, fec0::/10 deprecated site-local, ff00::/8 multicast
    return true;
  }
  // 6to4 (2002::/16) — embedded IPv4 in the lower 32 bits
  if (withoutZone.startsWith("2002:")) {
    const last = withoutZone.split(":").slice(-2).join(".");
    if (isPrivateIpv4(last)) return true;
  }
  // IPv4-mapped (::ffff:a.b.c.d) — should match the mapped IPv4
  const mapped = withoutZone.match(/^::ffff:([0-9.]+)$/);
  if (mapped) return isPrivateIpv4(mapped[1] ?? "");
  return false;
}

async function readBoundedText(response: Response, maxBytes: number) {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: false });
  let received = 0;
  let truncated = false;
  let result = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      received += value.byteLength;
      if (received > maxBytes) {
        const remaining = Math.max(0, maxBytes - (received - value.byteLength));
        result += decoder.decode(value.subarray(0, remaining), { stream: true });
        truncated = true;
        break;
      }
      result += decoder.decode(value, { stream: true });
    }
    result += decoder.decode();
  } finally {
    if (truncated) {
      try {
        await reader.cancel();
      } catch {
        // best-effort
      }
    }
    reader.releaseLock();
  }
  if (truncated) result += "\n\n[Content truncated]";
  return result;
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

type ChartInput = {
  title: string;
  chartType: "bar" | "line";
  xLabel?: string | undefined;
  yLabel?: string | undefined;
  data: Array<{ label: string; value: number }>;
};

function generateChart(input: ChartInput) {
  const data = input.data
    .map((point) => ({ label: point.label.trim(), value: point.value }))
    .filter((point) => point.label && Number.isFinite(point.value));
  if (data.length === 0) throw new Error("Chart data must include at least one finite value.");
  const svg = input.chartType === "line" ? lineChartSvg(input, data) : barChartSvg(input, data);
  return {
    type: "chart",
    chartType: input.chartType,
    title: input.title.trim(),
    xLabel: input.xLabel?.trim() || null,
    yLabel: input.yLabel?.trim() || null,
    data,
    svg,
    summary: `${input.chartType === "line" ? "Line" : "Bar"} chart with ${data.length} points.`,
  };
}

function chartScale(data: Array<{ value: number }>) {
  const values = data.map((point) => point.value);
  const min = Math.min(0, ...values);
  const max = Math.max(0, ...values);
  const range = max - min || 1;
  return {
    min,
    max,
    range,
    y(value: number) {
      const top = 64;
      const height = 220;
      return top + ((max - value) / range) * height;
    },
  };
}

function barChartSvg(input: ChartInput, data: Array<{ label: string; value: number }>) {
  const width = 720;
  const height = 360;
  const left = 72;
  const right = 24;
  const bottom = 64;
  const chartWidth = width - left - right;
  const chartHeight = 220;
  const scale = chartScale(data);
  const zeroY = scale.y(0);
  const slot = chartWidth / data.length;
  const barWidth = Math.max(10, Math.min(44, slot * 0.62));
  const bars = data
    .map((point, index) => {
      const x = left + slot * index + (slot - barWidth) / 2;
      const y = Math.min(scale.y(point.value), zeroY);
      const h = Math.max(1, Math.abs(zeroY - scale.y(point.value)));
      return `<g><rect x="${round(x)}" y="${round(y)}" width="${round(barWidth)}" height="${round(h)}" rx="4" fill="#2563eb"/><text x="${round(x + barWidth / 2)}" y="314" text-anchor="middle" font-size="11" fill="#475569">${escapeXml(shortLabel(point.label))}</text></g>`;
    })
    .join("");
  return chartSvgFrame(input, {
    width,
    height,
    left,
    bottom,
    chartWidth,
    chartHeight,
    scale,
    body: bars,
  });
}

function lineChartSvg(input: ChartInput, data: Array<{ label: string; value: number }>) {
  const width = 720;
  const height = 360;
  const left = 72;
  const right = 24;
  const bottom = 64;
  const chartWidth = width - left - right;
  const chartHeight = 220;
  const scale = chartScale(data);
  const step = data.length > 1 ? chartWidth / (data.length - 1) : 0;
  const points = data.map((point, index) => ({
    x: left + step * index,
    y: scale.y(point.value),
    label: point.label,
  }));
  const path = points
    .map((point, index) => `${index === 0 ? "M" : "L"} ${round(point.x)} ${round(point.y)}`)
    .join(" ");
  const body = [
    `<path d="${path}" fill="none" stroke="#2563eb" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>`,
    ...points.map(
      (point) =>
        `<g><circle cx="${round(point.x)}" cy="${round(point.y)}" r="4" fill="#1d4ed8"/><text x="${round(point.x)}" y="314" text-anchor="middle" font-size="11" fill="#475569">${escapeXml(shortLabel(point.label))}</text></g>`,
    ),
  ].join("");
  return chartSvgFrame(input, {
    width,
    height,
    left,
    bottom,
    chartWidth,
    chartHeight,
    scale,
    body,
  });
}

function chartSvgFrame(
  input: ChartInput,
  chart: {
    width: number;
    height: number;
    left: number;
    bottom: number;
    chartWidth: number;
    chartHeight: number;
    scale: ReturnType<typeof chartScale>;
    body: string;
  },
) {
  const top = 64;
  const baseY = top + chart.chartHeight;
  const zeroY = chart.scale.y(0);
  const ticks = [chart.scale.max, (chart.scale.max + chart.scale.min) / 2, chart.scale.min];
  const tickMarkup = ticks
    .map((value) => {
      const y = chart.scale.y(value);
      return `<g><line x1="${chart.left}" x2="${chart.left + chart.chartWidth}" y1="${round(y)}" y2="${round(y)}" stroke="#e2e8f0"/><text x="${chart.left - 10}" y="${round(y + 4)}" text-anchor="end" font-size="11" fill="#64748b">${escapeXml(formatNumber(value))}</text></g>`;
    })
    .join("");
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${chart.width}" height="${chart.height}" viewBox="0 0 ${chart.width} ${chart.height}" role="img" aria-label="${escapeXml(input.title)}">`,
    '<rect width="100%" height="100%" rx="12" fill="#ffffff"/>',
    `<text x="${chart.left}" y="34" font-size="20" font-weight="700" fill="#0f172a">${escapeXml(input.title)}</text>`,
    tickMarkup,
    `<line x1="${chart.left}" x2="${chart.left + chart.chartWidth}" y1="${round(baseY)}" y2="${round(baseY)}" stroke="#94a3b8"/>`,
    `<line x1="${chart.left}" x2="${chart.left}" y1="${top}" y2="${baseY}" stroke="#94a3b8"/>`,
    zeroY !== baseY
      ? `<line x1="${chart.left}" x2="${chart.left + chart.chartWidth}" y1="${round(zeroY)}" y2="${round(zeroY)}" stroke="#64748b" stroke-dasharray="4 4"/>`
      : "",
    chart.body,
    input.xLabel
      ? `<text x="${chart.left + chart.chartWidth / 2}" y="${chart.height - 16}" text-anchor="middle" font-size="12" fill="#64748b">${escapeXml(input.xLabel)}</text>`
      : "",
    input.yLabel
      ? `<text transform="translate(18 ${top + chart.chartHeight / 2}) rotate(-90)" text-anchor="middle" font-size="12" fill="#64748b">${escapeXml(input.yLabel)}</text>`
      : "",
    "</svg>",
  ].join("");
}

type SpreadsheetInput = {
  data: string;
  delimiter: "auto" | "comma" | "semicolon" | "tab" | "pipe";
  hasHeader: boolean;
};

function analyzeDelimitedData(input: SpreadsheetInput) {
  const delimiter = resolveDelimiter(input.data, input.delimiter);
  const rows = parseDelimitedRows(input.data, delimiter).slice(0, 501);
  if (rows.length === 0) throw new Error("No rows found in pasted data.");
  const headers = input.hasHeader
    ? normalizeHeaders(rows[0] ?? [])
    : (rows[0] ?? []).map((_, index) => `Column ${index + 1}`);
  const dataRows = input.hasHeader ? rows.slice(1) : rows;
  const normalizedRows = dataRows.map((row) =>
    headers.map((header, index) => ({ header, value: row[index] ?? "" })),
  );
  const stats = headers.map((header, index) =>
    numericStats(
      header,
      dataRows.map((row) => row[index] ?? ""),
    ),
  );
  return {
    type: "table-analysis",
    delimiter: delimiter === "\t" ? "tab" : delimiter,
    rowCount: dataRows.length,
    columnCount: headers.length,
    columns: headers,
    preview: normalizedRows
      .slice(0, 8)
      .map((row) => Object.fromEntries(row.map((cell) => [cell.header, cell.value]))),
    numericColumns: stats.filter((item) => item.numericCount > 0),
    truncated: rows.length > 500,
  };
}

function resolveDelimiter(data: string, requested: SpreadsheetInput["delimiter"]) {
  if (requested === "comma") return ",";
  if (requested === "semicolon") return ";";
  if (requested === "tab") return "\t";
  if (requested === "pipe") return "|";
  const firstLine = data.split(/\r?\n/, 1)[0] ?? "";
  const candidates = [",", ";", "\t", "|"];
  return (
    candidates
      .map((candidate) => ({ candidate, count: firstLine.split(candidate).length - 1 }))
      .sort((a, b) => b.count - a.count)[0]?.candidate ?? ","
  );
}

function parseDelimitedRows(data: string, delimiter: string) {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let inQuotes = false;
  for (let index = 0; index < data.length; index += 1) {
    const char = data[index] ?? "";
    const next = data[index + 1] ?? "";
    if (char === '"' && inQuotes && next === '"') {
      cell += '"';
      index += 1;
      continue;
    }
    if (char === '"') {
      inQuotes = !inQuotes;
      continue;
    }
    if (!inQuotes && char === delimiter) {
      row.push(cell.trim());
      cell = "";
      continue;
    }
    if (!inQuotes && (char === "\n" || char === "\r")) {
      if (char === "\r" && next === "\n") index += 1;
      row.push(cell.trim());
      if (row.some((value) => value.length > 0)) rows.push(row);
      row = [];
      cell = "";
      continue;
    }
    cell += char;
  }
  row.push(cell.trim());
  if (row.some((value) => value.length > 0)) rows.push(row);
  return rows;
}

function normalizeHeaders(rawHeaders: string[]) {
  return rawHeaders.map((header, index) => header.trim() || `Column ${index + 1}`);
}

function numericStats(header: string, rawValues: string[]) {
  const values = rawValues
    .map((value) => Number.parseFloat(value.replace(/,/g, "")))
    .filter((value) => Number.isFinite(value));
  const sum = values.reduce((total, value) => total + value, 0);
  return {
    column: header,
    numericCount: values.length,
    min: values.length ? Math.min(...values) : null,
    max: values.length ? Math.max(...values) : null,
    average: values.length ? sum / values.length : null,
    sum: values.length ? sum : null,
  };
}

function formatAgentPlan(input: {
  objective: string;
  steps: Array<{ title: string; notes?: string | undefined }>;
}) {
  return {
    type: "agent-plan",
    objective: input.objective.trim(),
    steps: input.steps.map((step, index) => ({
      index: index + 1,
      title: step.title.trim(),
      status: "pending",
      notes: step.notes?.trim() || null,
    })),
    summary: `${input.steps.length} planned steps.`,
  };
}

function shortLabel(label: string) {
  return label.length > 14 ? `${label.slice(0, 13)}...` : label;
}

function formatNumber(value: number) {
  if (Math.abs(value) >= 1000) return value.toLocaleString("en-US", { maximumFractionDigits: 0 });
  return value.toLocaleString("en-US", { maximumFractionDigits: 2 });
}

function round(value: number) {
  return Number(value.toFixed(2));
}

function escapeXml(value: string) {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
