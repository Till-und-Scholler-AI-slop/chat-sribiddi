import { OpenAPIHono, z } from "@hono/zod-openapi";
import type { AppContext } from "../app-context.js";
import { badRequest } from "../errors/http-error.js";
import type { AppVariables } from "../middleware/request-context.js";
import { currentUserId, intParam, noContent, optionalIntQuery, parseJson } from "./helpers.js";

const providerSchema = z.enum(["OPENAI", "GOOGLE", "CLAUDE", "OPENROUTER", "BRAVE_SEARCH"]);

const mediaSchema = z.object({
  mimeType: z.string().trim().min(1),
  data: z.string().trim().min(1),
});

const runtimeOptionsSchema = z
  .object({
    temperature: z.number().min(0).max(2).nullable().optional(),
    topP: z.number().min(0).max(1).nullable().optional(),
    topK: z.number().int().positive().max(1000).nullable().optional(),
    maxTokens: z.number().int().positive().max(1_000_000).nullable().optional(),
    frequencyPenalty: z.number().min(-2).max(2).nullable().optional(),
    presencePenalty: z.number().min(-2).max(2).nullable().optional(),
    stopSequences: z.array(z.string().trim().min(1).max(200)).max(8).nullable().optional(),
    thinkingBudget: z.number().int().min(-1).nullable().optional(),
    thinkingLevel: z.string().nullable().optional(),
    includeThoughts: z.boolean().nullable().optional(),
    googleSearchRetrieval: z.boolean().nullable().optional(),
    includeServerSideToolInvocations: z.boolean().nullable().optional(),
    includeExtendedUsageMetadata: z.boolean().nullable().optional(),
    openAiReasoningEffort: z.string().max(64).nullable().optional(),
    openAiVerbosity: z.string().max(64).nullable().optional(),
    openAiServiceTier: z.string().max(64).nullable().optional(),
  })
  .partial();

const toolConsentSchema = z.object({
  toolName: z.string().trim().min(1),
  riskLevel: z.string().trim().min(1),
  args: z.record(z.string(), z.unknown()).optional(),
});

const chatRequestSchema = z.object({
  content: z.string().trim().min(1).max(20_000),
  provider: providerSchema.nullable().optional(),
  providerId: z.string().max(80).nullable().optional(),
  modelId: z.string().trim().min(1),
  connectionId: z.number().int().positive().nullable().optional(),
  media: z.array(mediaSchema).max(5).nullable().optional(),
  webSearchEnabled: z.boolean().nullable().optional(),
  options: runtimeOptionsSchema.nullable().optional(),
  toolConsents: z.array(toolConsentSchema).nullable().optional(),
});

const createChatSchema = z.object({
  title: z.string().max(255).nullable().optional(),
});

const importedMessageSchema = z.object({
  role: z.string().trim().min(1),
  message: z.string().trim().min(1).max(20_000),
  createdAt: z.string().nullable().optional(),
});

const importChatsSchema = z.object({
  chats: z
    .array(
      z.object({
        title: z.string().max(120).nullable().optional(),
        createdAt: z.string().nullable().optional(),
        messages: z.array(importedMessageSchema).max(100).nullable().optional(),
      }),
    )
    .max(30)
    .nullable()
    .optional(),
});

const updateTitleSchema = z.object({
  title: z.string().trim().min(1).max(255),
});

const setToolSchema = z.object({
  toolName: z.string().trim().min(1),
  enabled: z.boolean(),
});

const anonymousRequestSchema = chatRequestSchema.extend({
  provider: z.literal("OPENROUTER"),
  history: z
    .array(
      z.object({
        role: providerRoleSchema(),
        message: z.string().trim().min(1).max(20_000),
      }),
    )
    .max(100)
    .nullable()
    .optional(),
});

