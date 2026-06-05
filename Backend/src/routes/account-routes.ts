import { OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppContext } from "../app-context.js";
import type { AppVariables } from "../middleware/request-context.js";
import { currentUserId, noContent, parseJson } from "./helpers.js";

const settingsSchema = z.object({
  customInstructions: z.string().max(20_000).nullable().optional(),
});

export function accountRoutes(appContext: AppContext) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  for (const prefix of ["/account/settings", "/settings"]) {
    app.get(prefix, async (c) => {
      const userId = await currentUserId(c, appContext);
      return c.json(await appContext.services.settings.getSettings(userId));
    });

    app.put(prefix, async (c) => {
      const userId = await currentUserId(c, appContext);
      const body = await parseJson(c, settingsSchema);
      await appContext.services.settings.updateSettings(userId, body.customInstructions);
      return noContent(c);
    });
  }

  app.delete("/account", async (c) => {
    const userId = await currentUserId(c, appContext);
    await appContext.services.account.deleteAccount(userId);
    return appContext.services.auth.logout(c);
  });

  return app;
}
