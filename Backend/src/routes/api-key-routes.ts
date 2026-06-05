import { OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppContext } from "../app-context.js";
import type { AppVariables } from "../middleware/request-context.js";
import { currentUserId, noContent, parseJson, providerParam } from "./helpers.js";

const apiKeySchema = z.object({
  apiKey: z.string().trim().min(1).max(4096),
});

export function apiKeyRoutes(appContext: AppContext) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  app.put("/apikeys/:provider", async (c) => {
    const userId = await currentUserId(c, appContext);
    const provider = providerParam(c.req.param("provider"));
    const body = await parseJson(c, apiKeySchema);
    await appContext.services.apiKeys.saveKey(userId, provider, body.apiKey);
    return noContent(c);
  });

  app.delete("/apikeys/:provider", async (c) => {
    const userId = await currentUserId(c, appContext);
    const provider = providerParam(c.req.param("provider"));
    await appContext.services.apiKeys.deleteKey(userId, provider);
    return noContent(c);
  });

  app.get("/apikeys/:provider/exists", async (c) => {
    const userId = await currentUserId(c, appContext);
    const provider = providerParam(c.req.param("provider"));
    return c.json(await appContext.services.apiKeys.hasKey(userId, provider));
  });

  return app;
}
