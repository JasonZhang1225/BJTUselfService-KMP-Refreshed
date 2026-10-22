import { validateAgentQuery as validateQuery } from "./query.js";

// 远程协议常量与输入校验。与 KMP 端 agent/remote/RemoteProtocol.kt 一一对应；
// 两边共享 fixtures/ 下的脱敏样例，改字段时同时改两边并更新样例。

export const PROTOCOL_VERSION = 1;
/** 2：data 为 Agent 数据集（与电脑端 CLI 同一结构，见 src/agent-query.js）。 */
export const SNAPSHOT_SCHEMA_VERSION = 2;
export const TEMPLATE_VERSION = "0.5.0";

/** 设备类型：desktop 常驻并领取即时任务；mobile 只在 App 刷新后上传快照。 */
export const DEVICE_KINDS = Object.freeze(["desktop", "mobile"]);
export const MAX_ACTIVE_DEVICES = 5;

/** 允许上云的数据范围。grades、mail（收件箱摘要）默认不上传，由用户在电脑端单独开启。 */
export const SCOPES = Object.freeze(["schedule", "exams", "assignments", "grades", "mail"]);
export const DEFAULT_SCOPES = Object.freeze(["schedule", "exams", "assignments"]);

/** 与 RefreshSource.wire 一致的来源名。 */
export const SOURCES = Object.freeze([
  "home", "mailUnread", "homework", "exams", "courses", "grades",
  "physicsLab", "physicsDemo", "citel", "phyvlab",
]);
export const SOURCE_STATUSES = Object.freeze(["success", "failed", "skipped", "needs_user_action"]);

/** 即时任务操作白名单；不接受任意命令、路径、脚本、URL 或 SQL。 */
export const OPERATIONS = Object.freeze(["query", "refresh_all", "citel_problem", "mail_message"]);
export const TASK_STATUSES = Object.freeze(["queued", "running", "succeeded", "partial", "failed", "expired", "revoked"]);
export const RESULT_STATUSES = Object.freeze(["succeeded", "partial", "failed"]);
export const FRESHNESS = Object.freeze(["local", "latest"]);

export const LIMITS = Object.freeze({
  snapshotBytes: 1024 * 1024,
  resultBytes: 256 * 1024,
  requestBytes: 16 * 1024,
  pairingTtlSeconds: 10 * 60,
  pairingMaxAttempts: 5,
  taskQueueTtlSeconds: 5 * 60,
  leaseSeconds: 120,
  maxLeaseSeconds: 600,
  maxAttempts: 3,
  resultRetentionSeconds: 24 * 3600,
  taskRetentionSeconds: 7 * 24 * 3600,
  heartbeatPersistSeconds: 60,
  minPollIntervalSeconds: 1,
  cleanupBatch: 100,
});

export class ProtocolError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export const bad = (code, message) => new ProtocolError(400, code, message);

const ID_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;
const NAME_PATTERN = /^[^\u0000-\u001f]{1,64}$/;

export function requireObject(value, field) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw bad("bad_request", `${field} 必须是对象`);
  return value;
}

export function requireId(value, field) {
  if (typeof value !== "string" || !ID_PATTERN.test(value)) throw bad("bad_request", `${field} 格式无效`);
  return value;
}

export function requireInt(value, field, min = 0, max = Number.MAX_SAFE_INTEGER) {
  if (!Number.isSafeInteger(value) || value < min || value > max) throw bad("bad_request", `${field} 超出范围`);
  return value;
}

export function requireProtocol(version) {
  if (version !== PROTOCOL_VERSION) {
    throw new ProtocolError(426, "protocol_mismatch", `协议版本不一致（期望 ${PROTOCOL_VERSION}），请更新电脑端或 Site 模板`);
  }
}

export function validateScopes(scopes) {
  if (!Array.isArray(scopes) || scopes.length > SCOPES.length) throw bad("bad_request", "scopes 无效");
  const unique = [...new Set(scopes)];
  if (unique.length !== scopes.length || unique.some((s) => !SCOPES.includes(s))) throw bad("bad_request", "scopes 含未知范围");
  return unique;
}

export function validatePairRequest(body) {
  requireObject(body, "body");
  requireProtocol(body.protocolVersion);
  if (typeof body.pairingCode !== "string" || !/^[A-Z2-9]{8}$/.test(body.pairingCode)) throw bad("bad_request", "配对码格式无效");
  if (typeof body.deviceName !== "string" || !NAME_PATTERN.test(body.deviceName)) throw bad("bad_request", "deviceName 无效");
  return {
    pairingCode: body.pairingCode,
    deviceName: body.deviceName,
    accountBindingId: requireId(body.accountBindingId, "accountBindingId"),
    scopes: validateScopes(body.scopes ?? DEFAULT_SCOPES),
    deviceKind: validateDeviceKind(body.deviceKind),
  };
}

export function validateDeviceKind(kind) {
  if (kind === undefined || kind === null) return "desktop";
  if (!DEVICE_KINDS.includes(kind)) throw bad("bad_request", "deviceKind 无效");
  return kind;
}

