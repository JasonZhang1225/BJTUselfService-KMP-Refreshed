import { ProtocolError } from "./protocol.js";
import { AgentQueryError, COMMANDS, agentQueryError, parseAgentQuery, runAgentQuery } from "./agent-query.js";

export const SCOPE_SOURCES = Object.freeze({
  schedule: ["courses", "physicsLab", "physicsDemo"], exams: ["exams"],
  assignments: ["homework", "phyvlab", "citel", "physicsLab"],
  grades: ["grades"], mail: ["mailUnread"],
});

/** 每个查询至少需要其中一个上云范围（与 KMP remoteCommandScopes 一致）。 */
export const COMMAND_SCOPES = Object.freeze({
  status: ["schedule", "exams", "assignments", "grades", "mail"],
  calendar: ["schedule", "exams", "assignments"],
  homework: ["assignments"], exams: ["exams"], grades: ["grades"], mail: ["mail"],
});

export function permittedSources(scopes) {
  return [...new Set(scopes.flatMap(s => SCOPE_SOURCES[s] ?? []))];
}

export function commandAllowed(command, scopes) {
  return (COMMAND_SCOPES[command] ?? []).some(scope => scopes.includes(scope));
}

/** 校验查询参数；错误统一转成协议错误（400）。 */
export function validateAgentQuery(command, args) {
  if (!COMMANDS.includes(command)) throw new ProtocolError(400, "bad_request", `未知查询：${command}`);
  try {
    return parseAgentQuery(command, args ?? {});
  } catch (error) {
    if (error instanceof AgentQueryError) throw new ProtocolError(400, error.code, error.message);
    throw error;
  }
}

const clock = (epoch) => {
  if (epoch == null) return null;
  const d = new Date((epoch + 8 * 3600) * 1000);
  return `${d.toISOString().slice(0, 10)} ${d.toISOString().slice(11, 16)}`;
};

/**
 * 用云端快照执行查询（get_homework 等工具）：数据与本机 CLI 同一结构，查询引擎同构。
 * payload 来自 service.latestSnapshot()（已按设备当前授权过滤）；now 为 Unix 秒。
 */
export function querySnapshot(payload, command, args, now) {
  const query = validateAgentQuery(command, args);
  const scopes = payload.device?.scopes ?? [];
  if (!commandAllowed(command, scopes)) throw new ProtocolError(403, "scope_denied", "这类数据没有授权上云；请在电脑端“允许上云的数据”里开启");
  const remote = {
    device: payload.device ? { name: payload.device.deviceName, lastSeen: clock(payload.device.lastSeenAt) } : null,
    snapshotReceived: clock(payload.snapshot?.receivedAt),
    snapshotScopes: payload.snapshot?.scopes ?? [],
  };
  if (!payload.snapshot) {
    return { ...agentQueryError(null, command, now, "cloud_snapshot", "no_snapshot", "电脑还没有上传过快照：请确认电脑上的交大自由行已登录并开启远程同步。"), remote };
  }
  if (payload.snapshot.schemaVersion !== 2) {
    return { ...agentQueryError(null, command, now, "cloud_snapshot", "schema_mismatch", "电脑端版本与 Site 模板不一致，请更新电脑上的交大自由行。"), remote };
  }
  const sources = Object.fromEntries(Object.entries(payload.snapshot.sources ?? {})
    .map(([name, s]) => [name, { fetchedAt: s.fetchedAt ?? null, status: s.status ?? null, reason: s.reason ?? null }]));
  return { ...runAgentQuery(payload.snapshot.data ?? {}, sources, query, now, "cloud_snapshot"), remote };
}
