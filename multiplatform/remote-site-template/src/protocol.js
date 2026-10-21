// 远程协议常量与输入校验。与 KMP 端 agent/remote/RemoteProtocol.kt 一一对应；
// 两边共享 fixtures/ 下的脱敏样例，改字段时同时改两边并更新样例。

export const PROTOCOL_VERSION = 1;
export const SNAPSHOT_SCHEMA_VERSION = 1;
export const TEMPLATE_VERSION = "0.1.0";

/** 允许上云的数据范围。grades、mail 默认不上传，由用户在电脑端单独开启。 */
export const SCOPES = Object.freeze(["schedule", "exams", "assignments", "grades", "mail"]);
export const DEFAULT_SCOPES = Object.freeze(["schedule", "exams", "assignments"]);

/** 与 RefreshSource.wire 一致的来源名。 */
export const SOURCES = Object.freeze([
  "home", "mailUnread", "homework", "exams", "courses", "grades",
  "physicsLab", "physicsDemo", "citel", "phyvlab",
]);
export const SOURCE_STATUSES = Object.freeze(["success", "failed", "skipped", "needs_user_action"]);

/** 即时任务操作白名单；不接受任意命令、路径、脚本、URL 或 SQL。 */
export const OPERATIONS = Object.freeze(["query", "refresh_all", "citel_problem"]);
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
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

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
  };
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
  const allowed = {
    refresh_all: [],
    query: ["freshness", "sources", "course", "date", "taskId"],
    citel_problem: ["taskId", "includeFullText"],
  }[body.operation];
  for (const key of Object.keys(args)) {
    if (!allowed.includes(key)) throw bad("bad_request", `arguments.${key} 不允许`);
  }
  if (body.operation === "query") {
    if (!FRESHNESS.includes(args.freshness ?? "local")) throw bad("bad_request", "freshness 无效");
    if (args.sources !== undefined) {
      if (!Array.isArray(args.sources) || args.sources.length === 0 || args.sources.some((s) => !SOURCES.includes(s))) {
        throw bad("bad_request", "sources 无效");
      }
    }
    if (args.course !== undefined && (typeof args.course !== "string" || !NAME_PATTERN.test(args.course))) throw bad("bad_request", "course 无效");
    if (args.date !== undefined && (typeof args.date !== "string" || !DATE_PATTERN.test(args.date))) throw bad("bad_request", "date 无效");
    if (args.taskId !== undefined) requireId(args.taskId, "taskId");
  }
  if (body.operation === "citel_problem") {
    requireId(args.taskId, "taskId");
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
