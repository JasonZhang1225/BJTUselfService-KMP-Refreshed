import test from "node:test";
import assert from "node:assert/strict";
import { createSite } from "./helpers.js";

test("私有部署的首次拥有者身份固定入库；机器访问和之后的其他身份不能冒领", async () => {
  const site = createSite();
  site.env.OWNER_TOKEN = undefined;
  site.env.OWNER_PRIVATE_BOOTSTRAP = "true";
  assert.equal((await site.call("GET", "/api/v1/owner/status")).status, 503);
  assert.equal(site.env.DB.raw.prepare("SELECT value FROM template_meta WHERE key = 'owner_user_id'").get(), undefined);
  const headers = { "oai-authenticated-user-id": "first-site-owner" };
  assert.equal((await site.call("GET", "/api/v1/owner/status", { headers })).status, 200);
  assert.equal((await site.call("GET", "/api/v1/owner/status", { headers: { "oai-authenticated-user-id": "another-user" } })).status, 401);
  assert.equal((await site.call("GET", "/api/v1/owner/status", { headers })).status, 200);
});

test("未显式开启私有部署引导时，任意身份头不能初始化拥有者；静态工具发现不读取业务数据", async () => {
  const site = createSite();
  site.env.OWNER_TOKEN = undefined;
  assert.equal((await site.call("GET", "/api/v1/owner/status", { headers: { "oai-authenticated-user-id": "untrusted-user" } })).status, 503);
  const list = await site.call("POST", "/mcp", { body: { jsonrpc: "2.0", id: 1, method: "tools/list" } });
  assert.equal(list.status, 200);
  assert.equal(list.body.result.tools.length, 11);
  assert.equal(JSON.stringify(list.body).includes("devices"), false);
});
