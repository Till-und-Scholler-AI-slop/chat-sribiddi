import { serve } from "@hono/node-server";
import { createApp } from "./app.js";
import { env } from "./config/env.js";

const { app, appContext } = createApp();

const server = serve(
  {
    fetch: app.fetch,
    port: env.PORT,
    hostname: env.HOST,
  },
  (info) => {
    console.info(`Hono backend listening on http://${info.address}:${info.port}`);
  },
);

const shutdown = async (signal: string) => {
  console.info(`Received ${signal}, shutting down`);
  server.close();
  await appContext.shutdown();
};

process.on("SIGINT", () => {
  void shutdown("SIGINT");
});
process.on("SIGTERM", () => {
  void shutdown("SIGTERM");
});
