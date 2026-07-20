import { once } from "node:events";
import type { ServerResponse } from "node:http";
import {
  formatProviderSseEvent,
  PROVIDER_CONTRACT_VERSION,
  type ProviderStreamEvent,
} from "@chat-sribiddi/provider-contract";
import { GatewayError } from "./errors.js";

type EventBaseKeys = "contractVersion" | "requestId" | "sequence" | "timestamp";
export type ProviderEventInput = ProviderStreamEvent extends infer Event
  ? Event extends ProviderStreamEvent
    ? Omit<Event, EventBaseKeys>
    : never
  : never;

export class ProviderSseWriter {
  private sequence = 0;
  private heartbeat: ReturnType<typeof setInterval> | null = null;
  private pendingWrite = Promise.resolve();
  private ended = false;

  constructor(
    private readonly response: ServerResponse,
    private readonly requestId: string,
    private readonly heartbeatIntervalMs: number,
    private readonly signal: AbortSignal,
  ) {}

  start() {
    this.response.writeHead(200, {
      "content-type": "text/event-stream; charset=utf-8",
      "cache-control": "no-cache, no-transform",
      connection: "keep-alive",
      "x-accel-buffering": "no",
      "x-content-type-options": "nosniff",
    });
    this.response.flushHeaders();
    this.heartbeat = setInterval(() => {
      void this.queueWrite(": heartbeat\n\n").catch(() => undefined);
    }, this.heartbeatIntervalMs);
    this.heartbeat.unref?.();
  }

  emit(input: ProviderEventInput) {
    const event = {
      ...input,
      contractVersion: PROVIDER_CONTRACT_VERSION,
      requestId: this.requestId,
      sequence: this.sequence,
      timestamp: new Date().toISOString(),
    } as ProviderStreamEvent;
    this.sequence += 1;
    return this.queueWrite(formatProviderSseEvent(event), input.type === "error");
  }

  async close() {
    if (this.ended) return;
    this.ended = true;
    if (this.heartbeat) clearInterval(this.heartbeat);
    await this.pendingWrite.catch(() => undefined);
    if (!this.response.writableEnded && !this.response.destroyed) this.response.end();
  }

  private queueWrite(value: string, allowAfterAbort = false) {
    const next = this.pendingWrite.then(() => this.write(value, allowAfterAbort));
    this.pendingWrite = next.catch(() => undefined);
    return next;
  }

  private async write(value: string, allowAfterAbort: boolean) {
    if (this.signal.aborted && !allowAfterAbort) throw abortError(this.signal);
    if (this.response.destroyed || this.response.writableEnded) {
      throw new GatewayError("client_closed", "Provider stream client disconnected", 499, false);
    }
    if (this.response.write(value)) return;
    let abortListener: (() => void) | undefined;
    const aborted = allowAfterAbort
      ? undefined
      : new Promise<never>((_resolve, reject) => {
          abortListener = () => reject(abortError(this.signal));
          this.signal.addEventListener("abort", abortListener, { once: true });
          if (this.signal.aborted) abortListener();
        });
    try {
      await Promise.race([
        once(this.response, "drain"),
        once(this.response, "close").then(() => {
          throw new GatewayError(
            "client_closed",
            "Provider stream client disconnected",
            499,
            false,
          );
        }),
        ...(aborted ? [aborted] : []),
      ]);
    } finally {
      if (abortListener) this.signal.removeEventListener("abort", abortListener);
    }
  }
}

function abortError(signal: AbortSignal) {
  return signal.reason instanceof Error
    ? signal.reason
    : new GatewayError("request_aborted", "Provider request was cancelled", 499, false);
}
