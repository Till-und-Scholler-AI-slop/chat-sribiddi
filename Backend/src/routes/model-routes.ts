import { OpenAPIHono } from "@hono/zod-openapi";
import type { AppContext } from "../app-context.js";
import type { Provider } from "../db/types.js";
import type { AppVariables } from "../middleware/request-context.js";
import { booleanQuery, optionalUserId } from "./helpers.js";

const legacyPaths: Array<[string, Provider]> = [
  ["/models/openai", "OPENAI"],
  ["/models/google", "GOOGLE"],
  ["/models/claude", "CLAUDE"],
  ["/models/openrouter", "OPENROUTER"],
];

export function modelRoutes(appContext: AppContext) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  app.get("/models", async (c) => {
    const userId = await optionalUserId(c, appContext);
    return c.json(
      await appContext.services.models.listByProviderId(
        userId,
        c.req.query("provider") ?? "",
        booleanQuery(c, "expert"),
      ),
    );
  });

  for (const [path, provider] of legacyPaths) {
    app.get(path, async (c) => {
      const userId = await optionalUserId(c, appContext);
      return c.json(
        await appContext.services.models.listLegacy(userId, provider, booleanQuery(c, "expert")),
      );
    });
  }

  return app;
}
