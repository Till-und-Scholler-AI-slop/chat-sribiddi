import { createDb, type Db } from "./db/database.js";
import { ChatRepository, MessageRepository } from "./repositories/chat-repository.js";
import { DocumentRepository } from "./repositories/document-repository.js";
import { ApiKeyRepository } from "./repositories/key-repository.js";
import { ProviderConnectionRepository } from "./repositories/provider-connection-repository.js";
import { ChatToolRepository, ToolCallLogRepository } from "./repositories/tool-repository.js";
import { UsageRepository } from "./repositories/usage-repository.js";
import { UserRepository } from "./repositories/user-repository.js";
import { AccountService } from "./services/account-service.js";
import { AiChatService } from "./services/ai-chat-service.js";
import { ApiKeyService } from "./services/api-key-service.js";
import { AuthService } from "./services/auth-service.js";
import { ChatService } from "./services/chat-service.js";
import { CredentialAccessService } from "./services/credential-access-service.js";
import { CryptoService } from "./services/crypto-service.js";
import { DocumentService } from "./services/document-service.js";
import { GuestQuotaService } from "./services/guest-quota-service.js";
import { ModelCatalogService } from "./services/model-catalog-service.js";
import { ProviderCatalogService } from "./services/provider-catalog-service.js";
import { ProviderConnectionService } from "./services/provider-connection-service.js";
import { StreamConcurrencyGate } from "./services/stream-concurrency-gate.js";
import { ToolService } from "./services/tool-service.js";
import { UsageService } from "./services/usage-service.js";
import { UserSettingsService } from "./services/user-settings-service.js";
import { WebSearchService } from "./services/web-search-service.js";
import { ModelUseCase } from "./use-cases/model-use-case.js";

export type AppContext = ReturnType<typeof createAppContext>;

export function createAppContext(input?: { db?: Db; shutdown?: () => Promise<void> }) {
  const created = input?.db ? null : createDb();
  const db = input?.db ?? created?.db;
  if (!db) throw new Error("Database handle is required");

  const users = new UserRepository(db);
  const chats = new ChatRepository(db);
  const messages = new MessageRepository(db);
  const apiKeyRepository = new ApiKeyRepository(db);
  const providerConnectionRepository = new ProviderConnectionRepository(db);
  const chatTools = new ChatToolRepository(db);
  const toolLogs = new ToolCallLogRepository(db);
  const documentsRepository = new DocumentRepository(db);
  const usageRepository = new UsageRepository(db);

  const crypto = new CryptoService();
  const providerCatalog = new ProviderCatalogService();
  const providerConnections = new ProviderConnectionService(
    providerConnectionRepository,
    providerCatalog,
    crypto,
  );
  const apiKeys = new ApiKeyService(apiKeyRepository, crypto, providerConnections);
  const credentialAccess = new CredentialAccessService(apiKeys, providerConnections);
  const modelCatalog = new ModelCatalogService();
  const usage = new UsageService(users, usageRepository);
  const documentService = new DocumentService(chats, documentsRepository);
  const webSearch = new WebSearchService();
  const toolService = new ToolService(chats, chatTools);
  const auth = new AuthService(users);
  const settings = new UserSettingsService(users);
  const account = new AccountService(db, users, chats, messages, apiKeys, providerConnections);
  const chat = new ChatService(
    chats,
    messages,
    new AiChatService(),
    credentialAccess,
    providerConnections,
    apiKeys,
    documentService,
    usage,
    toolLogs,
    toolService,
    webSearch,
    new GuestQuotaService(),
    new StreamConcurrencyGate(),
  );
  const models = new ModelUseCase(modelCatalog, apiKeys, credentialAccess, providerCatalog);

  return {
    db,
    shutdown: input?.shutdown ?? (() => created?.pool.end() ?? Promise.resolve()),
    repositories: {
      users,
      chats,
      messages,
      apiKeys: apiKeyRepository,
      providerConnections: providerConnectionRepository,
      chatTools,
      toolLogs,
      documents: documentsRepository,
      usage: usageRepository,
    },
    services: {
      account,
      apiKeys,
      auth,
      chat,
      credentialAccess,
      documents: documentService,
      models,
      providerCatalog,
      providerConnections,
      settings,
      tools: toolService,
      usage,
    },
  };
}
