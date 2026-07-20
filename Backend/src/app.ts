import crypto from "node:crypto";
import { OpenAPIHono } from "@hono/zod-openapi";
import { cors } from "hono/cors";
import type { AppContext } from "./app-context.js";
import { createAppContext } from "./app-context.js";
import { env } from "./config/env.js";
import { handleError } from "./errors/handler.js";
import { requestBodyLimitMiddleware } from "./middleware/body-limit.js";
import { csrfMiddleware } from "./middleware/csrf.js";
import { rateLimitMiddleware } from "./middleware/rate-limit.js";
import { type AppVariables, requestContextMiddleware } from "./middleware/request-context.js";
import { metricsRegistry } from "./observability/metrics.js";
import { accountRoutes } from "./routes/account-routes.js";
import { apiKeyRoutes } from "./routes/api-key-routes.js";
import { authRoutes } from "./routes/auth-routes.js";
import { chatRoutes } from "./routes/chat-routes.js";
import { modelRoutes } from "./routes/model-routes.js";
import { providerConnectionRoutes } from "./routes/provider-connection-routes.js";
import { providerRoutes } from "./routes/provider-routes.js";
import { usageRoutes } from "./routes/usage-routes.js";

export function createApp(appContext: AppContext = createAppContext()) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  app.onError(handleError);
  app.use("*", requestContextMiddleware);
  app.use(
    "*",
    cors({
      origin: (origin) => {
        if (!origin) return env.APP_FRONTEND_URL;
        return env.allowedOrigins.includes(origin) ? origin : env.APP_FRONTEND_URL;
      },
      allowHeaders: [
        "Content-Type",
        "Authorization",
        "X-Chat-User-Id",
        "X-XSRF-TOKEN",
        "X-Request-Id",
      ],
      allowMethods: ["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
      credentials: true,
      exposeHeaders: [
        "X-Request-Id",
        "X-RateLimit-Limit",
        "X-RateLimit-Remaining",
        "X-RateLimit-Reset",
        "Retry-After",
      ],
    }),
  );
  app.use("*", requestBodyLimitMiddleware);
  app.use("*", csrfMiddleware(appContext));
  app.use("*", rateLimitMiddleware(appContext));

  app.get("/health/live", (c) => c.json(appContext.services.health.liveness()));
  app.get("/health", (c) => c.json(appContext.services.health.liveness()));
  app.get("/health/ready", async (c) => {
    const readiness = await appContext.services.health.readiness();
    return c.json(readiness.body, readiness.httpStatus);
  });
  app.get("/actuator/health", async (c) => {
    const readiness = await appContext.services.health.readiness();
    return c.json({ status: readiness.httpStatus === 200 ? "UP" : "DOWN" }, readiness.httpStatus);
  });
  app.get("/internal/metrics", async (c) => {
    const configured = env.METRICS_TOKEN?.trim();
    const supplied = c.req
      .header("authorization")
      ?.replace(/^Bearer\s+/i, "")
      .trim();
    if (!configured || !supplied || !safeEqual(configured, supplied)) return c.notFound();
    return c.text(await metricsRegistry.metrics(), 200, {
      "Content-Type": metricsRegistry.contentType,
      "Cache-Control": "no-store",
    });
  });
  app.get("/chat/capabilities", (c) => {
    const authenticated = Boolean(appContext.services.auth.resolveUserId(c));
    c.header("Cache-Control", "private, max-age=300");
    c.header("Vary", "Cookie, X-Chat-User-Id");
    return c.json({
      maxAttachments: authenticated
        ? env.ACCESS_AUTH_MAX_ATTACHMENTS
        : env.ACCESS_GUEST_MAX_ATTACHMENTS,
      maxAttachmentBytes: authenticated
        ? env.ACCESS_AUTH_MAX_ATTACHMENT_BYTES
        : env.ACCESS_GUEST_MAX_ATTACHMENT_BYTES,
      maxTotalAttachmentBytes: authenticated
        ? env.ACCESS_AUTH_MAX_TOTAL_ATTACHMENT_BYTES
        : env.ACCESS_GUEST_MAX_TOTAL_ATTACHMENT_BYTES,
      maxDocumentBytes: env.CHAT_MAX_DOCUMENT_BYTES,
    });
  });
  app.get("/", (c) => c.json({ service: "chat-sribiddi-backend", runtime: "hono" }));

  app.route("/", authRoutes(appContext));
  app.route("/", accountRoutes(appContext));
  app.route("/", apiKeyRoutes(appContext));
  app.route("/", providerConnectionRoutes(appContext));
  app.route("/", providerRoutes(appContext));
  app.route("/", modelRoutes(appContext));
  app.route("/", usageRoutes(appContext));
  app.route("/", chatRoutes(appContext));

  app.doc("/openapi.json", {
    openapi: "3.1.0",
    info: {
      title: "Chat Sribiddi Backend",
      version: "1.0.0",
    },
  });

  return { app, appContext };
}

function safeEqual(left: string, right: string) {
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return (
    leftBuffer.length === rightBuffer.length && crypto.timingSafeEqual(leftBuffer, rightBuffer)
  );
}
