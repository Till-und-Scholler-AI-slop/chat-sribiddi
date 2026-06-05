import { Kysely, PostgresDialect } from "kysely";
import pg from "pg";
import { env } from "../config/env.js";
import type { Database } from "./types.js";

const int8TypeId = 20;
pg.types.setTypeParser(int8TypeId, (value) => Number.parseInt(value, 10));

export function createDb(connectionString = env.databaseUrl) {
  const pool = new pg.Pool({
    connectionString,
    max: Number(process.env.HIKARI_MAX_POOL_SIZE ?? 20),
  });
  const db = new Kysely<Database>({
    dialect: new PostgresDialect({ pool }),
  });
  return { db, pool };
}

export type Db = Kysely<Database>;
