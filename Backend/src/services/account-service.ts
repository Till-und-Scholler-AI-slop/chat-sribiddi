import type { Db } from "../db/database.js";
import { notFound } from "../errors/http-error.js";
import type { ChatRepository, MessageRepository } from "../repositories/chat-repository.js";
import type { UserRepository } from "../repositories/user-repository.js";
import type { ApiKeyService } from "./api-key-service.js";
import type { ProviderConnectionService } from "./provider-connection-service.js";

export class AccountService {
  constructor(
    private readonly db: Db,
    _users: UserRepository,
    _chats: ChatRepository,
    _messages: MessageRepository,
    _apiKeys: ApiKeyService,
    _providerConnections: ProviderConnectionService,
  ) {}

  async deleteAccount(userId: string) {
    await this.db.transaction().execute(async (trx) => {
      const user = await trx
        .selectFrom("users")
        .select("id")
        .where("provider_id", "=", userId)
        .forUpdate()
        .executeTakeFirst();
      if (!user) throw notFound("No account found for the current user");

      // Every statement deliberately uses the transaction handle. Deleting chats
      // cascades to messages, tool logs, chat tools, documents and chunks.
      await trx.deleteFrom("api_keys").where("user_id", "=", userId).execute();
      await trx.deleteFrom("provider_connections").where("user_id", "=", userId).execute();
      await trx.deleteFrom("tool_credentials").where("user_id", "=", userId).execute();
      await trx.deleteFrom("chat_documents").where("user_id", "=", userId).execute();
      await trx.deleteFrom("chats").where("user_id", "=", userId).execute();
      await trx.deleteFrom("users").where("id", "=", user.id).executeTakeFirst();
    });
  }
}
