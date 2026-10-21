// HTTP 入口（Workers 风格：export default { fetch, scheduled }，D1 绑定名 DB）。
// 平台访问控制（owner-private、机器访问凭据 OAI-Sites-Authorization 等）在应用代码之前生效；
// 这里只做应用层的设备令牌与拥有者鉴权，二者都不能替代平台门槛。见 README“待验证事实”。

import { LIMITS, PROTOCOL_VERSION, ProtocolError, TEMPLATE_VERSION } from "./protocol.js";
import { RemoteService, digestEquals, sha256Hex } from "./core.js";
import { mcpReply } from "./mcp.js";
import { page } from "./page.js";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
  "x-content-type-options": "nosniff",
};

function json(status, body, extraHeaders = {}) {
  return new Response(JSON.stringify(body), { status, headers: { ...JSON_HEADERS, ...extraHeaders } });
}

function errorResponse(error) {
  if (error instanceof ProtocolError) {
    const headers = error.status === 429 ? { "retry-after": "60" } : {};
    return json(error.status, { error: { code: error.code, message: error.message } }, headers);
  }
  // 不回显内部异常，避免泄露 SQL 或数据内容。
  return json(500, { error: { code: "internal", message: "服务器内部错误" } });
}

async function readBody(request, limit) {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > limit) throw new ProtocolError(413, "too_large", "请求体过大");
  const text = await request.text();
  const size = new TextEncoder().encode(text).length;
  if (size > limit) throw new ProtocolError(413, "too_large", "请求体过大");
  if (text.length === 0) return { body: {}, size };
  try {
    return { body: JSON.parse(text), size };
  } catch {
    throw new ProtocolError(400, "bad_json", "请求不是合法 JSON");
  }
}

/**
 * 拥有者鉴权：部署时设置机密 OWNER_TOKEN，请求带 `Authorization: Bearer <OWNER_TOKEN>`。
 * 未设置时拒绝全部拥有者接口（安全默认），不会因漏配而公开数据。
 * 平台能可靠传递拥有者身份后，可在这里改用平台身份（PR 3 首项验证）。
 */
async function authorizeOwner(request, env) {
  // Sites 在平台边界验证身份；仅匹配部署时配置的 Site 内拥有者 ID。
  if (env.OWNER_USER_ID && request.headers.get("oai-authenticated-user-id") === env.OWNER_USER_ID) return;
  // 仅由部署者确认 owner-private 后开启。首个可信 Sites 用户身份固定入库，之后不随分享范围改变。
  const identity = request.headers.get("oai-authenticated-user-id");
  if (!env.OWNER_USER_ID && env.OWNER_PRIVATE_BOOTSTRAP === "true" && identity) {
    await env.DB.prepare("INSERT OR IGNORE INTO template_meta (key, value) VALUES ('owner_user_id', ?)").bind(identity).run();
    const owner = await env.DB.prepare("SELECT value FROM template_meta WHERE key = 'owner_user_id'").first();
    if (owner?.value === identity) return;
    throw new ProtocolError(401, "unauthorized", "当前 ChatGPT 用户不是本站拥有者");
  }
  if (!env.OWNER_TOKEN || env.OWNER_TOKEN.length < 24) {
    throw new ProtocolError(503, "owner_auth_not_configured", "Site 尚未配置拥有者令牌 OWNER_TOKEN");
  }
  const match = /^Bearer (.+)$/.exec(request.headers.get("authorization") ?? "");
  const presented = await sha256Hex(match?.[1] ?? "");
  if (!digestEquals(presented, await sha256Hex(env.OWNER_TOKEN))) {
    throw new ProtocolError(401, "unauthorized", "拥有者令牌无效");
  }
}

