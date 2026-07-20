import { Kysely, PostgresDialect } from "kysely";
import pg from "pg";
import { env } from "../config/env.js";
import type { Database } from "./types.js";
import { logger, safeError } from "../observability/logger.js";
import { registerDatabasePoolMetrics } from "../observability/metrics.js";

const int8TypeId = 20;
pg.types.setTypeParser(int8TypeId, (value) => Number.parseInt(value, 10));

/**
 * Build a node-postgres pool with sensible defaults plus optional
 * session-level timeouts. A non-zero `statement_timeout` defends against
 * runaway queries (a missing index, accidental cross-join) holding a pool
 * connection indefinitely; `idle_in_transaction_session_timeout` is the
 * companion guard for code that opens a transaction and forgets to
 * commit/rollback, which would otherwise pin a connection until the client
 * itself is recycled.
 *
 * Both are applied via the pool's `connectionConfig` `options` so they
 * only take effect when we own the connection lifecycle (we do — no
 * PgBouncer transaction-pooling is in front of this code path).
 */
export function createDb(connectionString = env.databaseUrl) {
  const statementTimeoutMs = env.POSTGRES_STATEMENT_TIMEOUT_MS;
  const idleTransactionTimeoutMs = env.POSTGRES_IDLE_TRANSACTION_TIMEOUT_MS;
  const sessionOptions: string[] = [];
  if (statementTimeoutMs > 0) {
    sessionOptions.push(`-c statement_timeout=${Math.floor(statementTimeoutMs)}`);
  }
  if (idleTransactionTimeoutMs > 0) {
    sessionOptions.push(
      `-c idle_in_transaction_session_timeout=${Math.floor(idleTransactionTimeoutMs)}`,
    );
  }

  const pool = new pg.Pool({
    connectionString,
    max: env.POSTGRES_POOL_MAX,
    idleTimeoutMillis: env.POSTGRES_IDLE_TIMEOUT_MS,
    connectionTimeoutMillis: env.POSTGRES_CONNECTION_TIMEOUT_MS,
    ...(sessionOptions.length > 0 ? { options: sessionOptions.join(" ") } : {}),
  });
  registerDatabasePoolMetrics(pool);
  pool.on("error", (error) => {
    logger.error({ error: safeError(error) }, "Unexpected idle PostgreSQL pool error");
  });
  const db = new Kysely<Database>({
    dialect: new PostgresDialect({ pool }),
  });
  return { db, pool };
}

export type Db = Kysely<Database>;
