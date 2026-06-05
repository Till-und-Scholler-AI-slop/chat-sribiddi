import { OpenAPIHono } from "@hono/zod-openapi";
import type { AppContext } from "../app-context.js";
import type { AppVariables } from "../middleware/request-context.js";
import { optionalUserId } from "./helpers.js";

export function providerRoutes(appContext: AppContext) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  app.get("/providers", async (c) => {
    const userId = await optionalUserId(c, appContext);
    return c.json(await appContext.services.providerConnections.listCatalogWithStatus(userId));
  });

  return app;
}
