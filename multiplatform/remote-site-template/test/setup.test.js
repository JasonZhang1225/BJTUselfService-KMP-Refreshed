import test from 'node:test';
import assert from 'node:assert/strict';
import { createSite } from './helpers.js';
import { page } from '../src/page.js';
import { createRefreshMonitor } from '../src/refresh-monitor.js';

test('干净私人实例首次可信登录后可自行生成配对信息；机器和其他身份不能领取', async t => {
  const site = createSite(); t.after(() => site.env.DB.raw.close());
  delete site.env.OWNER_TOKEN;
  site.env.OWNER_PRIVATE_BOOTSTRAP = 'true';
  site.env.SITE_DEVICE_ACCESS = 'fixture-platform-access';
  const endpoint = '/api/v1/owner/connection';
  assert.equal((await site.call('POST', endpoint)).status, 401);
  const headers = { 'oai-authenticated-user-id': 'owner-fixture' };
  const result = await site.call('POST', endpoint, { headers });
  assert.equal(result.status, 200);
  const info = JSON.parse(result.body.connectionCode.replace('bjtuss-pair-v1:', ''));
  assert.equal(info.siteUrl, 'https://site.example');
  assert.equal(info.platformAccess, site.env.SITE_DEVICE_ACCESS);
  assert.equal(info.expiresAt, site.clock.now + 600);
  assert.match(info.pairingCode, /^[A-Z2-9]{8}$/);
  assert.equal((await site.call('POST', endpoint, { headers: { 'oai-authenticated-user-id': 'other-fixture' } })).status, 401);
  assert.equal(page.includes(site.env.SITE_DEVICE_ACCESS), false, 'HTML 不嵌入运行时机密');
  const body = { protocolVersion: 1, pairingCode: info.pairingCode, deviceName: 'fixture', accountBindingId: 'bind_fixture0001', scopes: ['schedule'] };
  assert.equal((await site.call('POST', '/api/v1/device/pair', { body })).status, 200);
  assert.equal((await site.call('POST', '/api/v1/device/pair', { body })).status, 403);
  assert.equal(site.env.DB.raw.prepare("SELECT value FROM template_meta WHERE key = 'owner_user_id'").get().value, 'owner-fixture');
});

test('缺少连接机密时明确报部署配置错误且不消耗配对码', async t => {
  const site = createSite(); t.after(() => site.env.DB.raw.close());
  const response = await site.call('POST', '/api/v1/owner/connection', { owner: true });
  assert.equal(response.status, 503);
  assert.equal(response.body.error.code, 'device_access_not_configured');
  assert.equal(site.env.DB.raw.prepare('SELECT count(*) AS n FROM pairing_codes').get().n, 0);
});

for (const [status, code] of [[401, 'sign_in_required'], [403, 'unauthorized'], [503, 'owner_auth_not_configured']]) {
  test(`页面遇到 ${code} 显示真实原因并停轮询；处理后可手动恢复`, async () => {
    let broken = true, scheduled = 0;
    const messages = [];
    const monitor = createRefreshMonitor({
      api: async () => { if (broken) throw Object.assign(Error(code), { status, code }); return { devices: [], latestRefreshTask: null }; },
      onStatus() {}, onTask() {}, onBusy() {}, onError: m => messages.push(m),
      setTimer: () => ++scheduled, clearTimer() {},
    });
    monitor.start(); await monitor.poll();
    assert.equal(messages.at(-1), code);
    assert.equal(scheduled, 0);
    broken = false; await monitor.wake();
    assert.equal(scheduled, 1);
    monitor.stop();
  });
}
