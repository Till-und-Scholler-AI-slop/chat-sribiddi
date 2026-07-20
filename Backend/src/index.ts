import { serve } from "@hono/node-server";
import { createApp } from "./app.js";
import { env } from "./config/env.js";
import { logger, safeError } from "./observability/logger.js";

const { app, appContext } = createApp();

const server = serve(
  {
    fetch: app.fetch,
    port: env.PORT,
    hostname: env.HOST,
  },
  (info) => {
    logger.info({ address: info.address, port: info.port }, "Hono backend listening");
  },
);
const drainableServer = server as typeof server & {
  closeIdleConnections?: () => void;
  closeAllConnections?: () => void;
};

let shutdownPromise: Promise<void> | null = null;

const shutdown = (signal: string) => {
  if (shutdownPromise) return shutdownPromise;
  shutdownPromise = (async () => {
    logger.info({ signal }, "Shutdown requested");
    appContext.services.health.beginShutdown();
    const closed = new Promise<boolean>((resolve) => {
      server.close((error) => resolve(!error));
      drainableServer.closeIdleConnections?.();
    });
    const graceExpired = new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => resolve(false), env.SHUTDOWN_GRACE_MS);
      timer.unref?.();
    });
    const graceful = await Promise.race([closed, graceExpired]);
    if (!graceful) {
      logger.warn({ graceMs: env.SHUTDOWN_GRACE_MS }, "Shutdown grace period expired");
      drainableServer.closeAllConnections?.();
    }
    await appContext.shutdown();
    logger.info("Shutdown completed");
  })().catch((error) => {
    logger.error({ error: safeError(error) }, "Shutdown failed");
    process.exitCode = 1;
  });
  return shutdownPromise;
};

process.on("SIGINT", () => {
  void shutdown("SIGINT");
});
process.on("SIGTERM", () => {
  void shutdown("SIGTERM");
});

process.on("uncaughtException", (error) => {
  logger.fatal({ error: safeError(error) }, "Uncaught exception");
  void shutdown("uncaughtException");
});

process.on("unhandledRejection", (error) => {
  logger.error({ error: safeError(error) }, "Unhandled promise rejection");
});
