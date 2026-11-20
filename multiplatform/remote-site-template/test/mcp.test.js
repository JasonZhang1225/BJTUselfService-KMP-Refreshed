import test from "node:test";
import assert from "node:assert/strict";
import { createSite, pairDevice, snapshotFor } from "./helpers.js";
import { querySnapshot } from "../src/query.js";

const rpc = (site, method, params) => site.call("POST", "/mcp", { owner: true, body: { jsonrpc: "2.0", id: 1, method, params } });
const call = async (site, name, args = {}) => {
  const response = await rpc(site, "tools/call", { name, arguments: args });
  return { ...response, value: JSON.parse(response.body.result.content[0].text) };
};

test("MCP 发现、工具及普通查询十次均不入队", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshotFor(device) });
  assert.equal((await rpc(site, "server/discover")).body.result.supportedVersions[0], "2026-07-28");
  const names = (await rpc(site, "tools/list")).body.result.tools.map(t => t.name);
  assert.deepEqual(names, ["get_status", "get_homework", "get_calendar", "get_exams", "get_grades", "get_mail",
    "request_live_query", "request_refresh_all", "request_citel_problem", "request_assignment_detail", "get_assignment_attachment", "request_mail_message", "get_request_result"]);
  for (let i = 0; i < 10; i++) {
    const read = await call(site, "get_homework", { status: "all" });
    assert.equal(read.body.result.isError, false);
    assert.equal(read.value.origin, "cloud_snapshot");
    assert.equal(read.value.command, "homework");
    assert.equal(read.value.items.length, 4);
    assert.deepEqual(read.value.summary.bySource.map(s => s.source), ["course_platform", "phyvlab", "citel"]);
    assert.equal(read.value.remote.device.name.length > 0, true);
  }
  const calendar = await call(site, "get_calendar", { date: "2026-10-25" });
  assert.equal(calendar.value.days[0].items[0].due, "2026-10-25（周日）24:00");
  const status = await call(site, "get_status");
  assert.equal(status.value.overview.summary.assignments.available, true);
  assert.equal(status.value.site.snapshot.sources.grades, undefined);
  assert.equal(site.env.DB.raw.prepare("SELECT COUNT(*) n FROM tasks").get().n, 0);
});

test("没有授权的范围返回 scope_denied；没有快照时说明原因", async () => {
  const site = createSite();
  await pairDevice(site);
  assert.equal((await call(site, "get_homework")).value.error.code, "no_snapshot");
  assert.equal((await call(site, "get_grades")).value.error.code, "scope_denied");
  assert.equal((await call(site, "get_mail")).value.error.code, "scope_denied");
});

test("MCP 明确最新需求才建精确任务；幂等重试不重复入队", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
  const args = { command: "homework", arguments: { sources: ["citel"] }, freshness: "latest", idempotencyKey: "latest_request01" };
  const created = await call(site, "request_live_query", args);
  assert.equal(created.value.status, "queued");
  assert.deepEqual(created.value.arguments, { command: "homework", freshness: "latest", sources: ["citel"] });
  assert.equal((await call(site, "request_live_query", args)).value.requestId, created.value.requestId);
  assert.equal((await call(site, "get_request_result", { requestId: created.value.requestId })).value.status, "queued");
  assert.equal((await call(site, "request_live_query", { ...args, command: "grades", arguments: {}, idempotencyKey: "latest_request02" })).value.error.code, "scope_denied");
  assert.equal((await call(site, "request_live_query", { ...args, arguments: { status: "done" }, idempotencyKey: "latest_request03" })).value.error.code, "bad_request");
  assert.equal((await call(site, "request_citel_problem", { taskId: "9265", idempotencyKey: "problem_request1" })).value.status, "queued");
  assert.equal((await call(site, "request_mail_message", { messageId: "1:abc", idempotencyKey: "mail_request001" })).value.error.code, "scope_denied");
});

