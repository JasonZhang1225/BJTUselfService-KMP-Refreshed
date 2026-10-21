// 远程服务核心：只依赖 D1 形状的数据库接口（prepare().bind().first()/all()/run()），
// 便于在 Sites/D1 上运行，也能在本地用 node:sqlite 适配器做真实 SQL 测试。

import {
  DEFAULT_SCOPES, LIMITS, MAX_ACTIVE_DEVICES, PROTOCOL_VERSION, ProtocolError, SCOPES, TEMPLATE_VERSION,
  bad, requireId, requireInt, requireObject, requireProtocol,
  validatePairRequest, validateScopes, validateSnapshot, validateTaskRequest, validateTaskResult,
} from "./protocol.js";
import { SCOPE_SOURCES, commandAllowed, permittedSources } from "./query.js";

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const PAIR_FAILURE_WINDOW_SECONDS = 10 * 60;
const PAIR_FAILURE_LIMIT = 10;
const TERMINAL = ["succeeded", "partial", "failed"];

function base64url(bytes) {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomBytes(length) {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

export const randomId = (bytes = 16) => base64url(randomBytes(bytes));

function randomCode() {
  // 32 个字符恰好整除 256，没有取模偏差。
  return [...randomBytes(8)].map((b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
}

/** 常量时间比较两个十六进制摘要。 */
export function digestEquals(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function parseJson(text, fallback) {
  try {
    return JSON.parse(text);
  } catch {
    return fallback;
  }
}

/** 一组快照中最近收到的那份的序号与时间。 */
function latestMeta(snaps) {
  const row = [...snaps].sort((x, y) => y.row.received_at - x.row.received_at)[0].row;
  return { snapshotId: row.snapshot_id, sequence: row.sequence, generatedAt: row.generated_at, uploadedAt: row.uploaded_at, receivedAt: row.received_at };
}

function taskView(row) {
  if (!row) return null;
  return {
    requestId: row.request_id,
    deviceId: row.device_id,
    accountBindingId: row.account_binding_id,
    operation: row.operation,
    arguments: parseJson(row.arguments_json, {}),
    createdAt: row.created_at,
    expiresAt: row.expires_at,
    status: row.status,
    attempt: row.attempt,
    completedAt: row.completed_at ?? null,
    result: row.result_json ? parseJson(row.result_json, null) : null,
    resultCleared: row.result_cleared_at != null,
  };
}

export class RemoteService {
  /**
   * @param db D1Database 形状的对象
   * @param options.now 返回 Unix 秒，测试可注入
   */
  constructor(db, { now = () => Math.floor(Date.now() / 1000) } = {}) {
    this.db = db;
    this.now = now;
  }

  q(sql, ...args) {
    return this.db.prepare(sql).bind(...args);
  }

  // ---------- 拥有者：配对 ----------

  async createPairingCode() {
    const now = this.now();
    const code = randomCode();
    await this.q("INSERT INTO pairing_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)",
      await sha256Hex(code), now, now + LIMITS.pairingTtlSeconds).run();
    return { pairingCode: code, expiresAt: now + LIMITS.pairingTtlSeconds };
  }

  async pairingLocked(now) {
    const row = await this.q("SELECT value FROM template_meta WHERE key = 'pair_failures'").first();
    const state = parseJson(row?.value ?? "", null);
    return state && now - state.start < PAIR_FAILURE_WINDOW_SECONDS && state.count >= PAIR_FAILURE_LIMIT;
  }

  async recordPairFailure(now) {
    const row = await this.q("SELECT value FROM template_meta WHERE key = 'pair_failures'").first();
    let state = parseJson(row?.value ?? "", null);
    if (!state || now - state.start >= PAIR_FAILURE_WINDOW_SECONDS) state = { start: now, count: 0 };
    state.count += 1;
    await this.q("INSERT INTO template_meta (key, value) VALUES ('pair_failures', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
      JSON.stringify(state)).run();
  }

  /**
   * 设备用一次性配对码换取设备令牌（只返回这一次，Site 只存摘要）。
   * 一个 Site 可同时绑定电脑和手机（最多 MAX_ACTIVE_DEVICES 台）。同名同类型的旧绑定视为同一台设备重新配对，
   * 连同其未完成任务一起撤销；超出上限时撤销最早的绑定。
   */
  async pairDevice(body) {
    const input = validatePairRequest(body);
    const now = this.now();
    if (await this.pairingLocked(now)) throw new ProtocolError(429, "pairing_locked", "配对失败次数过多，请 10 分钟后在 Site 重新生成配对码");
    const codeHash = await sha256Hex(input.pairingCode);
    // 先占用：只有一个并发请求能把同一配对码标记为已用。
    const claimed = await this.q(
      "UPDATE pairing_codes SET used_at = ?, attempts = attempts + 1 WHERE code_hash = ? AND used_at IS NULL AND expires_at > ? AND attempts < ? RETURNING code_hash",
      now, codeHash, now, LIMITS.pairingMaxAttempts).first();
    if (!claimed) {
      await this.recordPairFailure(now);
      throw new ProtocolError(403, "pairing_invalid", "配对码无效、已过期或已使用");
    }
    const deviceId = `dev_${randomId(12)}`;
    const deviceToken = `bjtud_${randomId(32)}`;
    const sameDevice = "SELECT device_id FROM devices WHERE revoked_at IS NULL AND device_name = ? AND device_kind = ?";
    await this.db.batch([
      this.q(`UPDATE tasks SET status = 'revoked', completed_at = ? WHERE status IN ('queued', 'running') AND device_id IN (${sameDevice})`,
        now, input.deviceName, input.deviceKind),
      this.q(`UPDATE devices SET revoked_at = ? WHERE device_id IN (${sameDevice})`, now, input.deviceName, input.deviceKind),
      this.q(`INSERT INTO devices (device_id, token_hash, device_name, account_binding_id, protocol_version, created_at, last_seen_at, scopes_json, scopes_revision, device_kind)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)`,
        deviceId, await sha256Hex(deviceToken), input.deviceName, input.accountBindingId, PROTOCOL_VERSION, now, now, JSON.stringify(input.scopes), input.deviceKind),
    ]);
    const overflow = (await this.activeDevices()).slice(MAX_ACTIVE_DEVICES);
    for (const old of overflow) await this.revokeDevice(old.device_id);
    return { protocolVersion: PROTOCOL_VERSION, deviceId, deviceToken, accountBindingId: input.accountBindingId, scopes: input.scopes, scopesRevision: 1, templateVersion: TEMPLATE_VERSION, deviceKind: input.deviceKind };
  }

  // ---------- 设备 ----------

  /** 校验 `Authorization: Bearer <设备令牌>`；撤销或未知令牌一律 401（设备进入需重新配对状态）。 */
  async authenticateDevice(authorization, protocolHeader) {
    const match = /^Bearer (bjtud_[A-Za-z0-9_-]{20,80})$/.exec(authorization ?? "");
    if (!match) throw new ProtocolError(401, "unauthorized", "缺少设备令牌");
    if (protocolHeader !== undefined && protocolHeader !== null) requireProtocol(Number(protocolHeader));
    const device = await this.q("SELECT * FROM devices WHERE token_hash = ? AND revoked_at IS NULL", await sha256Hex(match[1])).first();
    if (!device) throw new ProtocolError(401, "unauthorized", "设备令牌无效或已撤销，请重新配对");
    return device;
  }

  async heartbeat(device) {
    const now = this.now();
    if (device.last_seen_at == null || now - device.last_seen_at >= LIMITS.heartbeatPersistSeconds) {
      await this.q("UPDATE devices SET last_seen_at = ? WHERE device_id = ?", now, device.device_id).run();
      device.last_seen_at = now;
    }
  }

  /** 上传最新完整快照：同一 snapshotId 重传幂等；序号不大于已存的视为旧快照拒收。 */
  async putSnapshot(device, body, rawLength) {
    if (rawLength > LIMITS.snapshotBytes) throw new ProtocolError(413, "too_large", "快照超过大小上限");
    const input = validateSnapshot(body, device);
    const now = this.now();
    const existing = await this.q("SELECT snapshot_id, sequence FROM snapshots WHERE device_id = ?", device.device_id).first();
    if (existing && existing.snapshot_id === input.snapshotId) {
      if (existing.sequence !== input.sequence) throw new ProtocolError(409, "snapshot_conflict", "同一快照 ID 的序号不一致");
      return { snapshotId: input.snapshotId, sequence: input.sequence, stored: false, duplicate: true };
    }
    if (existing && input.sequence <= existing.sequence) {
      throw new ProtocolError(409, "stale_snapshot", `序号 ${input.sequence} 不大于已存的 ${existing.sequence}`);
    }
    const result = await this.q(`INSERT INTO snapshots (device_id, snapshot_id, sequence, schema_version, generated_at, uploaded_at, received_at, body_json)
        SELECT ?, ?, ?, ?, ?, ?, ?, ?
        WHERE EXISTS (SELECT 1 FROM devices WHERE device_id = ? AND scopes_revision = ? AND revoked_at IS NULL)
        ON CONFLICT(device_id) DO UPDATE SET snapshot_id = excluded.snapshot_id, sequence = excluded.sequence,
          schema_version = excluded.schema_version, generated_at = excluded.generated_at, uploaded_at = excluded.uploaded_at,
          received_at = excluded.received_at, body_json = excluded.body_json
        WHERE excluded.sequence > snapshots.sequence`,
      device.device_id, input.snapshotId, input.sequence, body.schemaVersion, input.generatedAt, input.uploadedAt, now, JSON.stringify(body),
      device.device_id, device.scopes_revision).run();
    if ((result.meta?.changes ?? 0) === 0) throw new ProtocolError(409, "stale_snapshot", "已有更新的快照");
    await this.heartbeat(device);
    return { snapshotId: input.snapshotId, sequence: input.sequence, stored: true, duplicate: false };
  }

  /** 设备更新数据范围（关闭上云范围时立即生效，不必等下一次快照）。 */
  async updateScopes(device, body) {
    requireObject(body, "body");
    const scopes = validateScopes(body.scopes);
    if (body.scopesRevision == null && scopes.some(s => !parseJson(device.scopes_json, []).includes(s))) {
      throw new ProtocolError(409, "scopes_changed", "扩大数据范围需要带版本的明确授权，请更新电脑端");
    }
    const revision = body.scopesRevision == null ? device.scopes_revision + 1 : requireInt(body.scopesRevision, "scopesRevision", 1);
    const scopesJson = JSON.stringify(scopes);
    // 同版本重复请求幂等；新版本以条件写阻止晚到的旧请求覆盖。
    if (revision === device.scopes_revision && scopesJson === device.scopes_json) return { scopes, scopesRevision: revision };
    const updated = await this.q(`UPDATE devices SET scopes_json = ?, scopes_revision = ?
      WHERE device_id = ? AND scopes_revision < ? AND revoked_at IS NULL RETURNING device_id`,
      scopesJson, revision, device.device_id, revision).first();
    if (!updated) {
      const current = await this.q("SELECT scopes_json, scopes_revision FROM devices WHERE device_id = ? AND revoked_at IS NULL", device.device_id).first();
      if (current?.scopes_revision === revision && current.scopes_json === scopesJson) return { scopes, scopesRevision: revision };
      throw new ProtocolError(409, "scopes_changed", "数据范围请求已过期，请使用最新范围版本");
    }
    return { scopes, scopesRevision: revision };
  }

  /** 轮询：原子领取最早一条可执行任务并设置租约；没有任务时只搭载心跳。 */
  async poll(device) {
    const now = this.now();
    await this.heartbeat(device);
    const leaseId = `lease_${randomId(12)}`;
    const row = await this.q(`UPDATE tasks SET status = 'running', lease_id = ?, lease_until = ?, attempt = attempt + 1
        WHERE request_id = (
          SELECT request_id FROM tasks
          WHERE device_id = ? AND expires_at > ? AND attempt < ?
            AND (status = 'queued' OR (status = 'running' AND lease_until < ?))
          ORDER BY created_at LIMIT 1)
          AND (status = 'queued' OR (status = 'running' AND lease_until < ?))
        RETURNING *`,
      leaseId, now + LIMITS.leaseSeconds, device.device_id, now, LIMITS.maxAttempts, now, now).first();
    return {
      protocolVersion: PROTOCOL_VERSION,
      serverTime: now,
      scopesRevision: device.scopes_revision,
      task: row ? { ...taskView(row), leaseId: row.lease_id, leaseUntil: row.lease_until } : null,
    };
  }

  async renewLease(device, requestId, body) {
    requireId(requestId, "requestId");
    requireObject(body, "body");
    const leaseId = requireId(body.leaseId, "leaseId");
    const seconds = requireInt(body.leaseSeconds ?? LIMITS.leaseSeconds, "leaseSeconds", 10, LIMITS.maxLeaseSeconds);
    const now = this.now();
    const row = await this.q(`UPDATE tasks SET lease_until = MIN(?, created_at + ? + ?)
        WHERE request_id = ? AND device_id = ? AND status = 'running' AND lease_id = ? AND lease_until >= ?
        RETURNING lease_until`,
      now + seconds, LIMITS.taskQueueTtlSeconds, LIMITS.maxLeaseSeconds, requestId, device.device_id, leaseId, now).first();
    if (!row) throw new ProtocolError(409, "stale_lease", "租约已失效或已被重新领取");
    return { requestId, leaseUntil: row.lease_until };
  }

  /**
   * 提交结果：至少一次交付，靠租约 ID 去重。同一租约重复提交幂等；
   * 租约被别人重新领取后，旧执行者的结果被拒，不能覆盖新租约。
   */
  async submitResult(device, requestId, body, rawLength) {
    requireId(requestId, "requestId");
    if (rawLength > LIMITS.resultBytes) throw new ProtocolError(413, "too_large", "结果超过大小上限");
    const input = validateTaskResult(body);
    const now = this.now();
    const resultJson = JSON.stringify({ status: input.status, data: input.result, error: input.error });
    const updated = await this.q(`UPDATE tasks SET status = ?, result_json = ?, completed_at = ?
        WHERE request_id = ? AND device_id = ? AND status = 'running' AND lease_id = ? AND lease_until >= ?
        RETURNING request_id`,
      input.status, resultJson, now, requestId, device.device_id, input.leaseId, now).first();
    if (updated) return { requestId, accepted: true, duplicate: false };
    const current = await this.q("SELECT status, lease_id FROM tasks WHERE request_id = ? AND device_id = ?", requestId, device.device_id).first();
    if (current && TERMINAL.includes(current.status) && current.lease_id === input.leaseId) {
      return { requestId, accepted: true, duplicate: true };
    }
    if (!current) throw new ProtocolError(404, "not_found", "任务不存在");
    throw new ProtocolError(409, "stale_lease", "租约已失效、任务已过期或已被撤销");
  }

  // ---------- 拥有者：查询与管理 ----------

  /** 全部有效绑定，最新配对在前。 */
  async activeDevices() {
    return (await this.q("SELECT * FROM devices WHERE revoked_at IS NULL ORDER BY created_at DESC").all()).results ?? [];
  }

  /** 执行即时任务的电脑：最近在线的 desktop 绑定。手机不轮询，不能领取任务。 */
  async taskDevice() {
    return this.q("SELECT * FROM devices WHERE revoked_at IS NULL AND device_kind = 'desktop' ORDER BY COALESCE(last_seen_at, 0) DESC, created_at DESC LIMIT 1").first();
  }

  async queriesPaused() {
    const row = await this.q("SELECT value FROM template_meta WHERE key = 'queries_paused'").first();
    return row?.value === "true";
  }

  async setQueriesPaused(paused) {
    await this.q("INSERT INTO template_meta (key, value) VALUES ('queries_paused', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
      paused ? "true" : "false").run();
    return { paused };
  }

  async ensureQueriesAllowed() {
    if (await this.queriesPaused()) throw new ProtocolError(423, "queries_paused", "拥有者已暂停远程查询");
  }

  /** 创建即时任务；相同幂等键返回已有任务，不重复入队。 */
  async createTask(body) {
    await this.ensureQueriesAllowed();
    const input = validateTaskRequest(body);
    const device = await this.taskDevice();
    if (!device) throw new ProtocolError(409, "no_device", "还没有绑定可以执行即时查询的电脑（手机只在刷新时上传快照）");
    const scopes = parseJson(device.scopes_json, DEFAULT_SCOPES);
    if (!taskAllowed(input.operation, input.arguments, scopes)) {
      throw new ProtocolError(403, "scope_denied", "查询超出设备授权范围，请在电脑端“允许上云的数据”里开启");
    }
    const now = this.now();
    const requestId = `req_${randomId(12)}`;
    const inserted = await this.q(`INSERT INTO tasks (request_id, idempotency_key, device_id, account_binding_id, operation, arguments_json, created_at, expires_at, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'queued')
        ON CONFLICT(device_id, idempotency_key) DO NOTHING RETURNING *`,
      requestId, input.idempotencyKey, device.device_id, device.account_binding_id, input.operation,
      JSON.stringify(input.arguments), now, now + LIMITS.taskQueueTtlSeconds).first();
    if (inserted) return { ...taskView(inserted), created: true };
    const existing = await this.q("SELECT * FROM tasks WHERE device_id = ? AND idempotency_key = ?", device.device_id, input.idempotencyKey).first();
    if (existing.operation !== input.operation || existing.arguments_json !== JSON.stringify(input.arguments)) {
      throw new ProtocolError(409, "idempotency_conflict", "相同幂等键对应不同的请求");
    }
    return { ...taskView(existing), created: false };
  }

  async getTask(requestId) {
    await this.ensureQueriesAllowed();
    requireId(requestId, "requestId");
    const row = await this.q("SELECT * FROM tasks WHERE request_id = ?", requestId).first();
    if (!row) throw new ProtocolError(404, "not_found", "任务不存在或已清理");
    const device = (await this.activeDevices()).find((d) => d.device_id === row.device_id);
    if (!device) return { ...taskView({ ...row, result_json: null }), status: "revoked" };
    const scopes = parseJson(device.scopes_json, []);
    const args = parseJson(row.arguments_json, {});
    if (!taskAllowed(row.operation, args, scopes)) throw new ProtocolError(403, "scope_denied", "任务数据范围已撤销");
    return taskView(row);
  }

  /**
   * 合并后的最新快照：每个上云范围取“该范围数据同步时间最新”的那台设备的快照（同步时间相同取生成较晚的），
   * 并按那台设备当前授权再过滤——关闭的范围立即不可读。电脑和手机谁刚刷新过就用谁的。
   */
  async latestSnapshot() {
    await this.ensureQueriesAllowed();
    const devices = await this.activeDevices();
    if (!devices.length) return { device: null, devices: [], snapshot: null };
    const rows = (await this.q(`SELECT * FROM snapshots WHERE device_id IN (${devices.map(() => "?").join(",")})`,
      ...devices.map((d) => d.device_id)).all()).results ?? [];
    const snaps = rows.map((row) => {
      const device = devices.find((d) => d.device_id === row.device_id);
      return { row, device, body: parseJson(row.body_json, null), allowed: parseJson(device.scopes_json, []) };
    }).filter((s) => s.body);
    const union = SCOPES.filter((scope) => devices.some((d) => parseJson(d.scopes_json, []).includes(scope)));
    const primary = (await this.taskDevice()) ?? [...devices].sort((x, y) => (y.last_seen_at ?? 0) - (x.last_seen_at ?? 0))[0];
    const view = { ...this.deviceView(primary), scopes: union };
    const data = {};
    const sources = {};
    const origins = {};
    const used = new Set();
    for (const scope of SCOPES) {
      const freshness = (snap) => Math.max(0, ...SCOPE_SOURCES[scope].map((s) => snap.body.sources?.[s]?.fetchedAt ?? 0));
      const chosen = snaps.filter((s) => s.allowed.includes(scope) && (s.body.scopes ?? []).includes(scope) && s.body.data?.[scope] !== undefined)
        .sort((x, y) => (freshness(y) - freshness(x)) || (y.row.generated_at - x.row.generated_at))[0];
      if (!chosen) continue;
      data[scope] = chosen.body.data[scope];
      origins[scope] = chosen.device.device_name;
      used.add(chosen);
      for (const source of SCOPE_SOURCES[scope]) {
        const state = chosen.body.sources?.[source];
        if (state && (sources[source] === undefined || (state.fetchedAt ?? 0) > (sources[source].fetchedAt ?? 0))) sources[source] = state;
      }
    }
    if (!Object.keys(data).length) return { device: view, devices: devices.map((d) => this.deviceView(d)), snapshot: null };
    return {
      device: view,
      devices: devices.map((d) => this.deviceView(d)),
      // 顶层的序号与时间取最近收到的那份；每台设备的快照另列在 parts。
      snapshot: { ...latestMeta([...used]), schemaVersion: 2, scopes: Object.keys(data), data, sources, origins,
        parts: [...used].map((u) => ({ deviceId: u.device.device_id, deviceName: u.device.device_name, ...latestMeta([u]) })) },
    };
  }

  deviceView(device) {
    return {
      deviceId: device.device_id,
      deviceName: device.device_name,
      deviceKind: device.device_kind ?? "desktop",
      createdAt: device.created_at,
      revokedAt: device.revoked_at ?? null,
      lastSeenAt: device.last_seen_at ?? null,
      scopes: parseJson(device.scopes_json, []),
      scopesRevision: device.scopes_revision,
    };
  }

  async status() {
    const devices = (await this.q("SELECT * FROM devices ORDER BY created_at DESC LIMIT 20").all()).results ?? [];
    const taskDevice = await this.taskDevice();
    const active = devices.filter((d) => d.revoked_at == null);
    const snapshots = active.length ? ((await this.q(`SELECT device_id, snapshot_id, sequence, generated_at, uploaded_at, received_at, body_json FROM snapshots WHERE device_id IN (${active.map(() => "?").join(",")})`,
      ...active.map((d) => d.device_id)).all()).results ?? []) : [];
    const counts = (await this.q("SELECT status, COUNT(*) AS n FROM tasks GROUP BY status").all()).results ?? [];
    const latestRefresh = taskDevice ? await this.q("SELECT request_id, idempotency_key, status, created_at, completed_at, expires_at FROM tasks WHERE device_id = ? AND operation = 'refresh_all' ORDER BY created_at DESC, rowid DESC LIMIT 1", taskDevice.device_id).first() : null;
    // 逐源同步时间：每个来源取各设备里最新的一份（只看该设备仍授权的来源）。
    const sources = {};
    for (const row of snapshots) {
      const device = active.find((d) => d.device_id === row.device_id);
      const allowed = permittedSources(parseJson(device.scopes_json, []));
      for (const [name, state] of Object.entries(parseJson(row.body_json, {})?.sources ?? {})) {
        if (allowed.includes(name) && (sources[name] === undefined || (state.fetchedAt ?? 0) > (sources[name].fetchedAt ?? 0))) sources[name] = state;
      }
    }
    const newest = [...snapshots].sort((x, y) => y.received_at - x.received_at)[0];
    return {
      templateVersion: TEMPLATE_VERSION,
      protocolVersion: PROTOCOL_VERSION,
      serverTime: this.now(),
      queriesPaused: await this.queriesPaused(),
      devices: devices.map((d) => this.deviceView(d)),
      taskDeviceId: taskDevice?.device_id ?? null,
      snapshot: newest ? {
        snapshotId: newest.snapshot_id,
        sequence: newest.sequence,
        generatedAt: newest.generated_at,
        uploadedAt: newest.uploaded_at,
        receivedAt: newest.received_at,
        sources,
      } : null,
      snapshots: snapshots.map((row) => ({
        deviceId: row.device_id,
        deviceName: active.find((d) => d.device_id === row.device_id)?.device_name ?? null,
        generatedAt: row.generated_at,
        receivedAt: row.received_at,
      })),
      tasks: Object.fromEntries(counts.map((c) => [c.status, c.n])),
      latestRefreshTask: latestRefresh ? {
        requestId: latestRefresh.request_id, idempotencyKey: latestRefresh.idempotency_key,
        status: latestRefresh.status, createdAt: latestRefresh.created_at,
        completedAt: latestRefresh.completed_at ?? null, expiresAt: latestRefresh.expires_at,
      } : null,
    };
  }

  async revokeDevice(deviceId) {
    requireId(deviceId, "deviceId");
    const now = this.now();
    await this.db.batch([
      this.q("UPDATE tasks SET status = 'revoked', completed_at = ? WHERE device_id = ? AND status IN ('queued', 'running')", now, deviceId),
      this.q("UPDATE devices SET revoked_at = COALESCE(revoked_at, ?) WHERE device_id = ?", now, deviceId),
    ]);
    return { deviceId, revoked: true };
  }

  /** 清除云端数据：快照与任务全部删除；设备绑定保留（撤销另行操作）。 */
  async clearData() {
    await this.db.batch([this.q("DELETE FROM snapshots"), this.q("DELETE FROM tasks")]);
    return { cleared: true };
  }

  /** 有界批次清理；由定时任务和拥有者请求顺带触发，不依赖页面持续打开。 */
  async cleanup() {
    const now = this.now();
    const n = LIMITS.cleanupBatch;
    const results = await this.db.batch([
      this.q(`UPDATE tasks SET status = 'expired', completed_at = ? WHERE rowid IN (
          SELECT rowid FROM tasks WHERE (status = 'queued' AND expires_at <= ?)
            OR (status = 'running' AND lease_until < ? AND (attempt >= ? OR expires_at <= ?)) LIMIT ?)`,
        now, now, now, LIMITS.maxAttempts, now, n),
      this.q(`UPDATE tasks SET result_json = NULL, result_cleared_at = ? WHERE rowid IN (
          SELECT rowid FROM tasks WHERE result_json IS NOT NULL AND completed_at < ? LIMIT ?)`,
        now, now - LIMITS.resultRetentionSeconds, n),
      this.q("DELETE FROM tasks WHERE rowid IN (SELECT rowid FROM tasks WHERE created_at < ? LIMIT ?)", now - LIMITS.taskRetentionSeconds, n),
      this.q("DELETE FROM pairing_codes WHERE rowid IN (SELECT rowid FROM pairing_codes WHERE expires_at < ? LIMIT ?)", now - 3600, n),
    ]);
    return {
      expired: results[0].meta?.changes ?? 0,
      resultsCleared: results[1].meta?.changes ?? 0,
      tasksDeleted: results[2].meta?.changes ?? 0,
      pairingCodesDeleted: results[3].meta?.changes ?? 0,
    };
  }
}

export { bad };

/** 即时任务在当前授权下是否允许（与 KMP remoteTaskAllowed 一致）。 */
function taskAllowed(operation, args, scopes) {
  if (operation === "query") return commandAllowed(args?.command, scopes);
  if (operation === "citel_problem") return scopes.includes("assignments");
  if (operation === "mail_message") return scopes.includes("mail");
  return true;
}
