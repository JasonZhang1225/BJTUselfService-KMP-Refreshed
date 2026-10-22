// MCP JSONL → 本机虚构数据预览。默认不连接学校，也不读取 App 的任何文件。
import { createInterface } from 'node:readline';
const url = new URL(process.env.BJTU_REMOTE_SITE_URL ?? 'http://127.0.0.1:4173');
if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && url.hostname === '127.0.0.1')) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw Error('Use an HTTPS Site origin or the loopback preview origin');
const headers = { 'content-type': 'application/json' };
if (process.env.BJTU_REMOTE_OWNER_TOKEN) headers.authorization = `Bearer ${process.env.BJTU_REMOTE_OWNER_TOKEN}`;
if (process.env.BJTU_REMOTE_PLATFORM_ACCESS) headers['OAI-Sites-Authorization'] = `Bearer ${process.env.BJTU_REMOTE_PLATFORM_ACCESS}`;
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of lines) {
  if (!line.trim()) continue;
  let rpc;
  try {
    if (Buffer.byteLength(line) > 16384) throw Error('Request too large');
    rpc = JSON.parse(line);
    const response = await fetch(new URL('/mcp', url), { method: 'POST', headers, body: line, redirect: 'error', signal: AbortSignal.timeout(15000) });
    if (!response.ok) throw Error('Remote request rejected');
    if (response.status === 202 || rpc.id === undefined) continue;
    const reply = await response.json();
    process.stdout.write(JSON.stringify(reply)+'\n');
  } catch {
    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: rpc?.id ?? null, error: { code: -32603, message: '无法连接已配置的远程助手，请检查服务与访问授权。' } })+'\n');
  }
}