test("MCP 拒绝未授权调用、未知字段、未知工具、非法日期", async () => {
  const site = createSite();
  assert.equal((await site.call("POST", "/mcp", { body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_status", arguments: {} } } })).status, 401);
  assert.equal((await call(site, "unknown")).value.error.code, "unknown_tool");
  assert.equal((await call(site, "get_status", { command: "anything" })).value.error.code, "bad_request");
  await pairDevice(site);
  assert.equal((await call(site, "get_calendar", { date: "2026-02-30" })).value.error.code, "bad_request");
  site.env.OWNER_USER_ID = "owner-site-scoped";
  assert.equal((await site.call("POST", "/mcp", { headers: { "oai-authenticated-user-id": "another-user" }, body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_status", arguments: {} } } })).status, 401);
  assert.equal((await site.call("POST", "/mcp", { headers: { "oai-authenticated-user-id": "owner-site-scoped" }, body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_status", arguments: {} } } })).status, 200);
});

test("云端查询只读已授权范围；旧版快照提示更新", () => {
  const data = { assignments: { sources: [], items: [] }, grades: { items: [{ course: "示例", score: "90", credits: "1", year: "2025-2026", semester: "第一学期" }] } };
  const payload = { device: { scopes: ["assignments"] }, snapshot: { schemaVersion: 2, scopes: ["assignments"], sources: {}, data } };
  assert.throws(() => querySnapshot(payload, "grades", {}, 1792593000), /授权/);
  assert.equal(querySnapshot(payload, "homework", {}, 1792593000).summary.available, true);
  const old = { ...payload, snapshot: { ...payload.snapshot, schemaVersion: 1 } };
  assert.equal(querySnapshot(old, "homework", {}, 1792593000).error.code, "schema_mismatch");
});

test("过期租约不能提交，关闭范围后旧结果不能继续读取", async () => {
  const site = createSite();
  const device = await pairDevice(site, { scopes: ["schedule", "grades"] });
  await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
  const task = await call(site, "request_live_query", { command: "grades", freshness: "local", idempotencyKey: "grade_request01" });
  const leased = await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
  site.clock.now += 121;
  const result = { leaseId: leased.body.task.leaseId, status: "succeeded", result: { score: "fictional" } };
  assert.equal((await site.call("POST", `/api/v1/device/tasks/${task.value.requestId}/result`, { token: device.deviceToken, body: result })).status, 409);
  await site.call("PUT", "/api/v1/device/scopes", { token: device.deviceToken, body: { scopes: ["schedule"] } });
  assert.equal((await call(site, "get_request_result", { requestId: task.value.requestId })).value.error.code, "scope_denied");
});

test("手动同步仍可读，未轮询拒绝即时任务，轮询停止后提示停止等待", async () => {
  const site = createSite();
  const device = await pairDevice(site);
  const args = { taskId: "42", idempotencyKey: "polling_example" };
  await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshotFor(device) });

  const before = (await call(site, "get_status")).value.site;
  assert.equal(before.taskPollingAvailable, false);
  assert.deepEqual(before.devices[0].polling, { state: "never", lastPollAt: null, intervalSeconds: null });
  assert.equal((await call(site, "request_citel_problem", args)).value.error.code, "device_not_polling");
  assert.equal((await call(site, "get_homework", { detail: true })).value.summary.available, true);
  assert.equal(site.env.DB.raw.prepare("SELECT COUNT(*) n FROM tasks").get().n, 0);

  site.clock.now += 5;
  const poll = await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken, body: { pollIntervalSeconds: 5 } });
  assert.equal(poll.body.task, null);
  const active = (await call(site, "get_status")).value.site;
  assert.deepEqual(active.devices[0].polling, { state: "active", lastPollAt: site.clock.now, intervalSeconds: 5 });
  assert.equal(active.taskPollingAvailable, true);

  const queued = (await call(site, "request_citel_problem", args)).value;
  assert.equal(queued.waitForResult, true);
  site.clock.now += 36;
  const stopped = (await call(site, "get_request_result", { requestId: queued.requestId })).value;
  assert.equal(stopped.polling.state, "stale");
  assert.equal(stopped.waitForResult, false);
  assert.equal((await call(site, "request_citel_problem", args)).value.requestId, queued.requestId);
  assert.equal((await call(site, "request_citel_problem", { ...args, idempotencyKey: "new_after_stop" })).value.error.code, "device_not_polling");
});

test("即使另一台电脑刚上传快照，MCP 也派给仍在轮询的电脑", async () => {
  const site = createSite();
  const poller = await pairDevice(site, { deviceName: "轮询电脑" });
  await site.call("POST", "/api/v1/device/poll", { token: poller.deviceToken, body: { pollIntervalSeconds: 5 } });
  site.clock.now += 6;
  const uploader = await pairDevice(site, { deviceName: "手动上传电脑", accountBindingId: "bind_exampleUploader" });
  await site.call("PUT", "/api/v1/device/snapshot", { token: uploader.deviceToken, body: snapshotFor(uploader) });

  const args = { taskId: "42", idempotencyKey: "poller_selection" };
  const responses = await Promise.all([call(site, "request_citel_problem", args), call(site, "request_citel_problem", args)]);
  const [first, second] = responses.map(r => r.value);
  assert.equal(first.deviceId, poller.deviceId);
  assert.equal(first.requestId, second.requestId);
  assert.equal(site.env.DB.raw.prepare("SELECT COUNT(*) n FROM tasks").get().n, 1);
  const lease = await site.call("POST", "/api/v1/device/poll", { token: poller.deviceToken, body: { pollIntervalSeconds: 5 } });
  assert.equal(lease.body.task.requestId, first.requestId);

  site.clock.now += 36;
  await site.call("POST", "/api/v1/device/poll", { token: uploader.deviceToken, body: { pollIntervalSeconds: 5 } });
  const retry = (await call(site, "request_citel_problem", args)).value;
  assert.equal(retry.requestId, first.requestId);
  assert.equal(retry.polling.state, "stale");
  assert.equal(retry.waitForResult, true, "仍有有效的执行租约");
});
