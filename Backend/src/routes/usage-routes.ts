import { OpenAPIHono } from "@hono/zod-openapi";
import type { AppContext } from "../app-context.js";
import { badRequest, unauthorized } from "../errors/http-error.js";
import type { AppVariables } from "../middleware/request-context.js";
import { currentUserId } from "./helpers.js";

const USAGE_WINDOW_MS = 30 * 86_400_000;

export function usageRoutes(appContext: AppContext) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  app.get("/usage", async (c) => {
    const userId = await currentUserId(c, appContext);
    const until = parseUsageTo(c.req.query("to"), new Date());
    const since = parseUsageFrom(c.req.query("from"), new Date(until.getTime() - USAGE_WINDOW_MS));
    if (since >= until) throw badRequest("from must be before to");
    const usage = await appContext.services.usage.dashboard(
      userId,
      since,
      until,
      c.req.query("model")?.trim() || null,
    );
    if (!usage) throw unauthorized("User not found");
    return c.json(usage);
  });

  return app;
}

function parseUsageFrom(value: string | undefined, fallback: Date) {
  return parseUsageInstant(value, fallback, false);
}

function parseUsageTo(value: string | undefined, fallback: Date) {
  return parseUsageInstant(value, fallback, true);
}

function parseUsageInstant(value: string | undefined, fallback: Date, dateEndExclusive: boolean) {
  if (!value?.trim()) return fallback;
  const trimmed = value.trim();
  const direct = Date.parse(trimmed);
  if (Number.isFinite(direct)) return new Date(direct);
  const dateOnly = trimmed.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (dateOnly) {
    const date = new Date(`${trimmed}T00:00:00.000Z`);
    if (dateEndExclusive) date.setUTCDate(date.getUTCDate() + 1);
    return date;
  }
  throw badRequest(`Invalid usage date parameter: ${value}`);
}
