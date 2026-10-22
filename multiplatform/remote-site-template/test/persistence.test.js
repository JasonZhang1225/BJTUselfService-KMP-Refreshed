import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, unlinkSync, rmdirSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createSite, createD1, pairDevice, snapshotFor } from "./helpers.js";

test("重建服务对象并重新打开数据库后，绑定、快照、任务与租约仍存在", async () => {
  const directory = mkdtempSync(join(tmpdir(), "bjtuss-site-restart-"));
  const file = join(directory, "fixture.sqlite");
  let db;
  try {
    const first = createSite();
    first.env.DB.raw.close();
    first.env.DB = db = createD1(file);
    const device = await pairDevice(first);
    await first.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshotFor(device) });
    const queued = await first.call("POST", "/api/v1/owner/tasks", { owner: true,
      body: { operation: "query", idempotencyKey: "restart_test001", arguments: { command: "calendar", freshness: "local" } } });
    const leased = await first.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
    db.raw.close(); db = null;

    const restarted = createSite();
    restarted.env.DB.raw.close();
    restarted.env.DB = db = createD1(file);
    const read = await restarted.call("GET", "/api/v1/owner/snapshot", { owner: true });
    assert.equal(read.body.snapshot.sequence, 42);
    assert.equal(read.body.device.deviceId, device.deviceId);
    const task = await restarted.call("GET", `/api/v1/owner/tasks/${queued.body.requestId}`, { owner: true });
    assert.equal(task.body.status, "running");
    assert.equal((await restarted.call("POST", "/api/v1/device/poll", { token: device.deviceToken })).body.task, null);
    assert.equal((await restarted.call("POST", `/api/v1/device/tasks/${queued.body.requestId}/result`, {
      token: device.deviceToken, body: { leaseId: leased.body.task.leaseId, status: "succeeded", result: { items: [] } },
    })).status, 200);
  } finally { db?.raw.close(); unlinkSync(file); rmdirSync(directory); }
});

test("两个独立 Site 的设备令牌、平台身份和快照互相隔离", async () => {
  const a = createSite();
  const b = createSite();
  const da = await pairDevice(a);
  const db = await pairDevice(b);
  await a.call("PUT", "/api/v1/device/snapshot", { token: da.deviceToken, body: snapshotFor(da) });
  assert.equal((await b.call("POST", "/api/v1/device/poll", { token: da.deviceToken })).status, 401);
  assert.equal((await a.call("POST", "/api/v1/device/poll", { token: db.deviceToken })).status, 401);
  a.env.OWNER_USER_ID = "site-a-owner"; a.env.OWNER_TOKEN = undefined;
  b.env.OWNER_USER_ID = "site-b-owner"; b.env.OWNER_TOKEN = undefined;
  assert.equal((await b.call("GET", "/api/v1/owner/snapshot", { headers: { "oai-authenticated-user-id": "site-a-owner" } })).status, 401);
  const own = await b.call("GET", "/api/v1/owner/snapshot", { headers: { "oai-authenticated-user-id": "site-b-owner" } });
  assert.equal(own.status, 200);
  assert.equal(own.body.snapshot, null);
  assert.equal((await a.call("GET", "/api/v1/owner/snapshot", { headers: { "oai-authenticated-user-id": "site-a-owner" } })).body.snapshot.sequence, 42);
});
