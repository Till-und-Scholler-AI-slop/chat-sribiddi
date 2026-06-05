import type { Db } from "../db/database.js";
import { notFound } from "../errors/http-error.js";
import type { ChatRepository, MessageRepository } from "../repositories/chat-repository.js";
import type { UserRepository } from "../repositories/user-repository.js";
import type { ApiKeyService } from "./api-key-service.js";
import type { ProviderConnectionService } from "./provider-connection-service.js";

export class AccountService {
  constructor(
    private readonly db: Db,
    private readonly users: UserRepository,
    private readonly chats: ChatRepository,
    private readonly messages: MessageRepository,
    private readonly apiKeys: ApiKeyService,
    private readonly providerConnections: ProviderConnectionService,
  ) {}

  async deleteAccount(userId: string) {
    const user = await this.users.findByProviderId(userId);
    if (!user) throw notFound("No account found for the current user");

    await this.db.transaction().execute(async () => {
      await this.apiKeys.deleteByUser(userId);
      await this.providerConnections.deleteByUser(userId);
      await this.db.deleteFrom("tool_credentials").where("user_id", "=", userId).execute();
      await this.db.deleteFrom("chat_documents").where("user_id", "=", userId).execute();
      await this.messages.deleteByUser(userId);
      await this.chats.deleteByUser(userId);
      await this.users.deleteByProviderId(userId);
    });
  }
}
