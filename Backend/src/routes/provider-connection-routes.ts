import { OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppContext } from "../app-context.js";
import { env } from "../config/env.js";
import type { AppVariables } from "../middleware/request-context.js";
import { currentUserId, intParam, noContent, parseJson } from "./helpers.js";

const authMethodSchema = z.enum([
  "API_KEY",
  "OAUTH_PKCE",
  "OAUTH_AUTH_CODE",
  "DEVICE_CODE",
  "SERVICE_ACCOUNT",
  "LOCAL_ENDPOINT",
  "OPENAI_COMPATIBLE",
  "SUBSCRIPTION_CLI",
  "CLI_SESSION",
  "CUSTOM_HTTP",
]);

const apiKeyPayloadSchema = z.object({
  apiKey: z.string().max(4096).nullable().optional(),
  authMethod: authMethodSchema.nullable().optional(),
  baseUrl: z.string().max(2048).nullable().optional(),
  headers: z.record(z.string(), z.string()).nullable().optional(),
  queryParams: z.record(z.string(), z.string()).nullable().optional(),
  defaultModelId: z.string().max(255).nullable().optional(),
  displayName: z.string().max(255).nullable().optional(),
});

const connectPayloadSchema = z
  .object({
    authMethod: authMethodSchema.nullable().optional(),
  })
  .partial();

export function providerConnectionRoutes(appContext: AppContext) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  app.get("/provider-connections", async (c) => {
    const userId = await currentUserId(c, appContext);
    return c.json(await appContext.services.providerConnections.listConnections(userId));
  });

  app.post("/provider-connections/:providerId/api-key", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, apiKeyPayloadSchema);
    const saved = await appContext.services.providerConnections.saveCredential(
      userId,
      c.req.param("providerId"),
      body,
    );
    return c.json(saved);
  });

  app.delete("/provider-connections/:providerId/api-key", async (c) => {
    const userId = await currentUserId(c, appContext);
    await appContext.services.providerConnections.deleteApiKey(userId, c.req.param("providerId"));
    return noContent(c);
  });

  app.post("/provider-connections/:providerId/connect", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, connectPayloadSchema);
    const providerId = c.req.param("providerId");
    const callbackUri = new URL(
      `/provider-connections/${encodeURIComponent(providerId)}/callback`,
      env.APP_FRONTEND_URL,
    ).toString();
    return c.json(
      appContext.services.providerConnections.startConnection(
        userId,
        providerId,
        body.authMethod,
        callbackUri,
      ),
    );
  });

  app.get("/provider-connections/:providerId/callback", async (c) => {
    const userId = await currentUserId(c, appContext);
    const providerId = c.req.param("providerId");
    const redirect = settingsRedirect(providerId);
    const error = c.req.query("error");
    try {
      if (error) throw new Error(error);
      if (providerId.toLowerCase() === "openrouter") {
        await appContext.services.providerConnections.completeOpenRouterPkce(
          userId,
          c.req.query("state") ?? null,
          c.req.query("code") ?? null,
        );
      } else if (providerId.toLowerCase() === "google") {
        await appContext.services.providerConnections.completeGoogleOAuth(
          userId,
          c.req.query("state") ?? null,
          c.req.query("code") ?? null,
        );
      } else {
        throw new Error("Provider callback is not enabled");
      }
      redirect.searchParams.set("connected", "1");
    } catch (callbackError) {
      redirect.searchParams.set("connected", "0");
      redirect.searchParams.set(
        "error",
        callbackError instanceof Error ? callbackError.message : "Provider callback failed",
      );
    }
    return c.redirect(redirect.toString(), 302);
  });

  app.delete("/provider-connections/:connectionId", async (c) => {
    const userId = await currentUserId(c, appContext);
    await appContext.services.providerConnections.deleteConnection(
      userId,
      intParam(c.req.param("connectionId"), "connectionId"),
    );
    return noContent(c);
  });

  return app;
}

function settingsRedirect(providerId: string) {
  const redirect = new URL("/settings", env.APP_FRONTEND_URL);
  redirect.searchParams.set("provider", providerId);
  return redirect;
}
