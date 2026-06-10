/**
 * Wraps {@link fetch} with a default timeout so a single hung upstream cannot
 * tie up a Hono worker forever. Use the helper everywhere a backend service
 * makes a network call to a third party.
 */
export async function fetchWithTimeout(
  input: string | URL,
  init: RequestInit & { timeoutMs?: number; fallbackTimeoutMs?: number } = {},
): Promise<Response> {
  const { timeoutMs, fallbackTimeoutMs = 15_000, signal, ...rest } = init;
  const controller = new AbortController();
  const onAbort = () => controller.abort(signal?.reason);
  if (signal) {
    if (signal.aborted) controller.abort(signal.reason);
    else signal.addEventListener("abort", onAbort, { once: true });
  }
  const effective = clampTimeout(timeoutMs ?? fallbackTimeoutMs);
  const timer = setTimeout(() => controller.abort(new Error("Upstream request timed out")), effective);
  timer.unref?.();
  try {
    return await fetch(input, { ...rest, signal: controller.signal });
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener("abort", onAbort);
  }
}

function clampTimeout(value: number) {
  if (!Number.isFinite(value) || value <= 0) return 15_000;
  return Math.min(value, 5 * 60_000);
}
