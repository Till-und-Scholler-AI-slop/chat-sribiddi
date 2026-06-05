import type { Context } from "hono";
import type { z } from "zod";
import type { AppContext } from "../app-context.js";
import type { Provider } from "../db/types.js";
import { badRequest } from "../errors/http-error.js";

export async function currentUserId(c: Context, appContext: AppContext) {
  const userId = appContext.services.auth.requireUserId(c);
  await appContext.services.auth.ensureDevUser(userId);
  appContext.services.auth.ensureXsrf(c);
  return userId;
}

export async function optionalUserId(c: Context, appContext: AppContext) {
  const userId = appContext.services.auth.resolveUserId(c);
  if (userId) {
    await appContext.services.auth.ensureDevUser(userId);
    appContext.services.auth.ensureXsrf(c);
  }
  return userId;
}

export async function parseJson<T extends z.ZodType>(c: Context, schema: T): Promise<z.infer<T>> {
  let body: unknown = {};
  try {
    body = await c.req.json();
  } catch {
    body = {};
  }
  return schema.parse(body);
}

export function providerParam(value: string): Provider {
  const normalized = value.trim().toUpperCase().replaceAll("-", "_");
  if (
    normalized === "OPENAI" ||
    normalized === "GOOGLE" ||
    normalized === "CLAUDE" ||
    normalized === "OPENROUTER" ||
    normalized === "BRAVE_SEARCH"
  ) {
    return normalized;
  }
  throw badRequest(`Unknown provider: ${value}`);
}

export function intParam(value: string | undefined, name: string) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed)) throw badRequest(`${name} must be an integer`);
  return parsed;
}

export function optionalIntQuery(c: Context, name: string) {
  const value = c.req.query(name);
  if (value == null || value === "") return null;
  return intParam(value, name);
}

export function booleanQuery(c: Context, name: string) {
  const value = c.req.query(name);
  return value === "true" || value === "1";
}

export const noContent = (c: Context) => c.body(null, 204);

export const errorRedirect = (providerId: string, error: string) => {
  const url = new URL("/settings", "http://placeholder");
  url.searchParams.set("provider", providerId);
  url.searchParams.set("connected", "0");
  url.searchParams.set("error", error);
  return `${url.pathname}${url.search}`;
};
