import { ProtocolError, SOURCES, requireObject } from "./protocol.js";

export const SCOPE_SOURCES = Object.freeze({
  schedule: ["courses", "physicsLab", "physicsDemo"], exams: ["exams"],
  assignments: ["homework", "phyvlab", "citel", "physicsLab", "physicsDemo"],
  grades: ["grades"], mail: ["mailUnread"],
});

export function validateQuery(args = {}) {
  requireObject(args, "query");
  if (Object.keys(args).some(k => !["sources", "course", "date", "taskId"].includes(k))) throw new ProtocolError(400, "bad_request", "未知查询参数");
  if (args.sources !== undefined && (!Array.isArray(args.sources) || !args.sources.length || args.sources.some(s => !SOURCES.includes(s)))) throw new ProtocolError(400, "bad_request", "sources 无效");
  for (const key of ["course", "taskId"]) if (args[key] !== undefined && (typeof args[key] !== "string" || !args[key].trim() || args[key].length > 128)) throw new ProtocolError(400, "bad_request", `${key} 无效`);
  if (args.date !== undefined && (typeof args.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(args.date) || !Number.isFinite(Date.parse(args.date)) || new Date(args.date).toISOString().slice(0, 10) !== args.date)) throw new ProtocolError(400, "bad_request", "date 无效");
  return args;
}

export function permittedSources(scopes) {
  return [...new Set(scopes.flatMap(s => SCOPE_SOURCES[s] ?? []))];
}

/** 旧客户端的全局筛选元数据可能包含其他范围的课程名，不能随某一个范围返回。 */
export function withoutGlobalFilterMetadata(value) {
  if (Array.isArray(value)) return value.map(withoutGlobalFilterMetadata);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value)
    .filter(([key]) => key !== "availableFilters").map(([key, nested]) => [key, withoutGlobalFilterMetadata(nested)]));
  return value;
}

// 只过滤业务记录数组，保留 notes、时间范围等解释字段。无匹配字段的记录不误报为匹配。
function project(value, args) {
  if (Array.isArray(value)) return value.filter(item => {
    if (!item || typeof item !== "object") return true;
    const source = ["course", "course_platform"].includes(item.source) ? "homework" : item.source;
    if (args.sources && source && !args.sources.includes(source)) return false;
    if (args.course && !(item.courseName ?? item.course ?? item.name ?? item.title ?? "").toLowerCase().includes(args.course.toLowerCase())) return false;
    if (args.taskId && String(item.id ?? item.taskId ?? "") !== args.taskId) return false;
    const due = item.dueTime ?? item.dueAt;
    const dueDate = due?.includes("T00:00:00+08:00") ? new Date(Date.parse(due.slice(0, 10)) - 86400000).toISOString().slice(0, 10) : due?.slice(0, 10);
    if (args.date && (item.agendaDate ?? item.dueAgendaDate ?? item.date ?? item.start?.slice(0, 10) ?? dueDate) !== args.date) return false;
    return true;
  });
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).filter(([k]) => k !== "citel" || !args.sources || args.sources.includes("citel")).map(([k, v]) => [k, project(v, args)]));
  return value;
}

export function querySnapshot(payload, input = {}) {
  const args = validateQuery(input);
  const allowed = payload.device?.scopes ?? [];
  const allowedSources = permittedSources(allowed);
  if (args.sources?.some(s => !allowedSources.includes(s))) throw new ProtocolError(403, "scope_denied", "查询超出设备授权范围");
  if (!payload.snapshot) return { ...payload, freshness: "snapshot", query: args };
  const selected = allowed.filter(scope => !args.sources || SCOPE_SOURCES[scope].some(s => args.sources.includes(s)));
  const snapshot = payload.snapshot;
  return { ...payload, freshness: "snapshot", query: args, snapshot: { ...snapshot,
    scopes: snapshot.scopes.filter(s => selected.includes(s)),
    sources: Object.fromEntries(Object.entries(snapshot.sources ?? {}).filter(([s]) => permittedSources(selected).includes(s) && (!args.sources || args.sources.includes(s)))),
    data: Object.fromEntries(Object.entries(snapshot.data ?? {}).filter(([s]) => selected.includes(s)).map(([s, v]) => [s, project(withoutGlobalFilterMetadata(v), args)])),
  } };
}
