import type { Redis } from "ioredis";
import { describe, expect, it, vi } from "vitest";
import { RedisService } from "./redis-service.js";

describe("RedisService connection recovery", () => {
  it("reconnects from end after a failed connection attempt", async () => {
    const client = redisClientStub();
    client.connect
      .mockImplementationOnce(async () => {
        client.status = "end";
        throw new Error("Redis is restarting");
      })
      .mockImplementationOnce(async () => {
        client.status = "ready";
      });
    const service = new RedisService(client as unknown as Redis);

    await expect(service.ping()).resolves.toBe(false);
    await expect(service.ping()).resolves.toBe(true);

    expect(client.connect).toHaveBeenCalledTimes(2);
    expect(client.ping).toHaveBeenCalledOnce();
  });

  it("shares one reconnect attempt between concurrent callers", async () => {
    const client = redisClientStub();
    let finishConnect!: () => void;
    client.connect.mockImplementation(() => {
      client.status = "connecting";
      return new Promise<void>((resolve) => {
        finishConnect = () => {
          client.status = "ready";
          resolve();
        };
      });
    });
    const service = new RedisService(client as unknown as Redis);

    const first = service.ping();
    const second = service.ping();
    expect(client.connect).toHaveBeenCalledOnce();
    finishConnect();

    await expect(Promise.all([first, second])).resolves.toEqual([true, true]);
    expect(client.ping).toHaveBeenCalledTimes(2);
  });

  it("disconnects immediately when closing during reconnect", async () => {
    const client = redisClientStub();
    client.status = "reconnecting";
    const service = new RedisService(client as unknown as Redis);

    await service.close();

    expect(client.disconnect).toHaveBeenCalledWith(false);
    expect(client.quit).not.toHaveBeenCalled();
  });
});

describe("RedisService guest quota", () => {
  it("reserves and rolls back all counters with one Lua call each", async () => {
    const client = redisClientStub();
    client.status = "ready";
    client.eval.mockResolvedValueOnce([1]).mockResolvedValueOnce([1]);
    const service = new RedisService(client as unknown as Redis);
    const keys = ["guest-quota:ip", "guest-quota:ip-guest"];

    await expect(service.reserveDaily(keys, 20, 120)).resolves.toEqual({ accepted: true });
    await service.rollbackDaily(keys);

    expect(client.eval).toHaveBeenCalledTimes(2);
    expect(client.eval.mock.calls[0]?.slice(1)).toEqual([2, ...keys, "20", "120"]);
    expect(client.eval.mock.calls[1]?.slice(1)).toEqual([2, ...keys]);
  });
});

function redisClientStub() {
  return {
    status: "end",
    on: vi.fn().mockReturnThis(),
    connect: vi.fn<() => Promise<void>>(),
    ping: vi.fn().mockResolvedValue("PONG"),
    eval: vi.fn(),
    quit: vi.fn().mockResolvedValue("OK"),
    disconnect: vi.fn(),
  };
}
