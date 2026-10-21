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
  assert.equal((await rpc(site, "tools/list")).body.result.tools.length, 6);
  for (let i = 0; i < 10; i++) {
    const read = await call(site, "query_snapshot", { course: "示例课程甲" });
    assert.equal(read.body.result.isError, false);
    assert.equal(read.value.freshness, "snapshot");
    assert.equal(read.value.snapshot.data.assignments.items.length, 1);
    assert.equal(read.value.snapshot.sources.grades, undefined);
  }
  assert.equal(site.env.DB.raw.prepare("SELECT COUNT(*) n FROM tasks").get().n, 0);
});

test("MCP 明确最新需求才建精确任务；幂等重试不重复入队", async () => {
  const site = createSite();
  await pairDevice(site);
  const args = { sources: ["citel"], freshness: "latest", idempotencyKey: "latest_request01" };
  const created = await call(site, "request_live_query", args);
  assert.equal(created.value.status, "queued");
  assert.deepEqual(created.value.arguments, { sources: ["citel"], freshness: "latest" });
  assert.equal((await call(site, "request_live_query", args)).value.requestId, created.value.requestId);
  assert.equal((await call(site, "get_request_result", { requestId: created.value.requestId })).value.status, "queued");
  assert.equal((await call(site, "request_live_query", { ...args, sources: ["grades"] })).value.error.code, "scope_denied");
  assert.equal((await call(site, "request_citel_problem", { taskId: "9265", idempotencyKey: "problem_request1" })).value.status, "queued");
});

test("MCP 拒绝未授权调用、未知字段、未知工具、非法日期", async () => {
  const site = createSite();
  assert.equal((await site.call("POST", "/mcp", { body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_status", arguments: {} } } })).status, 401);
  assert.equal((await call(site, "unknown")).value.error.code, "unknown_tool");
  assert.equal((await call(site, "get_status", { command: "anything" })).value.error.code, "bad_request");
  assert.equal((await call(site, "query_snapshot", { date: "2026-02-30" })).value.error.code, "bad_request");
  site.env.OWNER_USER_ID = "owner-site-scoped";
  assert.equal((await site.call("POST", "/mcp", { headers: { "oai-authenticated-user-id": "another-user" }, body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_status", arguments: {} } } })).status, 401);
  assert.equal((await site.call("POST", "/mcp", { headers: { "oai-authenticated-user-id": "owner-site-scoped" }, body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_status", arguments: {} } } })).status, 200);
});

test("查询沿用真实 CLI 嵌套结构与午夜归日，未匹配项被排除", () => {
  const payload = { device: { scopes: ["assignments"] }, snapshot: { scopes: ["assignments"], sources: {}, data: {
    assignments: { homework: { items: [{ source: "citel", id: 9265, courseName: "示例", dueTime: "2026-10-23T00:00:00+08:00", dueAgendaDate: "2026-10-22", midnightDeadline: true }] }, citel: { tasks: [{ id: 9265, courseName: "示例", dueTime: "2026-10-23T00:00:00+08:00" }] } },
  } } };
  const result = querySnapshot(payload, { date: "2026-10-22", sources: ["citel"] });
  assert.equal(result.snapshot.data.assignments.homework.items.length, 1);
  assert.equal(result.snapshot.data.assignments.homework.items[0].midnightDeadline, true);
  assert.equal(querySnapshot(payload, { course: "其他" }).snapshot.data.assignments.homework.items.length, 0);
});

test("过期租约不能提交，关闭范围后旧结果不能继续读取", async () => {
  const site = createSite();
  const device = await pairDevice(site, { scopes: ["schedule", "grades"] });
  const task = await call(site, "request_live_query", { sources: ["grades"], freshness: "local", idempotencyKey: "grade_request01" });
  const leased = await site.call("POST", "/api/v1/device/poll", { token: device.deviceToken });
  site.clock.now += 121;
  const result = { leaseId: leased.body.task.leaseId, status: "succeeded", result: { score: "fictional" } };
  assert.equal((await site.call("POST", `/api/v1/device/tasks/${task.value.requestId}/result`, { token: device.deviceToken, body: result })).status, 409);
  await site.call("PUT", "/api/v1/device/scopes", { token: device.deviceToken, body: { scopes: ["schedule"] } });
  assert.equal((await call(site, "get_request_result", { requestId: task.value.requestId })).value.error.code, "scope_denied");
});
