import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { Client } from "pg";

export async function applyFlywaySqlMigrations(connectionString: string) {
  const migrationsDir = join(process.cwd(), "src/main/resources/db/migration");
  const files = readdirSync(migrationsDir)
    .filter((file) => /^V\d+__.+\.sql$/.test(file))
    .sort((left, right) => version(left) - version(right));

  const client = new Client({ connectionString });
  await client.connect();
  try {
    for (const file of files) {
      const sql = readFileSync(join(migrationsDir, file), "utf8");
      await client.query(sql);
    }
  } finally {
    await client.end();
  }
}

function version(file: string) {
  return Number(file.match(/^V(\d+)__/)?.[1] ?? "0");
}
