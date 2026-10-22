import test from 'node:test';
import assert from 'node:assert/strict';
import { deviceStatusCards } from '../src/device-status.js';
import { page } from '../src/page.js';
import { TEMPLATE_VERSION } from '../src/protocol.js';
import { readFileSync } from 'node:fs';

test('设备独立在线状态与快照，不把手机静默当作离线，也不借用其他设备时间', () => {
  const state = { serverTime: 1000, taskDeviceId: 'win', devices: [
    {deviceId:'phone', deviceKind:'mobile', lastSeenAt: 100},
    {deviceId:'win', deviceKind:'desktop', lastSeenAt: 950},
    {deviceId:'mac', deviceKind:'desktop', lastSeenAt: 500},
    {deviceId:'old', deviceKind:'desktop', revokedAt: 900},
  ], snapshots: [{deviceId:'phone', receivedAt:110}, {deviceId:'win', receivedAt:960}] };
  const cards = deviceStatusCards(state);
  assert.equal(cards.length, 3);
  assert.equal(cards[0].presence, '按需上传 · 不提供持续在线状态');
  assert.equal(cards[0].receivedAt, 110);
  assert.equal(cards[1].presence, '在线');
  assert.equal(cards[1].receivedAt, 960);
  assert.equal(cards[1].taskTarget, true);
  assert.equal(cards[2].presence, '离线或暂未联系');
  assert.equal(cards[2].receivedAt, null);
  assert.equal(deviceStatusCards({...state, devices:[{deviceId:'fresh',deviceKind:'mobile',lastSeenAt:990} ]})[0].presence,'刚刚联系 · 不提供持续在线状态');
});

test('App 对齐 Site、MCP 和插件模板版本，网页提供每台设备独立撤销', () => {
  assert.equal(TEMPLATE_VERSION, '1.8.6-KMP');
  for (const name of ['../package.json','../plugin/plugin.json']) {
    assert.equal(JSON.parse(readFileSync(new URL(name, import.meta.url))).version, TEMPLATE_VERSION);
  }
  assert.ok(page.includes('版本 1.8.6-KMP'));
  assert.ok(page.includes('撤销此设备'));
  assert.ok(!page.includes('device=devices[0]'));
});

test('健康检查兼容已安装 App 的数字解析器，显示与 MCP 保留完整发布版本', async t => {
  const {createSite} = await import('./helpers.js');
  const site=createSite();t.after(()=>site.env.DB.raw.close());
  const response=await site.call('GET','/api/v1/health');
  assert.equal(response.status,200);
  assert.equal(response.body.templateVersion,'1.8.6');
  assert.equal(response.body.releaseVersion,TEMPLATE_VERSION);
  const legacyParts=response.body.templateVersion.split('.').map(Number);
  assert.ok(legacyParts.every(Number.isFinite));
  assert.ok(legacyParts[0]>0, '旧版 App 的 0.4.0 能力门槛应通过');
  const rpc=await site.call('POST','/mcp',{body:{jsonrpc:'2.0',id:1,method:'initialize'}});
  assert.equal(rpc.body.result.serverInfo.version,TEMPLATE_VERSION);
});
