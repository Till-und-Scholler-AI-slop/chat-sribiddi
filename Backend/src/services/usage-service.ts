import type { Provider } from "../db/types.js";
import { badRequest } from "../errors/http-error.js";
import type { UsageRepository } from "../repositories/usage-repository.js";
import type { UserRepository } from "../repositories/user-repository.js";

export type TokenUsage = {
  promptTokens: number | null;
  generationTokens: number | null;
  totalTokens: number | null;
};

export class UsageService {
  constructor(
    private readonly users: UserRepository,
    private readonly usage: UsageRepository,
  ) {}

  async record(input: {
    userId: string;
    provider: Provider;
    modelId: string;
    messageId: number | null;
    tokenUsage: TokenUsage;
    credentialSource: string;
  }) {
    const user = await this.users.findByProviderId(input.userId);
    if (!user) return;
    await this.usage.create({
      userId: user.id,
      provider: input.provider,
      modelId: input.modelId,
      messageId: input.messageId,
      promptTokens: input.tokenUsage.promptTokens,
      completionTokens: input.tokenUsage.generationTokens,
      totalTokens: input.tokenUsage.totalTokens,
      costMicroCents: null,
      credentialSource: input.credentialSource,
    });
  }

  async dashboard(userId: string, since: Date, until: Date, modelId?: string | null) {
    const earliestAllowed = new Date(until);
    earliestAllowed.setUTCFullYear(earliestAllowed.getUTCFullYear() - 1);
    if (since < earliestAllowed) throw badRequest("Usage range must not exceed one year");
    const user = await this.users.findByProviderId(userId);
    if (!user) return null;
    const [modelRows, dayRows] = await Promise.all([
      this.usage.aggregateByModel(user.id, since, until, modelId),
      this.usage.aggregateByDay(user.id, since, until, modelId),
    ]);
    const items = modelRows.map((row) => ({
      provider: row.provider,
      modelId: row.model_id,
      calls: Number(row.calls),
      promptTokens: Number(row.prompt_tokens),
      completionTokens: Number(row.completion_tokens),
      totalTokens: Number(row.total_tokens),
      costMicroCents: Number(row.cost_micro_cents),
    }));
    const daily = dayRows.map((row) => ({
      day: row.day,
      calls: Number(row.calls),
      totalTokens: Number(row.total_tokens),
      costMicroCents: Number(row.cost_micro_cents),
    }));
    return {
      since: since.toISOString(),
      until: until.toISOString(),
      totalCalls: items.reduce((sum, item) => sum + item.calls, 0),
      totalTokens: items.reduce((sum, item) => sum + item.totalTokens, 0),
      totalCostMicroCents: items.reduce((sum, item) => sum + item.costMicroCents, 0),
      items,
      daily,
    };
  }
}
