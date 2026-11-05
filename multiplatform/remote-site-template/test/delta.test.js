import test from "node:test";
import assert from "node:assert/strict";
import { LIMITS } from "../src/protocol.js";
import { applySnapshotPatch } from "../src/core.js";
import { createSite, fixture, pairDevice, snapshotFor } from "./helpers.js";

const assignment = (id, title) => ({ id, source: "phyvlab", title, submitted: false });
const base = (device) => snapshotFor(device, {
  snapshotId: "snap_base_000001", sequence: 10, scopes: ["schedule", "assignments"],
  data: { schedule: { days: 1 }, assignments: { sources: [], items: [assignment("phyvlab:1", "一"), assignment("phyvlab:2", "二")] } },
});
const patchFor = (snapshot, overrides = {}) => {
  const { data, ...envelope } = snapshot;
  return { ...envelope, snapshotId: "snap_next_000002", sequence: 11, baseSnapshotId: snapshot.snapshotId, baseSequence: snapshot.sequence, ...overrides };
};

async function setup() {
  const site = createSite();
  const device = await pairDevice(site, { scopes: ["schedule", "assignments"] });
  const snapshot = base(device);
  const put = await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshot });
  assert.equal(put.status, 200);
  return { site, device, snapshot, token: device.deviceToken };
}

const stored = async (site) => (await site.call("GET", "/api/v1/owner/snapshot", { owner: true })).body.snapshot;

test("差异只改变动的作业，其余沿用基线", async () => {
  const { site, snapshot, token } = await setup();
  const patch = patchFor(snapshot, { items: { assignments: { upsert: [assignment("phyvlab:3", "三"), assignment("phyvlab:1", "一改")], order: ["phyvlab:3", "phyvlab:1"] } } });
  const result = await site.call("PATCH", "/api/v1/device/snapshot", { token, body: patch });
  assert.equal(result.status, 200);
  assert.equal(result.body.stored, true);
  const data = (await stored(site)).data;
  assert.deepEqual(data.assignments.items.map((i) => i.title), ["三", "一改"], "phyvlab:2 不在 order 里即删除");
  assert.deepEqual(data.schedule, { days: 1 }, "没变的范围保留");
});

test("基线不是当前快照时拒绝，设备改传全量", async () => {
  const { site, snapshot, token } = await setup();
  const stale = await site.call("PATCH", "/api/v1/device/snapshot", { token, body: patchFor(snapshot, { baseSnapshotId: "snap_other_00001" }) });
  assert.equal(stale.status, 409);
  assert.equal(stale.body.error.code, "base_mismatch");
  const unknown = await site.call("PATCH", "/api/v1/device/snapshot", { token, body: patchFor(snapshot, { items: { assignments: { upsert: [], order: ["phyvlab:9"] } } }) });
  assert.equal(unknown.body.error.code, "base_mismatch");
});

test("重传同一差异幂等", async () => {
  const { site, snapshot, token } = await setup();
  const patch = patchFor(snapshot);
  assert.equal((await site.call("PATCH", "/api/v1/device/snapshot", { token, body: patch })).body.stored, true);
  const again = await site.call("PATCH", "/api/v1/device/snapshot", { token, body: patch });
  assert.equal(again.status, 200);
  assert.equal(again.body.duplicate, true);
});

test("差异只作用于本设备，不影响其他设备的快照", async () => {
  const { site, device, snapshot, token } = await setup();
  const other = await pairDevice(site, { deviceName: "手机", deviceKind: "mobile", scopes: ["assignments"] });
  const otherSnapshot = snapshotFor(other, { snapshotId: "snap_phone_00001", sequence: 5, scopes: ["assignments"], data: { assignments: { sources: [], items: [assignment("phyvlab:7", "手机")] } } });
  await site.call("PUT", "/api/v1/device/snapshot", { token: other.deviceToken, body: otherSnapshot });
  await site.call("PATCH", "/api/v1/device/snapshot", { token, body: patchFor(snapshot, { set: { schedule: { days: 2 } } }) });
  const row = site.env.DB.raw.prepare("SELECT body_json FROM snapshots WHERE device_id = ?").get(other.deviceId);
  assert.deepEqual(JSON.parse(row.body_json).data, otherSnapshot.data);
  assert.notEqual(device.deviceId, other.deviceId);
});

test("差异不能带入未授权范围", async () => {
  const { site, snapshot, token } = await setup();
  const result = await site.call("PATCH", "/api/v1/device/snapshot", { token, body: patchFor(snapshot, { set: { grades: { items: [] } } }) });
  assert.equal(result.status, 400);
  assert.equal(result.body.error.code, "scope_violation");
});

test("applySnapshotPatch 删除范围并去掉差异专用字段", () => {
  const merged = applySnapshotPatch({ data: { a: 1, b: 2 } }, { snapshotId: "s", baseSnapshotId: "x", baseSequence: 1, remove: ["a"], set: { c: 3 } });
  assert.deepEqual(merged, { snapshotId: "s", data: { b: 2, c: 3 } });
});

const meta = (overrides = {}) => ({ key: "att_0123456789abcdef", assignmentId: "phyvlab:1", attachmentId: "501", name: "模板.docx",
  contentType: "application/octet-stream", sizeBytes: LIMITS.attachmentChunkBytes + 3, chunkCount: 2, ...overrides });
const path = (key = "att_0123456789abcdef") => `/api/v1/device/attachments/${key}`;

