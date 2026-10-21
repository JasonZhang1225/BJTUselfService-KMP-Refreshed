import test from "node:test";
import assert from "node:assert/strict";
import { LIMITS } from "../src/protocol.js";
import { createSite, fixture, pairDevice, snapshotFor } from "./helpers.js";

test("拥有者接口在未配置或令牌错误时拒绝", async () => {
  const site = createSite();
  assert.equal((await site.call("GET", "/api/v1/owner/status")).status, 401);
  assert.equal((await site.call("GET", "/api/v1/owner/status", { token: "bjtud_wrongwrongwrongwrongwrong" })).status, 401);
  site.env.OWNER_TOKEN = undefined;
  const unconfigured = await site.call("GET", "/api/v1/owner/status", { owner: true });
  assert.equal(unconfigured.status, 503);
  assert.equal(unconfigured.body.error.code, "owner_auth_not_configured");
});

test("配对码一次性、会过期，失败过多锁定", async () => {
  const site = createSite();
  const code = (await site.call("POST", "/api/v1/owner/pairings", { owner: true })).body.pairingCode;
  const request = { ...fixture("pair-request-v1.json"), pairingCode: code };
  const first = await site.call("POST", "/api/v1/device/pair", { body: request });
  assert.equal(first.status, 200);
  assert.match(first.body.deviceToken, /^bjtud_/);
  const reused = await site.call("POST", "/api/v1/device/pair", { body: request });
  assert.equal(reused.status, 403);

  const late = (await site.call("POST", "/api/v1/owner/pairings", { owner: true })).body.pairingCode;
  site.clock.now += LIMITS.pairingTtlSeconds + 1;
  assert.equal((await site.call("POST", "/api/v1/device/pair", { body: { ...request, pairingCode: late } })).status, 403);

  for (let i = 0; i < 9; i += 1) {
    await site.call("POST", "/api/v1/device/pair", { body: { ...request, pairingCode: "ZZZZ2222" } });
  }
  const fresh = (await site.call("POST", "/api/v1/owner/pairings", { owner: true })).body.pairingCode;
  const locked = await site.call("POST", "/api/v1/device/pair", { body: { ...request, pairingCode: fresh } });
  assert.equal(locked.status, 429);
});

test("设备令牌只存摘要；协议版本不一致明确报错", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const rows = site.env.DB.raw.prepare("SELECT token_hash FROM devices").all();
  assert.equal(rows.length, 1);
  assert.notEqual(rows[0].token_hash, device.deviceToken);
  assert.ok(!JSON.stringify(rows).includes(device.deviceToken));
  const mismatch = await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken, headers: { "x-bjtu-protocol": "99" } });
  assert.equal(mismatch.status, 426);
  assert.equal(mismatch.body.error.code, "protocol_mismatch");
});

test("快照：幂等重传、旧序号拒收、范围外字段拒收", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const snapshot = snapshotFor(device);
  const stored = await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshot });
  assert.equal(stored.status, 200);
  assert.equal(stored.body.stored, true);
  const again = await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshot });
  assert.equal(again.body.duplicate, true);
  const stale = await site.call("PUT", "/api/v1/device/snapshot", {
    token: device.deviceToken, body: snapshotFor(device, { snapshotId: "snap_older00001", sequence: 41 }),
  });
  assert.equal(stale.status, 409);
  assert.equal(stale.body.error.code, "stale_snapshot");
  const leaking = await site.call("PUT", "/api/v1/device/snapshot", {
    token: device.deviceToken,
    body: snapshotFor(device, { snapshotId: "snap_newer00001", sequence: 43, data: { ...snapshot.data, grades: { items: [] } } }),
  });
  assert.equal(leaking.status, 400);
  assert.equal(leaking.body.error.code, "scope_violation");

  const read = await site.call("GET", "/api/v1/owner/snapshot", { owner: true });
  assert.equal(read.body.snapshot.sequence, 42);
  // 逐源同步时间原样保留，失败来源不被上传时间覆盖。
  assert.equal(read.body.snapshot.sources.homework.fetchedAt, 1792530000);
  assert.equal(read.body.snapshot.uploadedAt, 1792540805);
});

