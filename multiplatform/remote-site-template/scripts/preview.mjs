// 仅虚构数据的回环预览；从不打开 App 缓存、凭据或学校接口。
import { createServer } from "node:http";
import { createSite, pairDevice, snapshotFor, OWNER_TOKEN } from "../test/helpers.js";
import { handle } from "../src/index.js";

const site = createSite();
site.clock.now = Math.floor(Date.now() / 1000) - 300;
for (const [name, kind, offset] of [['测试 Mac（macOS）','desktop',300],['测试电脑（Windows）','desktop',0],['测试手机（iOS）','mobile',0]]) {
  const device = await pairDevice(site, {deviceName:name, deviceKind:kind});
  await site.call("PUT", "/api/v1/device/snapshot", { token: device.deviceToken, body: snapshotFor(device) });
  if(offset) site.clock.now += offset;
}
const port = Number(process.env.PORT ?? 4173);
const server = createServer(async (req, res) => {
  try {
    if (req.headers.host !== `127.0.0.1:${port}`) { res.writeHead(403).end(); return; }
    const headers = new Headers(req.headers);
    // 浏览器只见虚构数据；测试拥有者令牌留在本机进程内。
    headers.delete("oai-authenticated-user-id");
    if (req.url.startsWith("/api/v1/owner/") || req.url === "/mcp") headers.set("authorization", `Bearer ${OWNER_TOKEN}`);
    let size = 0; const chunks = [];
    for await (const chunk of req) { size += chunk.length; if (size > 1024 * 1024) { res.writeHead(413).end(); return; } chunks.push(chunk); }
    const body = Buffer.concat(chunks);
    const response = await handle(new Request(`http://127.0.0.1:${port}${req.url}`, { method: req.method, headers, body: body.length ? body : undefined }), site.env);
    res.writeHead(response.status, Object.fromEntries(response.headers)); res.end(Buffer.from(await response.arrayBuffer()));
  } catch { res.writeHead(500).end('Preview error'); }
});
server.listen(port, '127.0.0.1', () => console.log(`Local: http://127.0.0.1:${port} (fictional data only)`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
