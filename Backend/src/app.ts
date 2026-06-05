import { OpenAPIHono } from "@hono/zod-openapi";
import { cors } from "hono/cors";
import type { AppContext } from "./app-context.js";
import { createAppContext } from "./app-context.js";
import { env } from "./config/env.js";
import { handleError } from "./errors/handler.js";
import { csrfMiddleware } from "./middleware/csrf.js";
import { rateLimitMiddleware } from "./middleware/rate-limit.js";
import { type AppVariables, requestContextMiddleware } from "./middleware/request-context.js";
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
      exposeHeaders: ["X-Request-Id", "X-RateLimit-Limit", "X-RateLimit-Remaining"],
    }),
  );
  app.use("*", csrfMiddleware(appContext));
  app.use("*", rateLimitMiddleware(appContext));

  app.get("/actuator/health", (c) => c.json({ status: "UP" }));
  app.get("/health", (c) => c.json({ status: "UP" }));
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
