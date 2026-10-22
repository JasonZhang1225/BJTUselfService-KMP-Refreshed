// 测试辅助：把 node:sqlite 包成 D1 形状，执行真实迁移 SQL。
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { handle } from "../src/index.js";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

export function fixture(name) {
  return JSON.parse(readFileSync(join(root, "fixtures", name), "utf8"));
}

export function createD1(filename = ":memory:") {
  const db = new DatabaseSync(filename);
  db.exec("PRAGMA foreign_keys = ON");
  // 与 D1/Drizzle 一样只执行尚未应用的迁移：重新打开同一数据库时不重复 ALTER。
  db.exec("CREATE TABLE IF NOT EXISTS _test_migrations (name TEXT PRIMARY KEY)");
  for (const file of readdirSync(join(root, "migrations")).sort()) {
    if (db.prepare("SELECT 1 FROM _test_migrations WHERE name = ?").get(file)) continue;
    db.exec(readFileSync(join(root, "migrations", file), "utf8"));
    db.prepare("INSERT INTO _test_migrations (name) VALUES (?)").run(file);
  }
  const statement = (sql, args = []) => ({
    bind: (...next) => statement(sql, next),
    first: async () => db.prepare(sql).get(...args) ?? null,
    all: async () => ({ results: db.prepare(sql).all(...args) }),
    run: async () => ({ meta: { changes: Number(db.prepare(sql).run(...args).changes) } }),
  });
  return {
    raw: db,
    prepare: (sql) => statement(sql),
    async batch(statements) {
      db.exec("BEGIN");
      try {
        const results = [];
        for (const s of statements) results.push(await s.run());
        db.exec("COMMIT");
        return results;
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    },
  };
}

export const OWNER_TOKEN = "owner-token-for-tests-only-0123456789";

/** 一个虚构的 Site 实例：独立数据库、可控时钟。 */
export function createSite() {
  const env = { DB: createD1(), OWNER_TOKEN };
  const clock = { now: 1_792_540_800 };
  const call = async (method, path, { body, token, owner = false, headers = {} } = {}) => {
    const init = { method, headers: { ...headers } };
    if (body !== undefined) {
      init.body = typeof body === "string" ? body : JSON.stringify(body);
      init.headers["content-type"] = "application/json";
    }
    if (owner) init.headers.authorization = `Bearer ${OWNER_TOKEN}`;
    if (token) init.headers.authorization = `Bearer ${token}`;
    const response = await handle(new Request(`https://site.example${path}`, init), env, { now: () => clock.now });
    return { status: response.status, body: await response.json() };
  };
  return { env, clock, call };
}

/** 生成配对码并绑定一台虚构设备。 */
export async function pairDevice(site, overrides = {}) {
  const code = await site.call("POST", "/api/v1/owner/pairings", { owner: true });
  const request = { ...fixture("pair-request-v1.json"), pairingCode: code.body.pairingCode, ...overrides };
  const paired = await site.call("POST", "/api/v1/device/pair", { body: request });
  return paired.body;
}

export function snapshotFor(device, overrides = {}) {
  return {
    ...fixture("snapshot-v2.json"),
    deviceId: device.deviceId,
    accountBindingId: device.accountBindingId,
    ...overrides,
  };
}
