import { describe, expect, it } from "vitest";
import { booleanEnv } from "./env.js";

describe("booleanEnv", () => {
  it.each([
    ["true", true],
    ["1", true],
    ["yes", true],
    ["on", true],
    ["false", false],
    ["0", false],
    ["no", false],
    ["off", false],
    [true, true],
    [false, false],
  ])("parses %j as %s", (input, expected) => {
    expect(booleanEnv.parse(input)).toBe(expected);
  });

  it("rejects ambiguous boolean strings", () => {
    expect(() => booleanEnv.parse("enabled")).toThrow();
  });
});
