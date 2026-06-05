import { OpenAPIHono } from "@hono/zod-openapi";
import type { AppContext } from "../app-context.js";
import type { AppVariables } from "../middleware/request-context.js";
import { currentUserId, noContent } from "./helpers.js";

export function authRoutes(appContext: AppContext) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  app.get("/secured", async (c) => {
    await currentUserId(c, appContext);
    return noContent(c);
  });

  app.get("/oauth2/authorization/google", (c) => appContext.services.auth.startGoogleLogin(c));
  app.get("/login/oauth2/code/google", (c) => appContext.services.auth.completeGoogleLogin(c));
  app.post("/logout", (c) => appContext.services.auth.logout(c));

  return app;
}
