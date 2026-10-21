import { cp, mkdir, mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
const root = fileURLToPath(new URL('..', import.meta.url));
const destination = resolve(process.argv[2] ?? join(root, '../../.artifacts/bjtuss-remote-template-0.2.0.tar.gz'));
if (destination.startsWith(root)) throw Error('Archive must be outside the source template');
const stage = await mkdtemp(join(tmpdir(), 'bjtuss-template-'));
const target = join(stage, 'bjtuss-remote-template');
await mkdir(join(target, '.openai'), { recursive: true });
for (const name of ['src', 'fixtures', 'migrations', 'drizzle', 'test', 'scripts', 'plugin', 'package.json', 'README.md', '.gitignore', '.env.example']) await cp(join(root, name), join(target, name), { recursive: true });
// 身份只能在新实例中创建；绝不把当前部署的 ID 或运行数据复制出去。
await writeFile(join(target, '.openai/hosting.json'), JSON.stringify({ d1: 'DB', r2: null, capabilities: ['mcp'] }, null, 2)+'\n');
async function scan(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isSymbolicLink()) throw Error('Template cannot contain symlinks');
    if (entry.isDirectory()) { await scan(path); continue; }
    if (/\.(sqlite|db|keystore|jks|pem)$|^\.env$|^\.dev\.vars$/.test(entry.name)) throw Error('Unexpected runtime file');
    const text = await readFile(path, 'utf8');
    if (/"project_id"\s*:\s*"[^"\n]+"/.test(text) || /-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----/.test(text)) throw Error('Identity or credential in template: '+entry.name);
  }
}
await scan(target);
await mkdir(resolve(destination, '..'), { recursive: true });
const result = spawnSync('tar', ['-czf', destination, '-C', stage, 'bjtuss-remote-template'], { encoding: 'utf8' });
if (result.status !== 0) throw Error('Template archiving failed');
const listing = spawnSync('tar', ['-tzf', destination], { encoding: 'utf8' });
if (listing.status !== 0 || !listing.stdout.includes('bjtuss-remote-template/plugin/mcp.json')) throw Error('Archive verification failed');
console.log(`Verified clean template: ${destination}`);
