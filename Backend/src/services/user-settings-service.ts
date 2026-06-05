import type { UserRepository } from "../repositories/user-repository.js";

export class UserSettingsService {
  constructor(private readonly users: UserRepository) {}

  async getSettings(userId: string) {
    const user = await this.users.findByProviderId(userId);
    return {
      customInstructions: user?.custom_instructions ?? null,
    };
  }

  async updateSettings(userId: string, customInstructions: string | null | undefined) {
    await this.users.findOrCreate(userId, null);
    const trimmed = customInstructions?.trim();
    await this.users.updateCustomInstructions(userId, trimmed ? trimmed.slice(0, 20_000) : null);
  }
}
