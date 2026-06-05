import type { Provider } from "../db/types.js";
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
    const user = await this.users.findByProviderId(userId);
    if (!user) return null;
    const events = await this.usage.listForDashboard(user.id, since, until, modelId);
    const byModel = new Map<string, UsageAccumulator>();
    const byDay = new Map<string, UsageAccumulator>();
    for (const event of events) {
      const modelKey = `${event.provider}:${event.model_id}`;
      const modelAcc =
        byModel.get(modelKey) ?? new UsageAccumulator(event.provider, event.model_id);
      modelAcc.add(event);
      byModel.set(modelKey, modelAcc);
      const day = new Date(event.created_at).toISOString().slice(0, 10);
      const dayAcc = byDay.get(day) ?? new UsageAccumulator(event.provider, event.model_id);
      dayAcc.add(event);
      byDay.set(day, dayAcc);
    }
    const items = [...byModel.values()]
      .map((item) => item.toItem())
      .sort((a, b) => b.calls - a.calls);
    const daily = [...byDay.entries()]
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([day, acc]) => ({
        day,
        calls: acc.calls,
        totalTokens: acc.totalTokens,
        costMicroCents: acc.costMicroCents,
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

class UsageAccumulator {
  calls = 0;
  promptTokens = 0;
  completionTokens = 0;
  totalTokens = 0;
  costMicroCents = 0;

  constructor(
    readonly provider: Provider,
    readonly modelId: string,
  ) {}

  add(event: {
    prompt_tokens: number | null;
    completion_tokens: number | null;
    total_tokens: number | null;
    cost_micro_cents: number | null;
  }) {
    this.calls += 1;
    this.promptTokens += Math.max(0, event.prompt_tokens ?? 0);
    this.completionTokens += Math.max(0, event.completion_tokens ?? 0);
    this.totalTokens += Math.max(
      0,
      event.total_tokens ?? (event.prompt_tokens ?? 0) + (event.completion_tokens ?? 0),
    );
    this.costMicroCents += Math.max(0, event.cost_micro_cents ?? 0);
  }

  toItem() {
    return {
      provider: this.provider,
      modelId: this.modelId,
      calls: this.calls,
      promptTokens: this.promptTokens,
      completionTokens: this.completionTokens,
      totalTokens: this.totalTokens,
      costMicroCents: this.costMicroCents,
    };
  }
}