/**
 * 快照：data 只能包含 scopes 列出的范围（关闭某范围后下一次上传即移除云端字段）。
 * 逐源 fetchedAt 与 generatedAt/uploadedAt 分开，失败来源保留旧同步时间。
 */
export function validateSnapshot(body, device) {
  requireObject(body, "body");
  if (body.schemaVersion !== SNAPSHOT_SCHEMA_VERSION) throw new ProtocolError(426, "schema_mismatch", "快照格式版本不一致");
  if (body.deviceId !== device.device_id) throw new ProtocolError(403, "device_mismatch", "快照不属于当前设备");
  if (body.accountBindingId !== device.account_binding_id) throw new ProtocolError(409, "binding_mismatch", "学校账号绑定已变化，请重新配对");
  const scopes = validateScopes(body.scopes);
  const data = requireObject(body.data, "data");
  for (const key of Object.keys(data)) {
    if (!scopes.includes(key)) throw bad("scope_violation", `data.${key} 不在已授权范围内`);
  }
  // 快照只能携带当前授权；不能充当权限变更请求，更不能恢复已撤销范围。
  if (body.scopesRevision != null) requireInt(body.scopesRevision, "scopesRevision", 1);
  const allowed = JSON.parse(device.scopes_json);
  if ((body.scopesRevision != null && body.scopesRevision !== device.scopes_revision) || scopes.some(s => !allowed.includes(s))) {
    throw new ProtocolError(409, "scopes_changed", "数据范围已变化，请按最新范围重新生成快照");
  }
  const sources = requireObject(body.sources, "sources");
  for (const [name, source] of Object.entries(sources)) {
    if (!SOURCES.includes(name)) throw bad("bad_request", `未知来源 ${name}`);
    requireObject(source, `sources.${name}`);
    if (!SOURCE_STATUSES.includes(source.status)) throw bad("bad_request", `sources.${name}.status 无效`);
    if (source.fetchedAt !== null && source.fetchedAt !== undefined) requireInt(source.fetchedAt, `sources.${name}.fetchedAt`);
  }
  return {
    snapshotId: requireId(body.snapshotId, "snapshotId"),
    sequence: requireInt(body.sequence, "sequence", 1),
    generatedAt: requireInt(body.generatedAt, "generatedAt"),
    uploadedAt: requireInt(body.uploadedAt, "uploadedAt"),
    scopes,
  };
}

/** 即时任务参数：版本化 DTO + 枚举白名单。 */
export function validateTaskRequest(body) {
  requireObject(body, "body");
  if (!OPERATIONS.includes(body.operation)) throw bad("unsupported_operation", "不支持的操作");
  const idempotencyKey = requireId(body.idempotencyKey, "idempotencyKey");
  const args = requireObject(body.arguments ?? {}, "arguments");
  if (body.operation === "refresh_all" && Object.keys(args).length) throw bad("bad_request", "refresh_all 不接受参数");
  if (body.operation === "query") {
    // 即时查询的参数就是对应查询工具的参数，再加 command 与 freshness；由 agent-query.js 同一套白名单校验。
    if (!FRESHNESS.includes(args.freshness ?? "local")) throw bad("bad_request", "freshness 无效");
    if (typeof args.command !== "string") throw bad("bad_request", "command 无效");
    const { command, freshness, ...params } = args;
    validateQuery(command, params);
  }
  if (body.operation === "mail_message") {
    if (Object.keys(args).some((key) => key !== "messageId")) throw bad("bad_request", "mail_message 只接受 messageId");
    if (typeof args.messageId !== "string" || !/^[A-Za-z0-9:._-]{1,128}$/.test(args.messageId)) throw bad("bad_request", "messageId 无效");
  }
  if (body.operation === "citel_problem") {
    if (typeof args.taskId !== "string" || !/^[1-9][0-9]{0,8}$/.test(args.taskId)) throw bad("bad_request", "CITEL taskId 必须为正整数模块 ID");
    if (Object.keys(args).some((key) => !["taskId", "includeFullText"].includes(key))) throw bad("bad_request", "citel_problem 参数无效");
    if (args.includeFullText !== undefined && typeof args.includeFullText !== "boolean") throw bad("bad_request", "includeFullText 无效");
  }
  return { operation: body.operation, idempotencyKey, arguments: args };
}

export function validateTaskResult(body) {
  requireObject(body, "body");
  const leaseId = requireId(body.leaseId, "leaseId");
  if (!RESULT_STATUSES.includes(body.status)) throw bad("bad_request", "status 无效");
  const result = requireObject(body.result ?? {}, "result");
  let error = null;
  if (body.error !== undefined && body.error !== null) {
    const e = requireObject(body.error, "error");
    if (typeof e.code !== "string" || e.code.length > 64) throw bad("bad_request", "error.code 无效");
    error = { code: e.code, message: typeof e.message === "string" ? e.message.slice(0, 500) : null };
  }
  return { leaseId, status: body.status, result, error };
}
