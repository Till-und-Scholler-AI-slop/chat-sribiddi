import { describe, expect, it } from "vitest";
import { googleLoginRedirectUri } from "./auth-service.js";

describe("Google login OAuth redirect URI", () => {
  it("uses the configured public frontend origin instead of the backend request scheme", () => {
    expect(googleLoginRedirectUri("https://germinai.de")).toBe(
      "https://germinai.de/login/oauth2/code/google",
    );
  });

  it("normalizes configured origins with a trailing slash", () => {
    expect(googleLoginRedirectUri("https://germinai.de/")).toBe(
      "https://germinai.de/login/oauth2/code/google",
    );
  });
});
