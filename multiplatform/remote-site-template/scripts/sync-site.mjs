// 无 Sites source helper 时的确定性发布准备；凭据只经隐藏 stdin 与 Git 子进程内存。
import { readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, isAbsolute } from 'node:path';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const root = fileURLToPath(new URL('..', import.meta.url));
const archive = process.argv[2];
assert.ok(archive && isAbsolute(archive), 'Supply an absolute deployment archive path outside this Site checkout');
const manifest = JSON.parse(await readFile(resolve(root, '.openai/hosting.json'), 'utf8'));
assert.ok(manifest.project_id, 'Register this Site and persist project_id before synchronizing');

if (process.stdin.isTTY) process.stdin.setRawMode(true);
process.stdin.setEncoding('utf8');
console.log('Ready for Site credential JSON on stdin (input is hidden).');
const input = await new Promise((accept, reject) => {
  let value = '';
  process.stdin.on('data', chunk => {
    value += chunk;
    if (value.length > 65_536) reject(Error('Credential input is too large'));
    if (value.includes('\n')) accept(value.slice(0, value.indexOf('\n')));
  });
  process.stdin.on('end', () => reject(Error('Expected one newline-terminated credential JSON object')));
});
process.stdin.pause();
if (process.stdin.isTTY) process.stdin.setRawMode(false);
let parsed;
try { parsed = JSON.parse(input); } catch { throw Error('Expected valid credential JSON; input is hidden'); }
const { credential } = parsed;
assert.ok(credential && ['work_proxy', 'http_extra_header'].includes(credential.auth_mode), 'Unsupported Sites credential mode; use the current Sites source helper');
const remote = new URL(credential.remote_url);
assert.ok(remote.protocol === 'https:' && !remote.username && !remote.password && !remote.search && !remote.hash, 'Expected the credential-free HTTPS repository URL returned by Sites');
assert.ok(typeof credential.branch === 'string' && credential.branch, 'Missing Sites source branch');
const gitEnv = { ...process.env, GIT_TERMINAL_PROMPT: '0' };
const token = credential.auth_mode === 'http_extra_header' ? credential.token : '';
if (credential.auth_mode === 'http_extra_header') {
  assert.ok(typeof token === 'string' && token && !/[\r\n]/.test(token), 'Missing Sites repository token');
  Object.assign(gitEnv, { GIT_CONFIG_COUNT: '2',
    GIT_CONFIG_KEY_0: `http.${credential.remote_url}.extraheader`, GIT_CONFIG_VALUE_0: `Authorization: Bearer ${token}`,
    GIT_CONFIG_KEY_1: 'http.followRedirects', GIT_CONFIG_VALUE_1: 'false' });
}
function run(command, args, { network = false, allowed = [0] } = {}) {
  const result = spawnSync(command, args, { cwd: root, env: network ? gitEnv : process.env, encoding: 'utf8' });
  if (!allowed.includes(result.status)) {
    const message = (result.stderr || result.stdout || result.error?.message || 'Command failed');
    throw Error(token ? message.replaceAll(token, '[redacted]') : message);
  }
  return { text: result.stdout?.trim() ?? '', status: result.status };
}
run('git', ['check-ref-format', '--branch', credential.branch]);
const tests = (await readdir(resolve(root, 'test'))).filter(n => n.endsWith('.test.js')).map(n => `test/${n}`);
run('node', ['--no-warnings', '--test', ...tests]);
run('node', ['scripts/build.mjs']);
const existing = run('git', ['rev-parse', '--show-toplevel'], { allowed: [0, 128] });
if (existing.status === 0) assert.equal(resolve(existing.text), resolve(root), 'Use an independent Site checkout, not a directory inside the maintenance repository');
else run('git', ['init', '-b', credential.branch]);
const origin = run('git', ['remote', 'get-url', 'origin'], { allowed: [0, 2] });
if (origin.status === 0) assert.equal(origin.text, credential.remote_url, 'Existing source remote belongs to another project');
else run('git', ['remote', 'add', 'origin', credential.remote_url]);
assert.equal(run('git', ['symbolic-ref', '--short', 'HEAD']).text, credential.branch, 'Checkout the Site source branch before synchronizing');
for (const [key, value] of [['user.name', 'Codex'], ['user.email', 'codex@users.noreply.github.com']]) {
  if (run('git', ['config', '--get', key], { allowed: [0, 1] }).status !== 0) run('git', ['config', key, value]);
}
run('git', ['add', '--all']);
if (run('git', ['diff', '--cached', '--quiet'], { allowed: [0, 1] }).status === 1) run('git', ['commit', '-m', 'fix: 完善私人 Site 与插件一次交付流程']);
run('git', ['push', '--set-upstream', 'origin', credential.branch], { network: true });
const sha = run('git', ['rev-parse', 'HEAD']).text;
run('node', ['scripts/package-site.mjs', archive]);
console.log(JSON.stringify({ project_id: manifest.project_id, commit_sha: sha, archive }));
