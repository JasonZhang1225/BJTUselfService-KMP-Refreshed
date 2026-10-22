import { cp, mkdir, readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { join } from 'node:path';
const root = fileURLToPath(new URL('..', import.meta.url));
// 输出目录只写当前产物，不删除用户文件。保留所有 ESM 模块的相对导入路径。
await mkdir(join(root, 'dist/server'), { recursive: true });
await mkdir(join(root, 'dist/.openai'), { recursive: true });
await cp(join(root, 'src'), join(root, 'dist/server'), { recursive: true });
await cp(join(root, 'migrations'), join(root, 'dist/migrations'), { recursive: true });
await cp(join(root, 'drizzle'), join(root, 'dist/.openai/drizzle'), { recursive: true });
const manifest = JSON.parse(await readFile(join(root, '.openai/hosting.json'), 'utf8'));
await writeFile(join(root, 'dist/.openai/hosting.json'), JSON.stringify(manifest, null, 2)+'\n');
await writeFile(join(root, 'dist/server/package.json'), '{"type":"module"}\n');
const worker = await import(pathToFileURL(join(root, 'dist/server/index.js')));
assert.equal(typeof worker.default.fetch, 'function');
assert.equal(manifest.capabilities.includes('mcp'), true);
console.log('Built and verified dist/server/index.js; deployment still requires a private Site identity.');