test("关闭上云范围后立即不可读", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshotFor(device) });
  await site.call("PUT", "/api/v1/device/scopes", { token: device.deviceToken, body: { scopes: ["schedule"] } });
  const read = await site.call("GET", "/api/v1/owner/snapshot", { owner: true });
  assert.deepEqual(Object.keys(read.body.snapshot.data), ["schedule"]);
  assert.deepEqual(read.body.snapshot.scopes, ["schedule"]);
});

test("任务：幂等创建、原子领取、续租、结果去重", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const request = { operation: "refresh_all", idempotencyKey: "idem_example0001", arguments: {} };
  const created = await site.call("POST", "/api/v1/owner/tasks", { owner: true, body: request });
  assert.equal(created.status, 202);
  assert.equal(created.body.status, "queued");
  const duplicate = await site.call("POST", "/api/v1/owner/tasks", { owner: true, body: request });
  assert.equal(duplicate.body.requestId, created.body.requestId);
  assert.equal(duplicate.body.created, false);

  const poll = await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
  const task = poll.body.task;
  assert.equal(task.requestId, created.body.requestId);
  assert.equal(task.attempt, 1);
  const empty = await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
  assert.equal(empty.body.task, null, "已领取的任务在租约内不会被再次领取");

  const renewed = await site.call("POST", `/api/v1/device/tasks/${task.requestId}/renew`, {
    token: device.deviceToken, body: { leaseId: task.leaseId, leaseSeconds: 300 },
  });
  assert.equal(renewed.status, 200);

  const result = { ...fixture("task-result-v1.json"), leaseId: task.leaseId };
  const submitted = await site.call("POST", `/api/v1/device/tasks/${task.requestId}/result`, { token: device.deviceToken, body: result });
  assert.equal(submitted.body.accepted, true);
  const resent = await site.call("POST", `/api/v1/device/tasks/${task.requestId}/result`, { token: device.deviceToken, body: result });
  assert.equal(resent.body.duplicate, true);

  const status = await site.call("GET", `/api/v1/owner/tasks/${task.requestId}`, { owner: true });
  assert.equal(status.body.status, "partial");
  assert.equal(status.body.result.data.snapshotSequence, 43);
});

test("租约过期后重新领取，旧执行者的结果被拒", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  await site.call("POST", "/api/v1/owner/tasks", { owner: true, body: { operation: "refresh_all", idempotencyKey: "idem_example0002" } });
  const first = (await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken })).body.task;
  site.clock.now += LIMITS.leaseSeconds + 1;
  const second = (await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken })).body.task;
  assert.equal(second.requestId, first.requestId);
  assert.notEqual(second.leaseId, first.leaseId);
  assert.equal(second.attempt, 2);
  const stale = await site.call("POST", `/api/v1/device/tasks/${first.requestId}/result`, {
    token: device.deviceToken, body: { ...fixture("task-result-v1.json"), leaseId: first.leaseId },
  });
  assert.equal(stale.status, 409);
  assert.equal(stale.body.error.code, "stale_lease");
  const fresh = await site.call("POST", `/api/v1/device/tasks/${second.requestId}/result`, {
    token: device.deviceToken, body: { ...fixture("task-result-v1.json"), leaseId: second.leaseId, status: "succeeded" },
  });
  assert.equal(fresh.status, 200);
});

test("未领取的任务 5 分钟后过期；结果 24 小时后清理；元数据 7 天后删除", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const queued = await site.call("POST", "/api/v1/owner/tasks", { owner: true, body: { operation: "refresh_all", idempotencyKey: "idem_example0003" } });
  site.clock.now += LIMITS.taskQueueTtlSeconds + 1;
  assert.equal((await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken })).body.task, null);
  assert.equal((await site.call("GET", `/api/v1/owner/tasks/${queued.body.requestId}`, { owner: true })).body.status, "expired");

  await site.call("POST", "/api/v1/owner/tasks", { owner: true, body: { operation: "refresh_all", idempotencyKey: "idem_example0004" } });
  const task = (await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken })).body.task;
  await site.call("POST", `/api/v1/device/tasks/${task.requestId}/result`, {
    token: device.deviceToken, body: { ...fixture("task-result-v1.json"), leaseId: task.leaseId },
  });
  site.clock.now += LIMITS.resultRetentionSeconds + 1;
  const cleared = await site.call("GET", `/api/v1/owner/tasks/${task.requestId}`, { owner: true });
  assert.equal(cleared.body.result, null);
  assert.equal(cleared.body.resultCleared, true);
  site.clock.now += LIMITS.taskRetentionSeconds;
  assert.equal((await site.call("GET", `/api/v1/owner/tasks/${task.requestId}`, { owner: true })).status, 404);
});