async function upload(site, token) {
  await site.call("PUT", path(), { token, body: meta() });
  await site.call("PUT", `${path()}/chunks/0`, { token, body: new Uint8Array(LIMITS.attachmentChunkBytes).fill(7) });
}

const rpc = async (site, args) => (await site.call("POST", "/mcp", { owner: true, body: { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_assignment_attachment", arguments: args } } })).body.result;

test("附件分块上传，传完才算完整，可经 MCP 读取", async () => {
  const { site, token } = await setup();
  await upload(site, token);
  assert.deepEqual((await site.call("GET", "/api/v1/device/attachments", { token })).body.items, [{ key: meta().key, sizeBytes: meta().sizeBytes, complete: false }]);
  assert.equal((await rpc(site, { assignmentId: "phyvlab:1", attachmentId: "501" })).isError, true, "没传完不可读");
  await site.call("PUT", `${path()}/chunks/1`, { token, body: new Uint8Array([1, 2, 3]) });
  assert.equal((await site.call("GET", "/api/v1/device/attachments", { token })).body.items[0].complete, true);
  const read = await rpc(site, { assignmentId: "phyvlab:1", attachmentId: "501" });
  assert.equal(read.isError, false);
  assert.equal(JSON.parse(read.content[0].text).name, "模板.docx");
  const bytes = Buffer.from(read.content[1].resource.blob, "base64");
  assert.equal(bytes.length, meta().sizeBytes);
  assert.deepEqual([...bytes.subarray(-3)], [1, 2, 3]);
});

test("超过 5 MB、分块数不符或超额的附件被拒绝", async () => {
  const { site, token } = await setup();
  const size = LIMITS.attachmentBytes + 1;
  assert.equal((await site.call("PUT", path(), { token, body: meta({ sizeBytes: size, chunkCount: 6 }) })).status, 400);
  assert.equal((await site.call("PUT", path(), { token, body: meta({ chunkCount: 3 }) })).status, 400);
  for (let i = 0; i < 12; i += 1) {
    const key = `att_quota_${String(i).padStart(4, "0")}`;
    const result = await site.call("PUT", path(key), { token, body: meta({ key, sizeBytes: LIMITS.attachmentBytes, chunkCount: 5, attachmentId: `q${i}` }) });
    assert.equal(result.status, i < 12 ? 200 : 413);
  }
  const key = "att_quota_over";
  const over = await site.call("PUT", path(key), { token, body: meta({ key, sizeBytes: LIMITS.attachmentBytes, chunkCount: 5 }) });
  assert.equal(over.status, 413);
  assert.equal(over.body.error.code, "quota_exceeded");
});

test("设备看不到也改不了其他设备的附件", async () => {
  const { site, token } = await setup();
  await upload(site, token);
  const other = await pairDevice(site, { deviceName: "手机", deviceKind: "mobile", scopes: ["assignments"] });
  assert.deepEqual((await site.call("GET", "/api/v1/device/attachments", { token: other.deviceToken })).body.items, []);
  await site.call("DELETE", path(), { token: other.deviceToken });
  assert.equal((await site.call("GET", "/api/v1/device/attachments", { token })).body.items.length, 1);
});

test("重新登记附件会清掉旧分块；关闭作业范围立即删除", async () => {
  const { site, token } = await setup();
  await upload(site, token);
  await site.call("PUT", path(), { token, body: meta() });
  assert.equal(site.env.DB.raw.prepare("SELECT COUNT(*) AS n FROM attachment_chunks").get().n, 0);
  await upload(site, token);
  await site.call("PUT", "/api/v1/device/scopes", { token, body: { scopes: ["schedule"] } });
  assert.equal(site.env.DB.raw.prepare("SELECT COUNT(*) AS n FROM attachments").get().n, 0);
  assert.equal(site.env.DB.raw.prepare("SELECT COUNT(*) AS n FROM attachment_chunks").get().n, 0);
});

test("撤销设备和清除云端数据都会删附件", async () => {
  const { site, device, token } = await setup();
  await upload(site, token);
  await site.call("POST", `/api/v1/owner/devices/${device.deviceId}/revoke`, { owner: true });
  assert.equal(site.env.DB.raw.prepare("SELECT COUNT(*) AS n FROM attachments").get().n, 0);
});

test("assignment_detail 任务参数走白名单", async () => {
  const { site } = await setup();
  const task = (args) => site.call("POST", "/api/v1/owner/tasks", { owner: true, body: { operation: "assignment_detail", idempotencyKey: "idem_key_0001", arguments: args } });
  assert.equal((await task({ assignmentId: "phyvlab:1", path: "/etc" })).status, 400);
  assert.equal((await task({ assignmentId: "../x" })).status, 400);
  assert.equal((await task({ assignmentId: "phyvlab:1" })).status, 202);
});

test("共享差异样例：设备端写出的结构能在 Site 合并", () => {
  const patch = fixture("snapshot-patch-v1.json");
  const merged = applySnapshotPatch({ data: { schedule: { days: [1] }, assignments: { sources: [], items: [{ id: "phyvlab:1", title: "一" }, { id: "phyvlab:2", title: "二" }] } } }, patch);
  assert.deepEqual(merged.data.assignments.items.map((i) => i.id), ["phyvlab:3", "phyvlab:1"]);
  assert.equal(merged.baseSnapshotId, undefined);
  assert.equal(merged.sequence, 2);
});
