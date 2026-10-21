import test from "node:test";
import assert from "node:assert/strict";
import { createSite, pairDevice, snapshotFor } from "./helpers.js";

test("关闭范围后旧上传和旧权限回执不能重新打开范围", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const full = snapshotFor(device, { scopesRevision: 1 });
  assert.equal((await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: full })).status, 200);
  const scopeRequest = body => site.call("PUT", "/api/v1/device/scopes", { token: device.deviceToken, body });
  assert.equal((await scopeRequest({ scopes: ["schedule"], scopesRevision: 3 })).status, 200);
  const delayed = await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken,
    body: { ...full, snapshotId: "snap_delayed0001", sequence: 44 } });
  assert.equal(delayed.status, 409);
  assert.equal(delayed.body.error.code, "scopes_changed");
  assert.equal((await scopeRequest({ scopes: full.scopes, scopesRevision: 2 })).status, 409);
  assert.equal((await scopeRequest({ scopes: ["schedule"], scopesRevision: 3 })).status, 200, "同一版本同一范围重传幂等");
  assert.equal((await scopeRequest({ scopes: full.scopes, scopesRevision: 3 })).status, 409, "同一版本不能对应两个范围");
  const read = await site.call("GET", "/api/v1/owner/snapshot", { owner: true });
  assert.deepEqual(read.body.device.scopes, ["schedule"]);
  assert.deepEqual(Object.keys(read.body.snapshot.data), ["schedule"]);
});

test("旧客户端快照不能隐式扩大授权，新的明确范围版本可以恢复授权", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  await site.call("PUT", "/api/v1/device/scopes", { token: device.deviceToken, body: { scopes: ["schedule"], scopesRevision: 2 } });
  const legacy = snapshotFor(device);
  delete legacy.scopesRevision;
  assert.equal((await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: legacy })).status, 409);
  assert.equal((await site.call("PUT", "/api/v1/device/scopes", { token: device.deviceToken, body: { scopes: legacy.scopes } })).status, 409);
  await site.call("PUT", "/api/v1/device/scopes", { token: device.deviceToken,
    body: { scopes: ["schedule", "exams", "assignments"], scopesRevision: 3 } });
  assert.equal((await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken,
    body: snapshotFor(device, { scopesRevision: 3 }) })).status, 200);
});

test("范围撤销后，旧快照中该范围的数据立即不可读", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshotFor(device) });
  await site.call("PUT", "/api/v1/device/scopes", { token: device.deviceToken, body: { scopes: ["schedule"], scopesRevision: 2 } });
  const result = await site.call("GET", "/api/v1/owner/snapshot", { owner: true });
  assert.equal(result.status, 200);
  assert.equal(JSON.stringify(result.body).includes("实验 3 链表"), false);
  assert.deepEqual(Object.keys(result.body.snapshot.data), ["schedule"]);
});