test("撤销设备：令牌失效、未完成任务作废；重新配对是新绑定代次", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const task = await site.call("POST", "/api/v1/owner/tasks", { owner: true, body: { operation: "refresh_all", idempotencyKey: "idem_example0005" } });
  await site.call("POST", `/api/v1/owner/devices/${device.deviceId}/revoke`, { owner: true });
  assert.equal((await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken })).status, 401);
  assert.equal((await site.call("GET", `/api/v1/owner/tasks/${task.body.requestId}`, { owner: true })).body.status, "revoked");

  const second = await pairDevice(site);
  assert.notEqual(second.deviceId, device.deviceId);
  // 新代次从序号 1 开始也能上传，旧设备的快照不会挡住它。
  const upload = await site.call("PUT", "/api/v1/device/snapshot", {
    token: second.deviceToken, body: snapshotFor(second, { snapshotId: "snap_gen2000001", sequence: 1 }),
  });
  assert.equal(upload.status, 200);
});

test("新配对自动撤销旧设备（首版一台活跃设备），旧设备不能读写", async () => {
  const site = createSite();
  const a = await pairDevice(site);
  const b = await pairDevice(site, { accountBindingId: "bind_exampleAccount02" });
  assert.equal((await site.call("PUT", "/api/v1/device/snapshot", { token: a.deviceToken, body: snapshotFor(a) })).status, 401);
  // 设备 B 不能冒充设备 A 上传。
  const spoof = await site.call("PUT", "/api/v1/device/snapshot", { token: b.deviceToken, body: snapshotFor(a) });
  assert.equal(spoof.status, 403);
  const status = await site.call("GET", "/api/v1/owner/status", { owner: true });
  assert.equal(status.body.devices.filter((d) => d.revokedAt === null).length, 1);
});

test("任务参数只接受白名单 DTO；成绩未授权时拒绝", async () => {
  const site = createSite();
  await pairDevice(site);
  const cases = [
    { operation: "shell", idempotencyKey: "idem_bad000001", arguments: { command: "rm -rf /" } },
    { operation: "query", idempotencyKey: "idem_bad000002", arguments: { url: "https://evil.example" } },
    { operation: "query", idempotencyKey: "idem_bad000003", arguments: { sources: ["../../etc"] } },
    { operation: "citel_problem", idempotencyKey: "idem_bad000004", arguments: {} },
  ];
  for (const body of cases) {
    const response = await site.call("POST", "/api/v1/owner/tasks", { owner: true, body });
    assert.equal(response.status, 400, JSON.stringify(body));
  }
  const grades = await site.call("POST", "/api/v1/owner/tasks", {
    owner: true, body: { operation: "query", idempotencyKey: "idem_bad000005", arguments: { freshness: "latest", sources: ["grades"] } },
  });
  assert.equal(grades.status, 403);
  assert.equal(grades.body.error.code, "scope_denied");
});

test("暂停查询与清除云端数据", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshotFor(device) });
  await site.call("PUT", "/api/v1/owner/queries", { owner: true, body: { paused: true } });
  assert.equal((await site.call("GET", "/api/v1/owner/snapshot", { owner: true })).status, 423);
  await site.call("PUT", "/api/v1/owner/queries", { owner: true, body: { paused: false } });
  await site.call("DELETE", "/api/v1/owner/data", { owner: true });
  assert.equal((await site.call("GET", "/api/v1/owner/snapshot", { owner: true })).body.snapshot, null);
});

test("心跳最多约每分钟写一次", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const seen = () => site.env.DB.raw.prepare("SELECT last_seen_at FROM devices WHERE device_id = ?").get(device.deviceId).last_seen_at;
  const start = seen();
  site.clock.now += 5;
  await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
  assert.equal(seen(), start);
  site.clock.now += LIMITS.heartbeatPersistSeconds;
  await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
  assert.equal(seen(), site.clock.now);
});

test("超大请求与非法 JSON 被拒，内部错误不回显", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const big = await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: "x".repeat(LIMITS.snapshotBytes + 1) });
  assert.equal(big.status, 413);
  const broken = await site.call("POST", "/api/v1/device/pair", { body: "{not json" });
  assert.equal(broken.body.error.code, "bad_json");
});