export function chatRoutes(appContext: AppContext) {
  const app = new OpenAPIHono<{ Variables: AppVariables }>();

  app.post("/chats", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, createChatSchema);
    return c.json(await appContext.services.chat.createChat(userId, body.title), 201);
  });

  app.get("/chats", async (c) => {
    const userId = await currentUserId(c, appContext);
    return c.json(
      await appContext.services.chat.listChats(
        userId,
        optionalIntQuery(c, "limit"),
        optionalIntQuery(c, "offset"),
      ),
    );
  });

  app.post("/chats/import", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, importChatsSchema);
    return c.json(await appContext.services.chat.importChats(userId, body.chats), 201);
  });

  app.get("/chats/:chatId/messages", async (c) => {
    const userId = await currentUserId(c, appContext);
    return c.json(
      await appContext.services.chat.listMessages(
        c.req.param("chatId"),
        userId,
        optionalIntQuery(c, "limit"),
        optionalIntQuery(c, "offset"),
      ),
    );
  });

  app.post("/chats/:chatId/messages", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, chatRequestSchema);
    setChatLogContext(c, body);
    return c.json(await appContext.services.chat.sendMessage(c.req.param("chatId"), userId, body));
  });

  app.post("/chats/:chatId/messages/stream", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, chatRequestSchema);
    setChatLogContext(c, body);
    return appContext.services.chat.streamMessage(c, c.req.param("chatId"), userId, body);
  });

  app.post("/chats/:chatId/messages/:messageId/stream", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, chatRequestSchema);
    setChatLogContext(c, body);
    return appContext.services.chat.editAndStreamMessage(
      c,
      c.req.param("chatId"),
      userId,
      intParam(c.req.param("messageId"), "messageId"),
      body,
    );
  });

  app.post("/chats/:chatId/documents", async (c) => {
    const userId = await currentUserId(c, appContext);
    const form = await c.req.parseBody();
    const file = form.file;
    if (!(file instanceof File)) throw badRequest("file is required");
    return c.json(
      await appContext.services.documents.upload(c.req.param("chatId"), userId, file),
      202,
    );
  });

  app.get("/chats/:chatId/documents", async (c) => {
    const userId = await currentUserId(c, appContext);
    return c.json(await appContext.services.documents.list(c.req.param("chatId"), userId));
  });

  app.delete("/chats/:chatId/documents/:documentId", async (c) => {
    const userId = await currentUserId(c, appContext);
    await appContext.services.documents.delete(
      c.req.param("chatId"),
      userId,
      intParam(c.req.param("documentId"), "documentId"),
    );
    return noContent(c);
  });

  app.post("/chats/:chatId/export/runbook", async (c) => {
    const userId = await currentUserId(c, appContext);
    return c.json(await appContext.services.chat.exportRunbook(c.req.param("chatId"), userId));
  });

  app.patch("/chats/:chatId", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, updateTitleSchema);
    return c.json(
      await appContext.services.chat.updateTitle(c.req.param("chatId"), userId, body.title),
    );
  });

  app.delete("/chats/:chatId", async (c) => {
    const userId = await currentUserId(c, appContext);
    await appContext.services.chat.deleteChat(c.req.param("chatId"), userId);
    return noContent(c);
  });

  app.get("/chats/:chatId/tools/available", async (c) => {
    const userId = await currentUserId(c, appContext);
    return c.json(
      await appContext.services.tools.listAvailableTools(c.req.param("chatId"), userId),
    );
  });

  app.put("/chats/:chatId/tools", async (c) => {
    const userId = await currentUserId(c, appContext);
    const body = await parseJson(c, setToolSchema);
    return c.json(
      await appContext.services.tools.setToolEnabled(
        c.req.param("chatId"),
        userId,
        body.toolName,
        body.enabled,
      ),
    );
  });

  app.get("/messages/:messageId/tool-calls", async (c) => {
    const userId = await currentUserId(c, appContext);
    return c.json(
      await appContext.services.chat.listToolCalls(
        intParam(c.req.param("messageId"), "messageId"),
        userId,
      ),
    );
  });

  app.post("/anonymous/chats/stream", async (c) => {
    if (appContext.services.auth.hasSession(c)) {
      throw badRequest("Use the authenticated chat endpoint for signed-in sessions");
    }
    const body = await parseJson(c, anonymousRequestSchema);
    setChatLogContext(c, body);
    return appContext.services.chat.streamAnonymousMessage(c, body);
  });

  return app;
}

function setChatLogContext(
  c: { set: (key: "provider", value: string) => void },
  body: {
    provider?: string | null | undefined;
    providerId?: string | null | undefined;
  },
) {
  const provider = body.providerId?.trim() || body.provider?.trim();
  if (provider) c.set("provider", provider.toLowerCase().slice(0, 80));
}

function providerRoleSchema() {
  return z
    .string()
    .trim()
    .transform((value) => value.toUpperCase())
    .refine((value) => value === "USER" || value === "ASSISTANT" || value === "SYSTEM", {
      message: "Unsupported role",
    }) as z.ZodType<"USER" | "ASSISTANT" | "SYSTEM">;
}
