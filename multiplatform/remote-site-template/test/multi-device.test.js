import test from "node:test";
import assert from "node:assert/strict";
import { createSite, pairDevice, snapshotFor } from "./helpers.js";

const rpc = (site, name, args = {}) => site.call("POST", "/mcp", { owner: true, body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } } })
  .then((r) => JSON.parse(r.body.result.content[0].text));

function withHomework(snapshot, title, fetchedAt) {
  const items = snapshot.data.assignments.items.map((item, i) => (i === 0 ? { ...item, title } : item));
  return { ...snapshot, sources: { ...snapshot.sources, homework: { status: "success", fetchedAt } },
    data: { ...snapshot.data, assignments: { ...snapshot.data.assignments, items } } };
}

test("电脑与手机同时绑定：手机配对不撤销电脑，查询取同步更新的那份", async () => {
  const site = createSite();
  const desktop = await pairDevice(site, { deviceName: "示例电脑（Windows）" });
  const phone = await pairDevice(site, { deviceName: "示例手机（Android）", deviceKind: "mobile", accountBindingId: "bind_examplePhone01" });
  const status = await site.call("GET", "/api/v1/owner/status", { owner: true });
  assert.equal(status.body.devices.filter((d) => d.revokedAt === null).length, 2);
  assert.equal(status.body.taskDeviceId, desktop.deviceId, "即时任务只派给电脑");

  await site.call("PUT", "/api/v1/device/snapshot", { token: desktop.deviceToken, body: withHomework(snapshotFor(desktop), "电脑上的旧作业", 1792530000) });
  site.clock.now += 60;
  await site.call("PUT", "/api/v1/device/snapshot", { token: phone.deviceToken,
    body: withHomework(snapshotFor(phone, { snapshotId: "snap_phone000001", sequence: 50 }), "手机刚刷新的作业", 1792540900) });
  const homework = await rpc(site, "get_homework");
  assert.ok(homework.items.some((i) => i.title === "手机刚刷新的作业"));
  assert.ok(!homework.items.some((i) => i.title === "电脑上的旧作业"));
  assert.equal(homework.remote.dataFrom.assignments, "示例手机（Android）");

  const task = await rpc(site, "request_live_query", { command: "homework", freshness: "local", idempotencyKey: "multi_device_01" });
  assert.equal(task.deviceId, desktop.deviceId);
  assert.equal((await site.call("POST", "/api/v1/device/poll", { token: phone.deviceToken })).body.task, null, "手机不领取任务");
  assert.equal((await site.call("POST", "/api/v1/device/poll", { token: desktop.deviceToken })).body.task.requestId, task.requestId);
});

test("同一台设备重新配对只替换自己的旧绑定；只有手机时不能建即时任务", async () => {
  const site = createSite();
  const phone = await pairDevice(site, { deviceName: "示例手机（iOS）", deviceKind: "mobile" });
  assert.equal((await rpc(site, "request_refresh_all", { idempotencyKey: "phone_only_0001" })).error.code, "no_device");
  const again = await pairDevice(site, { deviceName: "示例手机（iOS）", deviceKind: "mobile" });
  assert.equal((await site.call("POST", "/api/v1/device/poll", { token: phone.deviceToken })).status, 401);
  assert.equal((await site.call("POST", "/api/v1/device/poll", { token: again.deviceToken })).status, 200);
  const bad = await site.call("POST", "/api/v1/owner/pairings", { owner: true });
  const rejected = await site.call("POST", "/api/v1/device/pair", { body: { protocolVersion: 1, pairingCode: bad.body.pairingCode,
    deviceName: "x", accountBindingId: "bind_exampleBad001", scopes: ["schedule"], deviceKind: "tablet" } });
  assert.equal(rejected.status, 400);
});

for (const platform of ['iOS', 'Android']) {
  test(`${platform} 使用网页完整长码配对并上传，服务端保留来源且不分配轮询任务`, async t => {
    const site = createSite(); t.after(() => site.env.DB.raw.close());
    site.env.SITE_DEVICE_ACCESS = 'fixture-platform-access';
    const desktop = await pairDevice(site, { deviceName: 'fixture desktop' });
    const generated = await site.call('POST', '/api/v1/owner/connection', { owner: true });
    assert.equal(generated.status, 200);
    const info = JSON.parse(generated.body.connectionCode.slice('bjtuss-pair-v1:'.length));
    assert.equal(info.siteUrl, 'https://site.example');
    assert.equal(info.platformAccess, site.env.SITE_DEVICE_ACCESS);
    const response = await site.call('POST', '/api/v1/device/pair', { body: {
      protocolVersion: 1, pairingCode: info.pairingCode, deviceName: `fixture ${platform}`,
      deviceKind: 'mobile', accountBindingId: 'bind_fixturePhone01', scopes: ['schedule', 'assignments', 'exams'],
    } });
    assert.equal(response.status, 200);
    const phone = response.body;
    assert.equal(phone.deviceKind, 'mobile');
    const upload = await site.call('PUT', '/api/v1/device/snapshot', { token: phone.deviceToken, body: snapshotFor(phone) });
    assert.equal(upload.status, 200);
    const status = (await site.call('GET', '/api/v1/owner/status', { owner: true })).body;
    const registered = status.devices.find(d => d.deviceId === phone.deviceId);
    assert.equal(registered.deviceName, `fixture ${platform}`);
    assert.equal(registered.deviceKind, 'mobile');
    assert.equal(status.taskDeviceId, desktop.deviceId);
    const task = await rpc(site, 'request_refresh_all', { idempotencyKey: 'long_code_refresh01' });
    assert.equal(task.deviceId, desktop.deviceId);
    assert.equal((await site.call('POST', '/api/v1/device/poll', { token: phone.deviceToken })).body.task, null);
    assert.equal((await site.call('POST', '/api/v1/device/poll', { token: desktop.deviceToken })).body.task.requestId, task.requestId);
  });
}