export async function handle(request, env, options = {}) {
  const service = new RemoteService(env.DB, options);
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "");
  const method = request.method.toUpperCase();
  try {
    if (path === "/api/v1/identity" && method === "GET") {
      const userId = request.headers.get("oai-authenticated-user-id");
      if (!userId) throw new ProtocolError(401, "sign_in_required", "请先用 ChatGPT 登录你的私人 Site");
      return json(200, { userId });
    }
    if (path === "" && method === "GET") return new Response(page, { headers: {
      "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff",
      "content-security-policy": "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'self'",
    } });
    if (path.startsWith("/api/v1/owner/") && !["GET", "HEAD"].includes(method)) {
      const origin = request.headers.get("origin");
      if (origin && origin !== url.origin) throw new ProtocolError(403, "origin_denied", "拒绝跨站请求");
    }
    if (path === "/api/v1/health" && method === "GET") {
      return json(200, { ok: true, protocolVersion: PROTOCOL_VERSION, templateVersion: TEMPLATE_VERSION });
    }
    if (path === "/mcp" && method === "POST") {
      const { body } = await readBody(request, LIMITS.requestBytes);
      // 元数据发现无业务数据，供 Sites 在首次拥有者访问前建立 canonical 插件。
      if (!["server/discover", "initialize", "notifications/initialized", "tools/list", "ping"].includes(body?.method)) {
        await authorizeOwner(request, env);
        await service.cleanup();
      }
      const reply = await mcpReply(service, body);
      return reply === null ? new Response(null, { status: 202, headers: JSON_HEADERS }) : json(200, reply);
    }

    // ----- 设备接口（Windows 主动连接；Site 不登录学校）-----
    if (path === "/api/v1/device/pair" && method === "POST") {
      const { body } = await readBody(request, LIMITS.requestBytes);
      return json(200, await service.pairDevice(body));
    }
    if (path.startsWith("/api/v1/device/")) {
      const device = await service.authenticateDevice(request.headers.get("authorization"), request.headers.get("x-bjtu-protocol"));
      if (path === "/api/v1/device/snapshot" && method === "PUT") {
        const { body, size } = await readBody(request, LIMITS.snapshotBytes);
        return json(200, await service.putSnapshot(device, body, size));
      }
      if (path === "/api/v1/device/scopes" && method === "PUT") {
        const { body } = await readBody(request, LIMITS.requestBytes);
        return json(200, await service.updateScopes(device, body));
      }
      if (path === "/api/v1/device/poll" && method === "POST") {
        await readBody(request, LIMITS.requestBytes);
        return json(200, await service.poll(device));
      }
      const renew = /^\/api\/v1\/device\/tasks\/([^/]+)\/renew$/.exec(path);
      if (renew && method === "POST") {
        const { body } = await readBody(request, LIMITS.requestBytes);
        return json(200, await service.renewLease(device, renew[1], body));
      }
      const result = /^\/api\/v1\/device\/tasks\/([^/]+)\/result$/.exec(path);
      if (result && method === "POST") {
        const { body, size } = await readBody(request, LIMITS.resultBytes);
        return json(200, await service.submitResult(device, result[1], body, size));
      }
      throw new ProtocolError(404, "not_found", "接口不存在");
    }

    // ----- 拥有者接口（Site 页面与 MCP 工具使用）-----
    if (path.startsWith("/api/v1/owner/")) {
      await authorizeOwner(request, env);
      // 顺带做一批有界清理，不依赖页面持续打开。
      await service.cleanup();
      if (path === "/api/v1/owner/status" && method === "GET") return json(200, await service.status());
      if (path === "/api/v1/owner/pairings" && method === "POST") return json(200, await service.createPairingCode());
      if (path === "/api/v1/owner/snapshot" && method === "GET") return json(200, await service.latestSnapshot());
      if (path === "/api/v1/owner/tasks" && method === "POST") {
        const { body } = await readBody(request, LIMITS.requestBytes);
        return json(202, await service.createTask(body));
      }
      const task = /^\/api\/v1\/owner\/tasks\/([^/]+)$/.exec(path);
      if (task && method === "GET") return json(200, await service.getTask(task[1]));
      const revoke = /^\/api\/v1\/owner\/devices\/([^/]+)\/revoke$/.exec(path);
      if (revoke && method === "POST") return json(200, await service.revokeDevice(revoke[1]));
      if (path === "/api/v1/owner/queries" && method === "PUT") {
        const { body } = await readBody(request, LIMITS.requestBytes);
        if (typeof body.paused !== "boolean") throw new ProtocolError(400, "bad_request", "paused 必须是布尔值");
        return json(200, await service.setQueriesPaused(body.paused));
      }
      if (path === "/api/v1/owner/data" && method === "DELETE") return json(200, await service.clearData());
      throw new ProtocolError(404, "not_found", "接口不存在");
    }
    throw new ProtocolError(404, "not_found", "接口不存在");
  } catch (error) {
    return errorResponse(error);
  }
}

export default {
  fetch: (request, env) => handle(request, env),
  /** 定时清理（每小时一次即可）：过期任务、24 小时前的结果、7 天前的任务元数据、过期配对码。 */
  scheduled: async (_event, env) => {
    await new RemoteService(env.DB).cleanup();
  },
};
